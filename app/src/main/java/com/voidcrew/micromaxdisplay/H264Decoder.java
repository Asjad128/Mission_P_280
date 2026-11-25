
package com.voidcrew.micromaxdisplay;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class H264Decoder {

    private static final String TAG = "H264Decoder";

    private static final String MIME_TYPE = "video/avc";

    private static final int QUEUE_CAPACITY = 30;

    private static final int INPUT_TIMEOUT_US = 10000;
    private static final int OUTPUT_TIMEOUT_US = 10000;

    private final int width;
    private final int height;

    private final Surface surface;

    private final ArrayBlockingQueue<byte[]> accessUnitQueue =
            new ArrayBlockingQueue<byte[]>(QUEUE_CAPACITY);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread decoderThread;
    private MediaCodec codec;

    private volatile String lastError = "";

    public H264Decoder(Surface surface, int width, int height) {

        if (surface == null) {
            throw new IllegalArgumentException("Surface cannot be null");
        }

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid video dimensions");
        }

        this.surface = surface;
        this.width = width;
        this.height = height;
    }

    /**
     * Starts the decoder worker.
     */
    public synchronized void start() {

        if (running.get()) {
            return;
        }

        if (!surface.isValid()) {
            throw new IllegalStateException("Decoder surface is not valid");
        }

        running.set(true);
        lastError = "";

        decoderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                decoderLoop();
            }
        }, "P280-H264-Decoder");

        decoderThread.start();
    }

    /**
     * Adds one complete H.264 Annex-B access unit.
     *
     * The caller must provide complete access units in presentation order.
     * This method blocks when the queue is full instead of dropping
     * compressed reference frames.
     */
    public boolean queueAccessUnit(byte[] accessUnit) {

        if (accessUnit == null || accessUnit.length == 0) {
            return false;
        }

        if (!running.get()) {
            return false;
        }

        try {
            while (running.get()) {
                if (accessUnitQueue.offer(accessUnit, 100, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return false;
    }

    /**
     * Decoder worker.
     */
    private void decoderLoop() {

        MediaCodec localCodec = null;

        try {

            MediaFormat format = MediaFormat.createVideoFormat(
                    MIME_TYPE,
                    width,
                    height
            );

            format.setInteger(
                    MediaFormat.KEY_MAX_INPUT_SIZE,
                    width * height
            );

            localCodec = MediaCodec.createDecoderByType(MIME_TYPE);

            localCodec.configure(
                    format,
                    surface,
                    null,
                    0
            );

            localCodec.start();

            synchronized (this) {
                codec = localCodec;
            }

            decodeLoop(localCodec);

        } catch (Exception e) {

            lastError = e.toString();

            Log.e(TAG, "Decoder failed", e);

        } finally {

            if (localCodec != null) {

                try {
                    localCodec.stop();
                } catch (Exception ignored) {
                }

                try {
                    localCodec.release();
                } catch (Exception ignored) {
                }
            }

            synchronized (this) {
                codec = null;
            }

            running.set(false);
            accessUnitQueue.clear();

            Log.i(TAG, "Decoder stopped");
        }
    }

    /**
     * Feeds complete access units into MediaCodec and releases decoded
     * frames to the Surface.
     */
    private void decodeLoop(MediaCodec decoder) {

        MediaCodec.BufferInfo bufferInfo =
                new MediaCodec.BufferInfo();

        byte[] pendingAccessUnit = null;

        long presentationTimeUs = 0;

        while (running.get()) {

            try {

                /*
                 * Keep the same access unit pending until an input buffer
                 * becomes available. Never put it back at the queue tail.
                 */
                if (pendingAccessUnit == null) {
                    pendingAccessUnit = accessUnitQueue.poll(
                            10,
                            TimeUnit.MILLISECONDS
                    );
                }

                if (pendingAccessUnit != null) {

                    int inputIndex = decoder.dequeueInputBuffer(
                            INPUT_TIMEOUT_US
                    );

                    if (inputIndex >= 0) {

                        ByteBuffer[] inputBuffers =
                                decoder.getInputBuffers();

                        ByteBuffer inputBuffer =
                                inputBuffers[inputIndex];

                        inputBuffer.clear();

                        if (pendingAccessUnit.length > inputBuffer.capacity()) {

                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    presentationTimeUs,
                                    0
                            );

                            throw new IllegalArgumentException(
                                    "H.264 access unit exceeds decoder input buffer: "
                                            + pendingAccessUnit.length
                            );
                        }

                        inputBuffer.put(pendingAccessUnit);

                        decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                pendingAccessUnit.length,
                                presentationTimeUs,
                                0
                        );

                        pendingAccessUnit = null;

                        presentationTimeUs += 50000;
                    }
                }

                /*
                 * Drain available decoded output.
                 */
                int outputIndex = decoder.dequeueOutputBuffer(
                        bufferInfo,
                        OUTPUT_TIMEOUT_US
                );

                if (outputIndex >= 0) {

                    decoder.releaseOutputBuffer(
                            outputIndex,
                            true
                    );

                } else if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {

                    Log.d(TAG, "Decoder output buffers changed");

                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    MediaFormat outputFormat =
                            decoder.getOutputFormat();

                    Log.i(TAG, "Output format: " + outputFormat);

                }

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();
                break;

            } catch (Exception e) {

                lastError = e.toString();

                Log.e(TAG, "Decode loop error", e);

                break;
            }
        }
    }

    /**
     * Stops decoding and releases MediaCodec.
     */
    public synchronized void stop() {

        if (!running.get()) {
            return;
        }

        running.set(false);

        if (decoderThread != null) {
            decoderThread.interrupt();
        }

        accessUnitQueue.clear();

        if (decoderThread != null
                && decoderThread != Thread.currentThread()) {

            try {
                decoderThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        decoderThread = null;
    }

    public boolean isRunning() {
        return running.get();
    }

    public String getLastError() {
        return lastError;
    }

    public int getQueuedAccessUnitCount() {
        return accessUnitQueue.size();
    }
}
