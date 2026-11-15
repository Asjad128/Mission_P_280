import ctypes
import socket
import struct
import subprocess
import threading
import time
from ctypes import wintypes

import cv2
import dxcam


# --------------------------------------------------
# CONFIGURATION
# --------------------------------------------------

ADB = r"F:\platform-tools\adb.exe"
PORT = 27183

DEVICE_IDX = 0
OUTPUT_IDX = 3

# Frame size sent to the tablet (always landscape, 5:3 like 800x480).
WIDTH = 200 * 7
HEIGHT = 120 * 7

# ROTATION controls how the captured frame is turned before sending:
#   "ccw"  - always rotate 90 degrees counter-clockwise (verified correct
#            with portrait_test.py: tablet held in portrait shows upright)
#   "cw"   - always rotate 90 degrees clockwise
#   "auto" - rotate ccw only when the captured frame is portrait (h > w);
#            landscape frames are sent unrotated
#   "none" - no rotation (original behaviour)
ROTATION = "ccw"

# FIT_MODE controls what happens when the source aspect ratio differs:
#   "letterbox" - keep proportions, add black bars
#   "stretch"   - fill the frame, distorting (original behaviour)
FIT_MODE = "letterbox"

CAPTURE_FPS = 30
SEND_FPS = 15
JPEG_QUALITY = 25

SEND_BUFFER = 128 * 1024

FRAME_INTERVAL = 1.0 / SEND_FPS


# --------------------------------------------------
# SHARED STATE
# --------------------------------------------------

stop_event = threading.Event()
condition = threading.Condition()
stats_lock = threading.Lock()

latest_raw = None
raw_generation = 0

latest_packet = None
packet_generation = 0

logged_frame_info = False

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


# --------------------------------------------------
# WINDOWS TIMER RESOLUTION
# --------------------------------------------------

winmm = ctypes.WinDLL("winmm", use_last_error=True)

winmm.timeBeginPeriod.argtypes = [wintypes.UINT]
winmm.timeBeginPeriod.restype = wintypes.UINT

winmm.timeEndPeriod.argtypes = [wintypes.UINT]
winmm.timeEndPeriod.restype = wintypes.UINT


def enable_high_resolution_timer():
    result = winmm.timeBeginPeriod(1)

    if result != 0:
        raise RuntimeError(
            f"timeBeginPeriod failed with error: {result}"
        )

    print("Windows timer resolution: 1 ms")
    return True


def disable_high_resolution_timer():
    result = winmm.timeEndPeriod(1)

    if result != 0:
        print(f"Warning: timeEndPeriod returned {result}")


# --------------------------------------------------
# FRAME PREPARATION (FIT + ROTATION)
# --------------------------------------------------

def fit_to_canvas(img, target_w, target_h):
    h, w = img.shape[:2]

    if (w, h) == (target_w, target_h):
        return img

    if FIT_MODE == "stretch":
        return cv2.resize(
            img,
            (target_w, target_h),
            interpolation=cv2.INTER_LINEAR
        )

    scale = min(target_w / w, target_h / h)

    new_w = min(target_w, max(1, round(w * scale)))
    new_h = min(target_h, max(1, round(h * scale)))

    resized = cv2.resize(
        img,
        (new_w, new_h),
        interpolation=cv2.INTER_LINEAR
    )

    top = (target_h - new_h) // 2
    bottom = target_h - new_h - top
    left = (target_w - new_w) // 2
    right = target_w - new_w - left

    return cv2.copyMakeBorder(
        resized,
        top,
        bottom,
        left,
        right,
        cv2.BORDER_CONSTANT,
        value=(0, 0, 0)
    )


def prepare_frame(frame):
    """Fit the captured frame and rotate it into a WIDTH x HEIGHT
    landscape frame for the tablet.

    When rotating, the frame is first fitted into a portrait canvas
    (HEIGHT x WIDTH) and then rotated, so the expensive resize works
    on the source and the rotation works on the small result."""

    src_h, src_w = frame.shape[:2]

    mode = ROTATION

    if mode == "auto":
        mode = "ccw" if src_h > src_w else "none"

    if mode == "none":
        out = fit_to_canvas(frame, WIDTH, HEIGHT)
    else:
        portrait = fit_to_canvas(frame, HEIGHT, WIDTH)

        code = (
            cv2.ROTATE_90_COUNTERCLOCKWISE
            if mode == "ccw"
            else cv2.ROTATE_90_CLOCKWISE
        )

        out = cv2.rotate(portrait, code)

    return out, mode


# --------------------------------------------------
# USB CONNECTION
# --------------------------------------------------

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


# --------------------------------------------------
# CAPTURE WORKER
# --------------------------------------------------

def capture_worker(camera):
    global latest_raw, raw_generation

    try:
        while not stop_event.is_set():

            start = time.perf_counter()

            frame = camera.get_latest_frame()

            wait_ms = (
                time.perf_counter() - start
            ) * 1000

            if frame is None:
                stop_event.wait(0.001)
                continue

            copy_start = time.perf_counter()

            frame = frame.copy()

            copy_ms = (
                time.perf_counter() - copy_start
            ) * 1000

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


# --------------------------------------------------
# ENCODING WORKER
# --------------------------------------------------

def encode_worker():
    global latest_packet, packet_generation, logged_frame_info

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

        dropped = max(
            0,
            generation - last_generation - 1
        )

        last_generation = generation

        try:
            # Fit + rotate
            resize_start = time.perf_counter()

            resized, applied = prepare_frame(frame)

            resize_ms = (
                time.perf_counter() - resize_start
            ) * 1000

            if not logged_frame_info:
                logged_frame_info = True
                src_h, src_w = frame.shape[:2]
                out_h, out_w = resized.shape[:2]
                print(
                    f"Source frame: {src_w}x{src_h} | "
                    f"rotation applied: {applied} | "
                    f"sent frame: {out_w}x{out_h}"
                )

            # JPEG encoding
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

            packet = (
                struct.pack(">I", len(data)) + data
            )

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


# --------------------------------------------------
# TRANSMISSION WORKER
# --------------------------------------------------

def send_worker():
    sock = None
    last_generation = 0

    next_send_time = 0.0

    while not stop_event.is_set():

        # Wait for a new encoded frame.
        with condition:
            while (
                packet_generation == last_generation
                and not stop_event.is_set()
            ):
                condition.wait(timeout=0.5)

            if stop_event.is_set():
                break

        # Enforce transmission interval.
        delay = next_send_time - time.perf_counter()

        if delay > 0:
            stop_event.wait(delay)

        if stop_event.is_set():
            break

        # Get the newest available packet.
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

            send_ms = (
                time.perf_counter() - start
            ) * 1000

            # Schedule the next transmission.
            next_send_time = (
                time.perf_counter() + FRAME_INTERVAL
            )

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


# --------------------------------------------------
# PERFORMANCE MONITOR
# --------------------------------------------------

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
            f"Resize+Rot: {average(values['resize_ms'], values['encoded']):.1f} ms | "
            f"JPEG: {average(values['jpeg_ms'], values['encoded']):.1f} ms | "
            f"Send: {average(values['send_ms'], values['sent']):.1f} ms | "
            f"Size: {average(values['jpeg_bytes'], values['encoded']) / 1024:.1f} KB | "
            f"Dropped: {values['dropped_raw']}"
        )


# --------------------------------------------------
# MAIN
# --------------------------------------------------

def main():
    timer_enabled = False
    camera = None
    threads = []

    try:
        if ROTATION not in ("ccw", "cw", "auto", "none"):
            raise RuntimeError(
                f"Invalid ROTATION '{ROTATION}'. "
                "Use ccw, cw, auto or none."
            )

        if FIT_MODE not in ("letterbox", "stretch"):
            raise RuntimeError(
                f"Invalid FIT_MODE '{FIT_MODE}'. "
                "Use letterbox or stretch."
            )

        timer_enabled = enable_high_resolution_timer()

        cv2.setNumThreads(1)

        setup_usb()

        camera = dxcam.create(
            device_idx=DEVICE_IDX,
            output_idx=OUTPUT_IDX,
            output_color="BGR"
        )

        if camera is None:
            raise RuntimeError(
                "DXcam could not initialize virtual display."
            )

        camera.start(
            target_fps=CAPTURE_FPS,
            video_mode=True
        )

        print("Optimized DXcam + OpenCV pipeline initialized.")
        print(f"Output: {WIDTH}x{HEIGHT}")
        print(f"Rotation: {ROTATION} | Fit: {FIT_MODE}")
        print(f"Capture target: {CAPTURE_FPS} FPS")
        print(f"Transmission target: {SEND_FPS} FPS")
        print(f"JPEG quality: {JPEG_QUALITY}")

        threads = [
            threading.Thread(
                target=capture_worker,
                args=(camera,),
                daemon=True
            ),
            threading.Thread(
                target=encode_worker,
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

        for thread in threads:
            thread.start()

        while not stop_event.wait(0.5):
            if not any(
                thread.is_alive()
                for thread in threads[:3]
            ):
                break

    except KeyboardInterrupt:
        print("\nStopping sender...")

    finally:
        stop_event.set()

        with condition:
            condition.notify_all()

        if camera is not None:
            camera.stop()

        for thread in threads:
            thread.join(timeout=2)

        if timer_enabled:
            disable_high_resolution_timer()

        print("Sender stopped.")


if __name__ == "__main__":
    main()