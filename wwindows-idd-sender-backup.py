
import socket
import struct
import subprocess
import time
from io import BytesIO

import mss
from PIL import Image

ADB = r"F:\platform-tools\adb.exe"
MONITOR_INDEX = 4
PORT = 27183

WIDTH = 850
HEIGHT = 480
FPS = 10
JPEG_QUALITY = 35
SEND_BUFFER = 128 * 1024


def setup_usb():
    result = subprocess.run(
        [ADB, "get-state"],
        capture_output=True,
        text=True
    )

    if result.stdout.strip() != "device":
        raise RuntimeError("P280 not detected by ADB.")

    subprocess.run(
        [ADB, "forward", "--remove", f"tcp:{PORT}"],
        capture_output=True
    )

    subprocess.run(
        [ADB, "forward", f"tcp:{PORT}", f"tcp:{PORT}"],
        check=True
    )

    print("USB forwarding ready.")


def connect_receiver():
    while True:
        try:
            sock = socket.create_connection(
                ("127.0.0.1", PORT),
                timeout=5
            )
            sock.setsockopt(
                socket.SOL_SOCKET,
                socket.SO_SNDBUF,
                SEND_BUFFER
            )
            sock.setsockopt(
                socket.IPPROTO_TCP,
                socket.TCP_NODELAY,
                1
            )
            sock.settimeout(3)

            print("Android receiver connected.")
            return sock

        except OSError as error:
            print("Waiting for receiver:", error)
            time.sleep(2)


def main():
    setup_usb()

    with mss.MSS() as capture:
        if MONITOR_INDEX >= len(capture.monitors):
            raise RuntimeError("Virtual display not found.")

        monitor = capture.monitors[MONITOR_INDEX]

        print(
            f"Capture: {monitor['width']}x{monitor['height']}"
        )
        print(f"Output: {WIDTH}x{HEIGHT}")
        print(f"FPS target: {FPS}, JPEG quality: {JPEG_QUALITY}")

        sock = connect_receiver()

        frames = 0
        window_start = time.perf_counter()

        capture_total = 0
        encode_total = 0
        send_total = 0

        try:
            while True:
                frame_start = time.perf_counter()

                try:
                    t = time.perf_counter()
                    shot = capture.grab(monitor)
                    capture_ms = (time.perf_counter() - t) * 1000

                    t = time.perf_counter()

                    image = Image.frombytes(
                        "RGB", shot.size, shot.rgb
                    )

                    if image.size != (WIDTH, HEIGHT):
                        image = image.resize(
                            (WIDTH, HEIGHT),
                            Image.Resampling.BILINEAR
                        )

                    buffer = BytesIO()
                    image.save(
                        buffer,
                        format="JPEG",
                        quality=JPEG_QUALITY,
                        optimize=False
                    )

                    data = buffer.getvalue()
                    packet = struct.pack(">I", len(data)) + data

                    encode_ms = (time.perf_counter() - t) * 1000

                    t = time.perf_counter()
                    sock.sendall(packet)
                    send_ms = (time.perf_counter() - t) * 1000

                    frames += 1
                    capture_total += capture_ms
                    encode_total += encode_ms
                    send_total += send_ms

                    elapsed = time.perf_counter() - window_start

                    if elapsed >= 2:
                        print(
                            f"FPS: {frames / elapsed:.1f} | "
                            f"Capture: {capture_total / frames:.1f} ms | "
                            f"Encode: {encode_total / frames:.1f} ms | "
                            f"Send: {send_total / frames:.1f} ms | "
                            f"JPEG: {len(data) / 1024:.1f} KB"
                        )

                        frames = 0
                        capture_total = 0
                        encode_total = 0
                        send_total = 0
                        window_start = time.perf_counter()

                    remaining = (1 / FPS) - (
                        time.perf_counter() - frame_start
                    )

                    if remaining > 0:
                        time.sleep(remaining)

                except (OSError, ConnectionError) as error:
                    print("Connection interrupted:", error)
                    sock.close()
                    time.sleep(1)
                    sock = connect_receiver()

        except KeyboardInterrupt:
            print("\nSender stopped.")

        finally:
            sock.close()


if __name__ == "__main__":
    main()
