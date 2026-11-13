
import ctypes
import socket
import struct
import subprocess
import threading
import time
from ctypes import wintypes

import dxcam
import mss
from PIL import Image, ImageDraw

ADB = r"F:\platform-tools\adb.exe"
PORT = 27183

DEVICE_IDX = 0
OUTPUT_IDX = 3

WIDTH = 640
HEIGHT = 360
FPS = 20
CAPTURE_FPS = 30
JPEG_QUALITY = 25
SEND_BUFFER = 128 * 1024

stop_event = threading.Event()
condition = threading.Condition()
stats_lock = threading.Lock()

latest_raw = None
raw_generation = 0

latest_packet = None
packet_generation = 0

stats = {
    "captured": 0,
    "encoded": 0,
    "sent": 0,
    "capture_ms": 0.0,
    "encode_ms": 0.0,
    "send_ms": 0.0,
    "jpeg_bytes": 0,
}


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
        if info.flags & 1:
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

    outer = [
        (x, y), (x, y + 19), (x + 5, y + 14),
        (x + 9, y + 23), (x + 13, y + 21),
        (x + 9, y + 12), (x + 16, y + 12),
    ]

    inner = [
        (x + 2, y + 3), (x + 2, y + 15),
        (x + 6, y + 12), (x + 10, y + 20),
        (x + 11, y + 19), (x + 7, y + 10),
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

            sock.settimeout(2)

            print("Android receiver connected.")
            return sock

        except OSError as error:
            print("Waiting for Android receiver:", error)
            stop_event.wait(2)

    return None


def capture_worker(camera):
    global latest_raw, raw_generation

    try:
        while not stop_event.is_set():
            start = time.perf_counter()

            frame = camera.get_latest_frame()

            if frame is None:
                continue

            # Copy because DXcam may reuse its internal frame buffer.
            frame = frame.copy()

            elapsed = (time.perf_counter() - start) * 1000

            with condition:
                latest_raw = frame
                raw_generation += 1
                condition.notify_all()

            with stats_lock:
                stats["captured"] += 1
                stats["capture_ms"] += elapsed

    except Exception as error:
        print("DXcam capture error:", error)
        stop_event.set()

    finally:
        with condition:
            condition.notify_all()


def encode_worker(monitor):
    global latest_packet, packet_generation

    last_generation = 0

    while not stop_event.is_set():

        with condition:
            while (
                raw_generation == last_generation
                and not stop_event.is_set()
            ):
                condition.wait(timeout=0.5)

            if stop_event.is_set():
                break

            frame = latest_raw
            last_generation = raw_generation

        if frame is None:
            continue

        try:
            start = time.perf_counter()

            image = Image.fromarray(frame)

            if image.size != (WIDTH, HEIGHT):
                image = image.resize(
                    (WIDTH, HEIGHT),
                    Image.Resampling.BILINEAR
                )

            draw_cursor(image, monitor)

            from io import BytesIO
            buffer = BytesIO()

            image.save(
                buffer,
                format="JPEG",
                quality=JPEG_QUALITY,
                optimize=False
            )

            data = buffer.getvalue()
            packet = struct.pack(">I", len(data)) + data

            elapsed = (time.perf_counter() - start) * 1000

            with condition:
                latest_packet = packet
                packet_generation += 1
                condition.notify_all()

            with stats_lock:
                stats["encoded"] += 1
                stats["encode_ms"] += elapsed
                stats["jpeg_bytes"] += len(data)

        except Exception as error:
            print("Encoding error:", error)


def send_worker():
    global packet_generation

    sock = None
    last_generation = 0

    try:
        while not stop_event.is_set():

            with condition:
                while (
                    packet_generation == last_generation
                    and not stop_event.is_set()
                ):
                    condition.wait(timeout=0.5)

                if stop_event.is_set():
                    break

                packet = latest_packet
                last_generation = packet_generation

            if packet is None:
                continue

            if sock is None:
                sock = connect_receiver()

                if sock is None:
                    break

            try:
                start = time.perf_counter()
                sock.sendall(packet)
                elapsed = time.perf_counter() - start

                with stats_lock:
                    stats["sent"] += 1
                    stats["send_ms"] += elapsed

            except OSError as error:
                print("Transmission interrupted:", error)

                sock.close()
                sock = None
                stop_event.wait(0.5)

    finally:
        if sock is not None:
            sock.close()


def report_stats():
    previous = time.perf_counter()

    while not stop_event.wait(2):
        now = time.perf_counter()
        elapsed = now - previous
        previous = now

        with stats_lock:
            values = stats.copy()

            for key in stats:
                stats[key] = 0

        def average(total, count):
            return total / count if count else 0

        print(
            f"Capture FPS: {values['captured'] / elapsed:.1f} | "
            f"Encode FPS: {values['encoded'] / elapsed:.1f} | "
            f"TX FPS: {values['sent'] / elapsed:.1f} | "
            f"Capture: {average(values['capture_ms'], values['captured']):.1f} ms | "
            f"Encode: {average(values['encode_ms'], values['encoded']):.1f} ms | "
            f"Send: {average(values['send_ms'], values['sent']) * 1000:.1f} ms | "
            f"JPEG: {average(values['jpeg_bytes'], values['encoded']) / 1024:.1f} KB"
        )


def main():
    global stop_event

    setup_usb()

    # MSS is used only to obtain the virtual monitor's screen coordinates.
    with mss.MSS() as screen:
        monitor_index = OUTPUT_IDX + 1

        if monitor_index >= len(screen.monitors):
            raise RuntimeError("Virtual monitor coordinates not found.")

        monitor = screen.monitors[monitor_index].copy()

    camera = dxcam.create(
        device_idx=DEVICE_IDX,
        output_idx=OUTPUT_IDX,
        output_color="RGB"
    )

    if camera is None:
        raise RuntimeError("DXcam could not initialize virtual display.")

    camera.start(
        target_fps=CAPTURE_FPS,
        video_mode=True
    )

    print("DXcam virtual display initialized.")
    print(f"Source: {monitor['width']}x{monitor['height']}")
    print(f"Output: {WIDTH}x{HEIGHT}")
    print(f"Capture target: {CAPTURE_FPS} FPS")
    print(f"Transmission target: {FPS} FPS")
    print("Three-stage pipeline active.")

    threads = [
        threading.Thread(
            target=capture_worker,
            args=(camera,),
            daemon=True
        ),
        threading.Thread(
            target=encode_worker,
            args=(monitor,),
            daemon=True
        ),
        threading.Thread(
            target=send_worker,
            daemon=True
        ),
        threading.Thread(
            target=report_stats,
            daemon=True
        ),
    ]

    try:
        for thread in threads:
            thread.start()

        while not stop_event.wait(0.5):
            if not any(thread.is_alive() for thread in threads[:3]):
                break

    except KeyboardInterrupt:
        print("\nStopping sender...")

    finally:
        stop_event.set()

        with condition:
            condition.notify_all()

        camera.stop()

        for thread in threads:
            thread.join(timeout=2)

        print("Sender stopped.")


if __name__ == "__main__":
    main()
