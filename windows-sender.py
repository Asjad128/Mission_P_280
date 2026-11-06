
import socket
import time
from io import BytesIO
from PIL import Image, ImageGrab

HOST = "127.0.0.1"
PORT = 27183

# Micromax P280 physical resolution
WIDTH = 800
HEIGHT = 480

FPS = 10
JPEG_QUALITY = 55


def capture_frame():
    screenshot = ImageGrab.grab()

    screenshot = screenshot.resize(
        (WIDTH, HEIGHT),
        Image.Resampling.LANCZOS
    )

    buffer = BytesIO()

    screenshot.save(
        buffer,
        format="JPEG",
        quality=JPEG_QUALITY,
        optimize=True
    )

    return buffer.getvalue()


def send_frame(sock, jpeg_data):
    if len(jpeg_data) > 4 * 1024 * 1024:
        raise ValueError("Frame exceeds 4 MB limit")

    # Four-byte big-endian frame length
    header = len(jpeg_data).to_bytes(4, byteorder="big")

    sock.sendall(header + jpeg_data)


def main():
    print("Connecting to Micromax P280...")

    try:
        with socket.create_connection((HOST, PORT), timeout=10) as sock:
            sock.settimeout(None)

            print("Connected to tablet!")
            print("Streaming desktop. Press Ctrl+C to stop.")

            frame_interval = 1.0 / FPS
            frame_count = 0

            while True:
                start = time.perf_counter()

                jpeg_data = capture_frame()
                send_frame(sock, jpeg_data)

                frame_count += 1

                if frame_count % FPS == 0:
                    print(
                        f"Frames: {frame_count} | "
                        f"JPEG size: {len(jpeg_data) / 1024:.1f} KB"
                    )

                elapsed = time.perf_counter() - start
                time.sleep(max(0, frame_interval - elapsed))

    except ConnectionRefusedError:
        print("Connection refused. Check ADB forwarding and tablet receiver.")

    except KeyboardInterrupt:
        print("\nStreaming stopped.")

    except Exception as error:
        print(f"Sender error: {error}")


if __name__ == "__main__":
    main()
