
package com.voidcrew.micromaxdisplay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.util.ArrayList;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class UsbReceiver {

    public static final int PORT = 27183;

    private static final String TAG = "UsbReceiver";

    private static final int MAX_FRAME_SIZE = 4 * 1024 * 1024;
    private static final int HEADER_SIZE = 4;
    private static final int H264_HEADER_SIZE = 12;

    private static final byte[] H264_MAGIC = {
            'H', '2', '6', '4'
    };

    // TCH1 | action:u8 | pointerId:u8 | reserved:u16 | x:f32 | y:f32
    public static final int TOUCH_HELLO = 0;
    public static final int TOUCH_DOWN = 1;
    public static final int TOUCH_MOVE = 2;
    public static final int TOUCH_UP = 3;
    public static final int TOUCH_CANCEL = 4;

    private static final int TOUCH_PACKET_SIZE = 16;

    public static final int STATE_STARTING = 0;
    public static final int STATE_WAITING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_STREAMING = 3;
    public static final int STATE_LOST = 4;
    public static final int STATE_ERROR = 5;

    public interface Listener {
        void onStatus(String status);
        void onFrame(Bitmap frame);
        void onError(String message, Exception exception);
        void onConnectionState(int state);
    }

    private final Listener listener;

    private final Object decodeLock = new Object();

    private volatile boolean running;

    private ServerSocket serverSocket;
    private Socket clientSocket;

    private Thread receiverThread;
    private Thread decoderThread;

    private volatile Thread touchWriterThread;
    private volatile Socket touchSocket;

    /*
     * H.264 decoder is created and owned by MainActivity.
     * MainActivity must attach it before starting this receiver.
     */
    private volatile H264Decoder h264Decoder;

    private final BlockingQueue<byte[]> touchQueue =
            new ArrayBlockingQueue<byte[]>(128);

    // JPEG latest-frame slot.
    private byte[] latestJpeg;

    private final AtomicInteger receivedFrames = new AtomicInteger();
    private final AtomicInteger decodedFrames = new AtomicInteger();
    private final AtomicInteger displayedFrames = new AtomicInteger();
    private final AtomicInteger replacedFrames = new AtomicInteger();
    private final AtomicInteger staleFrames = new AtomicInteger();

    private final AtomicLong decodeTimeNanos = new AtomicLong();

    private final BitmapFactory.Options decodeOptions =
            new BitmapFactory.Options();

    private final Object bitmapPoolLock = new Object();

    private final ArrayList<Bitmap> bitmapPool =
            new ArrayList<Bitmap>();

    private static final int MAX_BITMAP_POOL_SIZE = 3;

    public UsbReceiver(Listener listener) {

        this.listener = listener;

        decodeOptions.inPreferredConfig = Bitmap.Config.RGB_565;
        decodeOptions.inDither = false;
        decodeOptions.inSampleSize = 1;
        decodeOptions.inMutable = true;
        decodeOptions.inTempStorage = new byte[16 * 1024];
    }

    /**
     * Attach the decoder before calling start().
     */
    public void setH264Decoder(H264Decoder decoder) {
        this.h264Decoder = decoder;
    }

    public synchronized void start() {

        if (running) {
            return;
        }

        running = true;

        decoderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                decodeLoop();
            }
        }, "JpegDecoder");

        receiverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runServer();
            }
        }, "UsbReceiver");

        decoderThread.start();
        receiverThread.start();
    }

    public void stop() {

        running = false;

        synchronized (decodeLock) {
            latestJpeg = null;
            decodeLock.notifyAll();
        }

        stopTouchWriter();
        closeClient();
        closeServer();

        Thread receiver;
        Thread decoder;

        synchronized (this) {
            receiver = receiverThread;
            decoder = decoderThread;

            receiverThread = null;
            decoderThread = null;
        }

        if (receiver != null) {
            receiver.interrupt();
        }

        if (decoder != null) {
            decoder.interrupt();
        }

        clearBitmapPool();
    }

    private Bitmap acquireBitmap() {

        synchronized (bitmapPoolLock) {

            int size = bitmapPool.size();

            if (size == 0) {
                return null;
            }

            return bitmapPool.remove(size - 1);
        }
    }

    public void releaseBitmap(Bitmap bitmap) {

        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }

        synchronized (bitmapPoolLock) {

            if (!running || bitmapPool.size() >= MAX_BITMAP_POOL_SIZE) {
                bitmap.recycle();
                return;
            }

            bitmapPool.add(bitmap);
        }
    }

    private void clearBitmapPool() {

        synchronized (bitmapPoolLock) {

            for (int i = 0; i < bitmapPool.size(); i++) {

                Bitmap bitmap = bitmapPool.get(i);

                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }

            bitmapPool.clear();
        }
    }

    private void runServer() {

        try {

            serverSocket = new ServerSocket(PORT);

            listener.onStatus("Listening on port " + PORT);

            while (running) {

                listener.onConnectionState(STATE_WAITING);
                listener.onStatus("Waiting for Windows...");

                boolean wasConnected = false;

                try {

                    clientSocket = serverSocket.accept();
                    clientSocket.setTcpNoDelay(true);

                    wasConnected = true;

                    listener.onConnectionState(STATE_CONNECTED);
                    listener.onStatus("Windows connected");

                    startTouchWriter(clientSocket);

                    receiveFrames(clientSocket);

                } catch (SocketException exception) {

                    if (running) {
                        listener.onError(
                                "Receiver socket error",
                                exception
                        );
                    }

                } catch (IOException exception) {

                    if (running) {
                        listener.onError(
                                "Connection error",
                                exception
                        );
                    }

                } finally {

                    closeClient();
                }

                if (running && wasConnected) {

                    listener.onConnectionState(STATE_LOST);
                    listener.onStatus("Connection lost");

                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

        } catch (IOException exception) {

            if (running) {

                listener.onConnectionState(STATE_ERROR);

                listener.onError(
                        "Unable to listen on port " + PORT,
                        exception
                );
            }

        } finally {

            closeClient();
            closeServer();

            running = false;

            synchronized (decodeLock) {
                decodeLock.notifyAll();
            }
        }
    }

    /**
     * Reads either JPEG or H.264 packets.
     *
     * JPEG:
     * [4-byte length][JPEG payload]
     *
     * H.264:
     * [H264][4-byte length][4-byte sequence][Annex-B access unit]
     */
    private void receiveFrames(Socket socket) throws IOException {

        InputStream input =
                new BufferedInputStream(socket.getInputStream());

        byte[] firstFourBytes = new byte[HEADER_SIZE];

        while (running && !socket.isClosed()) {

            if (!readFully(
                    input,
                    firstFourBytes,
                    0,
                    HEADER_SIZE)) {

                return;
            }

            if (isH264Magic(firstFourBytes)) {

                receiveH264AccessUnit(input);

            } else {

                receiveJpegFrame(input, firstFourBytes);
            }
        }
    }

    private boolean isH264Magic(byte[] header) {

        return header[0] == H264_MAGIC[0]
                && header[1] == H264_MAGIC[1]
                && header[2] == H264_MAGIC[2]
                && header[3] == H264_MAGIC[3];
    }

    /**
     * Reads the remaining 8 bytes of the H264 header:
     *
     * length:u32
     * sequence:u32
     *
     * Then reads one complete Annex-B access unit.
     */
    private void receiveH264AccessUnit(InputStream input)
            throws IOException {

        byte[] remainingHeader = new byte[8];

        if (!readFully(
                input,
                remainingHeader,
                0,
                remainingHeader.length)) {

            throw new IOException("Incomplete H.264 packet header");
        }

        int frameSize = readIntBigEndian(
                remainingHeader,
                0
        );

        int sequence = readIntBigEndian(
                remainingHeader,
                4
        );

        if (frameSize <= 0 || frameSize > MAX_FRAME_SIZE) {

            throw new IOException(
                    "Invalid H.264 access unit size: " + frameSize
            );
        }

        H264Decoder decoder = h264Decoder;

        if (decoder == null || !decoder.isRunning()) {

            throw new IOException(
                    "H.264 decoder is not running. "
                            + "Attach and start it before starting UsbReceiver."
            );
        }

        byte[] accessUnit = new byte[frameSize];

        if (!readFully(
                input,
                accessUnit,
                0,
                frameSize)) {

            throw new IOException(
                    "Incomplete H.264 access unit. Sequence: " + sequence
            );
        }

        /*
         * Do not replace or discard compressed H.264 frames.
         * Preserve their order for reference-frame decoding.
         */
        boolean accepted = decoder.queueAccessUnit(accessUnit);

        if (!accepted && running) {

            throw new IOException(
                    "H.264 decoder rejected access unit. Sequence: "
                            + sequence
            );
        }

        listener.onConnectionState(STATE_STREAMING);
    }

    private void receiveJpegFrame(
            InputStream input,
            byte[] header) throws IOException {

        int frameSize = readIntBigEndian(header, 0);

        if (frameSize <= 0 || frameSize > MAX_FRAME_SIZE) {

            throw new IOException(
                    "Invalid JPEG frame size: " + frameSize
            );
        }

        byte[] jpeg = new byte[frameSize];

        if (!readFully(input, jpeg, 0, frameSize)) {
            throw new IOException("Incomplete JPEG frame");
        }

        receivedFrames.incrementAndGet();

        synchronized (decodeLock) {

            if (latestJpeg != null) {
                replacedFrames.incrementAndGet();
            }

            latestJpeg = jpeg;

            decodeLock.notifyAll();
        }
    }

    private static int readIntBigEndian(byte[] data, int offset) {

        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    /**
     * Queue a normalized touch event for Windows.
     * Socket I/O is performed by TouchWriter, never on the UI thread.
     */
    public boolean sendTouchEvent(
            int action,
            int pointerId,
            float normalizedX,
            float normalizedY) {

        if (action < TOUCH_DOWN || action > TOUCH_CANCEL) {
            return false;
        }

        if (!running || touchSocket == null || touchSocket.isClosed()) {
            return false;
        }

        float x = Math.max(0.0f, Math.min(1.0f, normalizedX));
        float y = Math.max(0.0f, Math.min(1.0f, normalizedY));

        byte[] packet = createTouchPacket(
                action,
                pointerId,
                x,
                y
        );

        if (touchQueue.offer(packet)) {
            return true;
        }

        if (action == TOUCH_MOVE) {
            return false;
        }

        byte[][] retained = new byte[touchQueue.size()][];
        int count = 0;

        byte[] queued;

        while ((queued = touchQueue.poll()) != null) {

            if (queued[4] != (byte) TOUCH_MOVE
                    && count < retained.length) {

                retained[count++] = queued;
            }
        }

        for (int i = 0; i < count; i++) {
            touchQueue.offer(retained[i]);
        }

        return touchQueue.offer(packet);
    }

    private byte[] createTouchPacket(
            int action,
            int pointerId,
            float x,
            float y) {

        byte[] packet = new byte[TOUCH_PACKET_SIZE];

        packet[0] = 'T';
        packet[1] = 'C';
        packet[2] = 'H';
        packet[3] = '1';

        packet[4] = (byte) action;
        packet[5] = (byte) (pointerId & 0xff);

        packet[6] = 0;
        packet[7] = 0;

        int xBits = Float.floatToIntBits(x);
        int yBits = Float.floatToIntBits(y);

        packet[8] = (byte) (xBits >>> 24);
        packet[9] = (byte) (xBits >>> 16);
        packet[10] = (byte) (xBits >>> 8);
        packet[11] = (byte) xBits;

        packet[12] = (byte) (yBits >>> 24);
        packet[13] = (byte) (yBits >>> 16);
        packet[14] = (byte) (yBits >>> 8);
        packet[15] = (byte) yBits;

        return packet;
    }

    private void startTouchWriter(final Socket socket) {

        touchQueue.clear();
        touchSocket = socket;

        touchWriterThread = new Thread(new Runnable() {

            @Override
            public void run() {

                try {

                    OutputStream output = socket.getOutputStream();

                    output.write(createTouchPacket(
                            TOUCH_HELLO,
                            0,
                            0.0f,
                            0.0f
                    ));

                    output.flush();

                    Log.i(TAG, "Touch channel handshake sent");

                    while (running
                            && !socket.isClosed()
                            && touchSocket == socket) {

                        byte[] packet = touchQueue.poll(
                                500,
                                TimeUnit.MILLISECONDS
                        );

                        if (packet != null) {

                            output.write(packet);
                            output.flush();
                        }
                    }

                } catch (InterruptedException ignored) {

                    Thread.currentThread().interrupt();

                } catch (IOException exception) {

                    if (running && !socket.isClosed()) {

                        Log.w(
                                TAG,
                                "Touch channel writer stopped",
                                exception
                        );
                    }

                } finally {

                    if (touchSocket == socket) {
                        touchSocket = null;
                    }
                }
            }

        }, "TouchWriter");

        touchWriterThread.start();
    }

    private void stopTouchWriter() {

        touchSocket = null;
        touchQueue.clear();

        Thread writer = touchWriterThread;
        touchWriterThread = null;

        if (writer != null) {
            writer.interrupt();
        }
    }

    /**
     * Existing JPEG decoding path.
     */
    private void decodeLoop() {

        long statsStart = System.nanoTime();

        while (running) {

            byte[] jpeg;

            synchronized (decodeLock) {

                while (latestJpeg == null && running) {

                    try {
                        decodeLock.wait();

                    } catch (InterruptedException exception) {

                        if (!running) {
                            return;
                        }
                    }
                }

                if (!running) {
                    return;
                }

                jpeg = latestJpeg;
                latestJpeg = null;
            }

            long decodeStart = System.nanoTime();

            Bitmap reusableBitmap = acquireBitmap();
            Bitmap frame = null;

            if (reusableBitmap != null) {

                decodeOptions.inBitmap = reusableBitmap;

                try {

                    frame = BitmapFactory.decodeByteArray(
                            jpeg,
                            0,
                            jpeg.length,
                            decodeOptions
                    );

                } catch (IllegalArgumentException exception) {

                    reusableBitmap.recycle();

                } finally {

                    decodeOptions.inBitmap = null;
                }
            }

            if (frame == null) {

                decodeOptions.inBitmap = null;

                frame = BitmapFactory.decodeByteArray(
                        jpeg,
                        0,
                        jpeg.length,
                        decodeOptions
                );
            }

            long decodeDuration =
                    System.nanoTime() - decodeStart;

            decodeTimeNanos.addAndGet(decodeDuration);
            decodedFrames.incrementAndGet();

            if (frame == null) {

                listener.onError(
                        "Received malformed JPEG frame",
                        null
                );

                continue;
            }

            if (!running) {

                if (!frame.isRecycled()) {
                    frame.recycle();
                }

                return;
            }

            long callbackStart = System.nanoTime();

            listener.onFrame(frame);

            long callbackDuration =
                    System.nanoTime() - callbackStart;

            displayedFrames.incrementAndGet();

            long now = System.nanoTime();

            if (now - statsStart >= 2_000_000_000L) {

                double seconds =
                        (now - statsStart) / 1_000_000_000.0;

                int rx = receivedFrames.getAndSet(0);
                int decoded = decodedFrames.getAndSet(0);
                int displayed = displayedFrames.getAndSet(0);

                int replaced = replacedFrames.getAndSet(0);
                int staleSkipped = staleFrames.getAndSet(0);

                long totalDecode =
                        decodeTimeNanos.getAndSet(0);

                double rxFps = rx / seconds;
                double decodeFps = decoded / seconds;
                double displayFps = displayed / seconds;

                double avgDecodeMs = decoded > 0
                        ? (totalDecode / 1_000_000.0) / decoded
                        : 0.0;

                double callbackMs =
                        callbackDuration / 1_000_000.0;

                Log.i(TAG, String.format(
                        Locale.US,
                        "RX: %.1f FPS"
                                + " | Decode: %.1f FPS"
                                + " | Display: %.1f FPS"
                                + " | Avg decode: %.1f ms"
                                + " | Replaced: %d"
                                + " | Stale skipped: %d"
                                + " | Callback: %.2f ms"
                                + " | Bitmap: %s",
                        rxFps,
                        decodeFps,
                        displayFps,
                        avgDecodeMs,
                        replaced,
                        staleSkipped,
                        callbackMs,
                        frame.getConfig()
                ));

                statsStart = now;
            }
        }
    }

    private static boolean readFully(
            InputStream input,
            byte[] buffer,
            int offset,
            int length
    ) throws IOException {

        int remaining = length;

        while (remaining > 0) {

            int count = input.read(
                    buffer,
                    offset + (length - remaining),
                    remaining
            );

            if (count < 0) {
                return false;
            }

            if (count == 0) {
                continue;
            }

            remaining -= count;
        }

        return true;
    }

    private synchronized void closeClient() {

        stopTouchWriter();

        if (clientSocket != null) {

            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }

            clientSocket = null;
        }
    }

    private synchronized void closeServer() {

        if (serverSocket != null) {

            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }

            serverSocket = null;
        }
    }
}
