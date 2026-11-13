
import ctypes
import socket
import struct
import subprocess
import threading
import time
from ctypes import wintypes
from io import BytesIO

import mss
from PIL import Image, ImageDraw

ADB = r"F:\platform-tools\adb.exe"
MONITOR_INDEX = 4
PORT = 27183

WIDTH = 640
HEIGHT = 360
FPS = 10
JPEG_QUALITY = 45
SEND_BUFFER = 128 * 1024

stop_event = threading.Event()
frame_condition = threading.Condition()

latest_packet = None
frame_generation = 0

stats_lock = threading.Lock()
sent_frames = 0
send_time_total = 0.0


# Windows cursor position API
class POINT(ctypes.Structure):
    _fields_ = [
        ("x", wintypes.LONG),
        ("y", wintypes.LONG),
    ]


class CURSORINFO(ctypes.Structure):
    _fields_ = [
        ("cbSize", wintypes.DWORD),
        ("flags", wintypes.DWORD),
        ("hCursor", wintypes.HANDLE),
        ("ptScreenPos", POINT),
    ]


user32 = ctypes.WinDLL("user32", use_last_error=True)
user32.GetCursorInfo.argtypes = [ctypes.POINTER(CURSORINFO)]
user32.GetCursorInfo.restype = wintypes.BOOL


def get_cursor_position():
    info = CURSORINFO()
    info.cbSize = ctypes.sizeof(CURSORINFO)

    if user32.GetCursorInfo(ctypes.byref(info)):
        if info.flags & 1:  # CURSOR_SHOWING
            return info.ptScreenPos.x, info.ptScreenPos.y

    return None


def draw_cursor(image, monitor):
    position = get_cursor_position()

    if position is None:
        return

    cursor_x, cursor_y = position

    relative_x = cursor_x - monitor["left"]
    relative_y = cursor_y - monitor["top"]

    if not (
        0 <= relative_x < monitor["width"]
        and 0 <= relative_y < monitor["height"]
    ):
        return

    x = int(relative_x * WIDTH / monitor["width"])
    y = int(relative_y * HEIGHT / monitor["height"])

    # White arrow with black outline.
    outer = [
        (x, y),
        (x, y + 19),
        (x + 5, y + 14),
        (x + 9, y + 23),
        (x + 13, y + 21),
        (x + 9, y + 12),
        (x + 16, y + 12),
    ]

    inner = [
        (x + 2, y + 3),
        (x + 2, y + 15),
        (x + 6, y + 12),
        (x + 10, y + 20),
        (x + 11, y + 19),
        (x + 7, y + 10),
        (x + 13, y + 10),
    ]

    draw = ImageDraw.Draw(image)
    draw.polygon(outer, fill="black")
    draw.polygon(inner, fill="white")


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
    while not stop_event.is_set():
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

            sock.settimeout(1)

            print("Android receiver connected.")
            return sock

        except OSError as error:
            print("Waiting for receiver:", error)
            stop_event.wait(2)

    return None


def capture_worker():
    global latest_packet, frame_generation

    try:
        with mss.MSS() as capture:
            if MONITOR_INDEX >= len(capture.monitors):
                raise RuntimeError("Virtual display not found.")

            monitor = capture.monitors[MONITOR_INDEX]

            print(
                f"Capture: {monitor['width']}x{monitor['height']}"
            )
            print(f"Output: {WIDTH}x{HEIGHT}")
            print(f"Target FPS: {FPS}, JPEG quality: {JPEG_QUALITY}")
            print("Cursor overlay enabled.")
            print("Latest-frame-only transmission enabled.")

            frames = 0
            capture_total = 0.0
            encode_total = 0.0
            jpeg_total = 0
            window_start = time.perf_counter()

            while not stop_event.is_set():
                frame_start = time.perf_counter()

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

                draw_cursor(image, monitor)

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

                # Replace any unsent frame with this newest frame.
                with frame_condition:
                    latest_packet = packet
                    frame_generation += 1
                    frame_condition.notify()

                frames += 1
                capture_total += capture_ms
                encode_total += encode_ms
                jpeg_total += len(data)

                elapsed = time.perf_counter() - window_start

                if elapsed >= 2:
                    with stats_lock:
                        tx_frames = sent_frames
                        tx_time = send_time_total

                        globals()["sent_frames"] = 0
                        globals()["send_time_total"] = 0.0

                    avg_send = (
                        tx_time / tx_frames * 1000
                        if tx_frames else 0
                    )

                    print(
                        f"Capture FPS: {frames / elapsed:.1f} | "
                        f"TX FPS: {tx_frames / elapsed:.1f} | "
                        f"Capture: {capture_total / frames:.1f} ms | "
                        f"Encode: {encode_total / frames:.1f} ms | "
                        f"Send: {avg_send:.1f} ms | "
                        f"JPEG: {jpeg_total / frames / 1024:.1f} KB"
                    )

                    frames = 0
                    capture_total = 0.0
                    encode_total = 0.0
                    jpeg_total = 0
                    window_start = time.perf_counter()

                remaining = (
                    1 / FPS
                    - (time.perf_counter() - frame_start)
                )

                if remaining > 0:
                    stop_event.wait(remaining)

    except Exception as error:
        print("Capture worker stopped:", error)
        stop_event.set()

    finally:
        with frame_condition:
            frame_condition.notify_all()


def send_worker():
    global sent_frames, send_time_total

    last_generation = 0
    sock = None

    try:
        while not stop_event.is_set():

            # Wait for a newer frame.
            with frame_condition:
                while (
                    frame_generation == last_generation
                    and not stop_event.is_set()
                ):
                    frame_condition.wait(timeout=0.5)

                if stop_event.is_set():
                    break

                packet = latest_packet
                last_generation = frame_generation

            if packet is None:
                continue

            if sock is None:
                sock = connect_receiver()

                if sock is None:
                    break

            try:
                t = time.perf_counter()
                sock.sendall(packet)
                send_ms = time.perf_counter() - t

                with stats_lock:
                    sent_frames += 1
                    send_time_total += send_ms

            except OSError as error:
                print("Transmission interrupted:", error)

                sock.close()
                sock = None

                # Reconnect; the next transmission uses the newest frame.
                stop_event.wait(0.5)

    finally:
        if sock is not None:
            sock.close()


def main():
    setup_usb()

    capture_thread = threading.Thread(
        target=capture_worker,
        daemon=True
    )

    sender_thread = threading.Thread(
        target=send_worker,
        daemon=True
    )

    capture_thread.start()
    sender_thread.start()

    try:
        while (
            capture_thread.is_alive()
            and sender_thread.is_alive()
            and not stop_event.is_set()
        ):
            time.sleep(0.2)

    except KeyboardInterrupt:
        print("\nStopping sender...")

    finally:
        stop_event.set()

        with frame_condition:
            frame_condition.notify_all()

        capture_thread.join(timeout=3)
        sender_thread.join(timeout=3)

        print("Sender stopped.")


if __name__ == "__main__":
    main()
