
package com.voidcrew.micromaxdisplay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class UsbReceiver {

    public static final int PORT = 27183;

    private static final String TAG = "UsbReceiver";
    private static final int MAX_FRAME_SIZE = 4 * 1024 * 1024;
    private static final int HEADER_SIZE = 4;

    public interface Listener {
        void onStatus(String status);
        void onFrame(Bitmap frame);
        void onError(String message, Exception exception);
    }

    private final Listener listener;
    private final Object decodeLock = new Object();

    private volatile boolean running;

    private ServerSocket serverSocket;
    private Socket clientSocket;

    private Thread receiverThread;
    private Thread decoderThread;

    // Only the newest compressed frame is retained.
    private byte[] latestJpeg;

    // Performance counters.
    private final AtomicInteger receivedFrames = new AtomicInteger();
    private final AtomicInteger decodedFrames = new AtomicInteger();
    private final AtomicInteger replacedFrames = new AtomicInteger();
    private final AtomicLong decodeTimeNanos = new AtomicLong();

    public UsbReceiver(Listener listener) {
        this.listener = listener;
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
    }

    private void runServer() {
        try {
            serverSocket = new ServerSocket(PORT);

            listener.onStatus("Listening on port " + PORT);

            while (running) {
                listener.onStatus("Waiting for Windows...");

                try {
                    clientSocket = serverSocket.accept();
                    clientSocket.setTcpNoDelay(true);

                    listener.onStatus("Windows connected");

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

                if (running) {
                    listener.onStatus("Connection lost");
                }
            }

        } catch (IOException exception) {
            if (running) {
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

    private void receiveFrames(Socket socket) throws IOException {

        InputStream input =
                new BufferedInputStream(socket.getInputStream());

        byte[] header = new byte[HEADER_SIZE];

        while (running && !socket.isClosed()) {

            if (!readFully(input, header, 0, HEADER_SIZE)) {
                return;
            }

            int frameSize =
                    ((header[0] & 0xff) << 24)
                    | ((header[1] & 0xff) << 16)
                    | ((header[2] & 0xff) << 8)
                    | (header[3] & 0xff);

            if (frameSize <= 0 || frameSize > MAX_FRAME_SIZE) {
                throw new IOException(
                        "Invalid JPEG frame size: " + frameSize
                );
            }

            byte[] jpeg = new byte[frameSize];

            if (!readFully(input, jpeg, 0, frameSize)) {
                return;
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
    }

    private void decodeLoop() {

        long statsStart = System.nanoTime();

        // Reuse decoder options rather than allocating them per frame.
        BitmapFactory.Options options =
                new BitmapFactory.Options();

        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;

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

            Bitmap frame = BitmapFactory.decodeByteArray(
                    jpeg,
                    0,
                    jpeg.length,
                    options
            );

            long decodeDuration =
                    System.nanoTime() - decodeStart;

            decodeTimeNanos.addAndGet(decodeDuration);

            if (frame == null) {
                listener.onError(
                        "Received malformed JPEG frame",
                        null
                );
                continue;
            }

            long callbackStart = System.nanoTime();

            listener.onFrame(frame);

            long callbackDuration =
                    System.nanoTime() - callbackStart;

            decodedFrames.incrementAndGet();

            long now = System.nanoTime();

            if (now - statsStart >= 2_000_000_000L) {

                double seconds =
                        (now - statsStart) / 1_000_000_000.0;

                int rx = receivedFrames.getAndSet(0);
                int decoded = decodedFrames.getAndSet(0);
                int replaced = replacedFrames.getAndSet(0);

                long totalDecode =
                        decodeTimeNanos.getAndSet(0);

                double rxFps = rx / seconds;
                double decodeFps = decoded / seconds;

                double avgDecodeMs = decoded > 0
                        ? (totalDecode / 1_000_000.0) / decoded
                        : 0.0;

                double callbackMs =
                        callbackDuration / 1_000_000.0;

                Log.i(TAG,
                        String.format(
                                Locale.US,
                                "RX: %.1f FPS | Decode: %.1f FPS"
                                + " | Avg decode: %.1f ms"
                                + " | Replaced: %d"
                                + " | Callback: %.2f ms"
                                + " | Bitmap: %s",
                                rxFps,
                                decodeFps,
                                avgDecodeMs,
                                replaced,
                                callbackMs,
                                frame.getConfig()
                        )
                );

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
