
import socket
import struct
import subprocess
import threading
import time

import cv2
import dxcam

ADB = r"F:\platform-tools\adb.exe"
PORT = 27183

DEVICE_IDX = 0
OUTPUT_IDX = 3

WIDTH = 480
HEIGHT = 270
CAPTURE_FPS = 30
SEND_FPS = 15
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
    "capture_wait_ms": 0.0,
    "copy_ms": 0.0,
    "resize_ms": 0.0,
    "jpeg_ms": 0.0,
    "send_ms": 0.0,
    "jpeg_bytes": 0,
    "dropped_raw": 0,
}


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

            wait_ms = (time.perf_counter() - start) * 1000

            if frame is None:
                stop_event.wait(0.001)
                continue

            copy_start = time.perf_counter()
            frame = frame.copy()
            copy_ms = (time.perf_counter() - copy_start) * 1000

            with condition:
                latest_raw = frame
                raw_generation += 1
                condition.notify_all()

            with stats_lock:
                stats["captured"] += 1
                stats["capture_wait_ms"] += wait_ms
                stats["copy_ms"] += copy_ms

    except Exception as error:
        print("DXcam capture error:", error)
        stop_event.set()

    finally:
        with condition:
            condition.notify_all()


def encode_worker():
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
            generation = raw_generation

        if frame is None:
            continue

        dropped = max(0, generation - last_generation - 1)
        last_generation = generation

        try:
            resize_start = time.perf_counter()

            resized = cv2.resize(
                frame,
                (WIDTH, HEIGHT),
                interpolation=cv2.INTER_LINEAR
            )

            resize_ms = (
                time.perf_counter() - resize_start
            ) * 1000

            jpeg_start = time.perf_counter()

            success, encoded = cv2.imencode(
                ".jpg",
                resized,
                [
                    cv2.IMWRITE_JPEG_QUALITY,
                    JPEG_QUALITY,
                    cv2.IMWRITE_JPEG_OPTIMIZE,
                    0,
                ]
            )

            jpeg_ms = (
                time.perf_counter() - jpeg_start
            ) * 1000

            if not success:
                continue

            data = encoded.tobytes()
            packet = struct.pack(">I", len(data)) + data

            with condition:
                latest_packet = packet
                packet_generation += 1
                condition.notify_all()

            with stats_lock:
                stats["encoded"] += 1
                stats["resize_ms"] += resize_ms
                stats["jpeg_ms"] += jpeg_ms
                stats["jpeg_bytes"] += len(data)
                stats["dropped_raw"] += dropped

        except Exception as error:
            print("Encoding error:", error)


def send_worker():
    sock = None
    last_generation = 0
    next_send_time = 0.0
    frame_interval = 1.0 / SEND_FPS

    while not stop_event.is_set():
        with condition:
            while (
                packet_generation == last_generation
                and not stop_event.is_set()
            ):
                condition.wait(timeout=0.5)

            if stop_event.is_set():
                break

        delay = next_send_time - time.perf_counter()

        if delay > 0:
            stop_event.wait(delay)

        if stop_event.is_set():
            break

        with condition:
            if packet_generation == last_generation:
                continue

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
            send_ms = (time.perf_counter() - start) * 1000

            next_send_time = time.perf_counter() + frame_interval

            with stats_lock:
                stats["sent"] += 1
                stats["send_ms"] += send_ms

        except OSError as error:
            print("Transmission interrupted:", error)

            sock.close()
            sock = None
            stop_event.wait(0.5)

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
            f"Capture: {values['captured'] / elapsed:.1f} FPS | "
            f"Encode: {values['encoded'] / elapsed:.1f} FPS | "
            f"TX: {values['sent'] / elapsed:.1f} FPS | "
            f"Wait: {average(values['capture_wait_ms'], values['captured']):.1f} ms | "
            f"Copy: {average(values['copy_ms'], values['captured']):.1f} ms | "
            f"Resize: {average(values['resize_ms'], values['encoded']):.1f} ms | "
            f"JPEG: {average(values['jpeg_ms'], values['encoded']):.1f} ms | "
            f"Send: {average(values['send_ms'], values['sent']):.1f} ms | "
            f"Size: {average(values['jpeg_bytes'], values['encoded']) / 1024:.1f} KB | "
            f"Dropped: {values['dropped_raw']}"
        )


def main():
    cv2.setNumThreads(1)

    setup_usb()

    camera = dxcam.create(
        device_idx=DEVICE_IDX,
        output_idx=OUTPUT_IDX,
        output_color="BGR"
    )

    if camera is None:
        raise RuntimeError("DXcam could not initialize virtual display.")

    camera.start(
        target_fps=CAPTURE_FPS,
        video_mode=True
    )

    print("Optimized DXcam + OpenCV pipeline initialized.")
    print(f"Output: {WIDTH}x{HEIGHT}")
    print(f"Capture target: {CAPTURE_FPS} FPS")
    print(f"Transmission target: {SEND_FPS} FPS")
    print(f"JPEG quality: {JPEG_QUALITY}")
    print("Native cursor capture: enabled.")

    threads = [
        threading.Thread(target=capture_worker, args=(camera,), daemon=True),
        threading.Thread(target=encode_worker, daemon=True),
        threading.Thread(target=send_worker, daemon=True),
        threading.Thread(target=report_stats, daemon=True),
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
