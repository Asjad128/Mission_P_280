package com.voidcrew.micromaxdisplay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

/**
 * Receives length-prefixed JPEG frames over the local TCP port used by ADB forwarding.
 */
public final class UsbReceiver {
    public static final int PORT = 27183;
    private static final int MAX_FRAME_SIZE = 4 * 1024 * 1024;
    private static final int HEADER_SIZE = 4;

    public interface Listener {
        void onStatus(String status);
        void onFrame(Bitmap frame);
        void onError(String message, Exception exception);
    }

    private final Listener listener;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private Thread receiverThread;

    public UsbReceiver(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        receiverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runServer();
            }
        }, "UsbReceiver");
        receiverThread.start();
    }

    public void stop() {
        running = false;
        closeClient();
        closeServer();
        Thread thread;
        synchronized (this) {
            thread = receiverThread;
            receiverThread = null;
        }
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
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
                    listener.onStatus("Windows connected");
                    receiveFrames(clientSocket);
                } catch (SocketException exception) {
                    if (running) {
                        listener.onError("Receiver socket error", exception);
                    }
                } catch (IOException exception) {
                    if (running) {
                        listener.onError("Connection error", exception);
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
                listener.onError("Unable to listen on port " + PORT, exception);
            }
        } finally {
            closeClient();
            closeServer();
            if (running) {
                running = false;
            }
        }
    }

    private void receiveFrames(Socket socket) throws IOException {
        InputStream input = new BufferedInputStream(socket.getInputStream());
        byte[] header = new byte[HEADER_SIZE];
        while (running) {
            if (!readFully(input, header, 0, HEADER_SIZE)) {
                return;
            }
            int frameSize = ((header[0] & 0xff) << 24)
                    | ((header[1] & 0xff) << 16)
                    | ((header[2] & 0xff) << 8)
                    | (header[3] & 0xff);
            if (frameSize <= 0 || frameSize > MAX_FRAME_SIZE) {
                throw new IOException("Invalid JPEG frame size: " + frameSize);
            }

            byte[] jpeg = new byte[frameSize];
            if (!readFully(input, jpeg, 0, frameSize)) {
                return;
            }
            Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (frame == null) {
                listener.onError("Received malformed JPEG frame", null);
                continue;
            }
            listener.onStatus("Receiving frames");
            listener.onFrame(frame);
        }
    }

    private static boolean readFully(InputStream input, byte[] buffer, int offset, int length)
            throws IOException {
        int remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, offset + (length - remaining), remaining);
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
