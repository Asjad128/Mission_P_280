
import argparse
import ctypes
import re
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

# Which DXGI output to capture. None = auto-detect the P280 virtual monitor by its
# resolution (1280x768, 800x480, 480x800 or 768x1280). Override with --device / --output.
DEVICE_IDX = None
OUTPUT_IDX = None

# Frame size sent to the tablet (always landscape). 800x480 matches the tablet's screen
# and the proven mirroring POC. The previous values were 1400x840 at JPEG quality 25.
WIDTH = 100 * 8
HEIGHT = 60 * 8

# ROTATION controls how the captured frame is turned before sending:
#   "ccw"  - always rotate 90 degrees counter-clockwise (verified correct
#            with portrait_test.py: tablet held in portrait shows upright)
#   "cw"   - always rotate 90 degrees clockwise
#   "auto" - rotate ccw only when the captured frame is portrait (h > w);
#            landscape frames are sent unrotated
#   "none" - no rotation (original behaviour)
ROTATION = "auto"

# FIT_MODE controls what happens when the source aspect ratio differs:
#   "letterbox" - keep proportions, add black bars
#   "stretch"   - fill the frame, distorting (original behaviour)
FIT_MODE = "letterbox"

CAPTURE_FPS = 30
SEND_FPS = 20
JPEG_QUALITY = 50

SEND_BUFFER = 128 * 1024

FRAME_INTERVAL = 1.0 / SEND_FPS

# --------------------------------------------------
# TOUCH INPUT PROTOCOL
# --------------------------------------------------

# TCH1 | action:u8 | pointer_id:u8 | reserved:u16 | x:f32 | y:f32
TOUCH_PACKET = struct.Struct("!4sBBHff")

TOUCH_ACTIONS = {
    0: "HELLO",
    1: "DOWN",
    2: "MOVE",
    3: "UP",
    4: "CANCEL",
}


# --------------------------------------------------
# WINDOWS MOUSE MOVEMENT
# --------------------------------------------------

user32 = ctypes.WinDLL("user32", use_last_error=True)

INPUT_MOUSE = 0

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_ABSOLUTE = 0x8000
MOUSEEVENTF_VIRTUALDESK = 0x4000

ULONG_PTR = wintypes.WPARAM


class RECT(ctypes.Structure):
    _fields_ = [
        ("left", wintypes.LONG),
        ("top", wintypes.LONG),
        ("right", wintypes.LONG),
        ("bottom", wintypes.LONG),
    ]


class MONITORINFOEX(ctypes.Structure):
    _fields_ = [
        ("cbSize", wintypes.DWORD),
        ("rcMonitor", RECT),
        ("rcWork", RECT),
        ("dwFlags", wintypes.DWORD),
        ("szDevice", wintypes.WCHAR * 32),
    ]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", wintypes.LONG),
        ("dy", wintypes.LONG),
        ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class INPUTUNION(ctypes.Union):
    _fields_ = [
        ("mi", MOUSEINPUT),
    ]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [
        ("type", wintypes.DWORD),
        ("u", INPUTUNION),
    ]


user32.SendInput.argtypes = [
    wintypes.UINT,
    ctypes.POINTER(INPUT),
    ctypes.c_int,
]
user32.SendInput.restype = wintypes.UINT

user32.GetSystemMetrics.argtypes = [ctypes.c_int]
user32.GetSystemMetrics.restype = ctypes.c_int



def find_monitor_by_device_name(device_name):
    monitors = []

    MONITORENUMPROC = ctypes.WINFUNCTYPE(
        wintypes.BOOL,
        wintypes.HANDLE,
        wintypes.HDC,
        ctypes.POINTER(RECT),
        wintypes.LPARAM
    )

    def callback(hmonitor, hdc, rect, data):
        info = MONITORINFOEX()
        info.cbSize = ctypes.sizeof(info)

        if user32.GetMonitorInfoW(
            hmonitor,
            ctypes.byref(info)
        ):
            if info.szDevice.casefold() == device_name.casefold():
                r = info.rcMonitor

                monitors.append((
                    r.left,
                    r.top,
                    r.right - r.left,
                    r.bottom - r.top
                ))

        return True

    callback_ref = MONITORENUMPROC(callback)

    user32.EnumDisplayMonitors(
        None,
        None,
        callback_ref,
        0
    )

    if len(monitors) != 1:
        raise RuntimeError(
            f"Could not uniquely identify Windows monitor "
            f"'{device_name}'. Found: {monitors}"
        )

    return monitors[0]

class WindowsMouse:

    def __init__(self, camera, device_idx, output_idx):

        # Obtain the exact DXGI output selected by DXcam.
        dxgi_output = getattr(camera, "_output", None)

        if dxgi_output is None:
            raise RuntimeError(
                "Could not access the selected DXcam output."
            )

        # DXcam output descriptor contains the Windows device name.
        device_name = getattr(
            dxgi_output,
            "devicename",
            None
        )

        # Compatibility fallback for output descriptor layouts.
        if not device_name:
            desc = getattr(dxgi_output, "desc", None)

            if desc is not None:
                device_name = getattr(
                    desc,
                    "DeviceName",
                    None
                )

        if not device_name:
            raise RuntimeError(
                "DXcam did not expose the selected monitor's "
                "Windows device name. Check DXcam compatibility."
            )

        self.monitor_device_name = device_name

        (
            self.monitor_left,
            self.monitor_top,
            self.monitor_width,
            self.monitor_height
        ) = find_monitor_by_device_name(device_name)

        self.virtual_left = user32.GetSystemMetrics(76)
        self.virtual_top = user32.GetSystemMetrics(77)
        self.virtual_width = user32.GetSystemMetrics(78)
        self.virtual_height = user32.GetSystemMetrics(79)

        print(
            f"Mouse target: Device {device_idx}, "
            f"Output {output_idx} -> {device_name} | "
            f"{self.monitor_width}x{self.monitor_height} "
            f"at ({self.monitor_left}, {self.monitor_top})"
        )

    def map_frame_coordinates(self, nx, ny):

        # Convert normalized coordinates into frame pixels.
        frame_x = nx * WIDTH
        frame_y = ny * HEIGHT

        if FIT_MODE == "letterbox":

            scale = min(
                WIDTH / self.monitor_width,
                HEIGHT / self.monitor_height
            )

            content_width = self.monitor_width * scale
            content_height = self.monitor_height * scale

            pad_x = (WIDTH - content_width) / 2.0
            pad_y = (HEIGHT - content_height) / 2.0

            # Ignore touches on the sender's black bars.
            if (
                frame_x < pad_x
                or frame_x > pad_x + content_width
                or frame_y < pad_y
                or frame_y > pad_y + content_height
            ):
                return None

            source_x = (
                (frame_x - pad_x) / content_width
            ) * self.monitor_width

            source_y = (
                (frame_y - pad_y) / content_height
            ) * self.monitor_height

        else:
            source_x = nx * self.monitor_width
            source_y = ny * self.monitor_height

        screen_x = self.monitor_left + source_x
        screen_y = self.monitor_top + source_y

        return screen_x, screen_y

    def move_to(self, screen_x, screen_y):

        absolute_x = round(
            (screen_x - self.virtual_left)
            * 65535 / max(1, self.virtual_width - 1)
        )

        absolute_y = round(
            (screen_y - self.virtual_top)
            * 65535 / max(1, self.virtual_height - 1)
        )

        mouse_input = MOUSEINPUT(
            absolute_x,
            absolute_y,
            0,
            (
                MOUSEEVENTF_MOVE
                | MOUSEEVENTF_ABSOLUTE
                | MOUSEEVENTF_VIRTUALDESK
            ),
            0,
            0
        )

        input_event = INPUT(
            type=INPUT_MOUSE,
            mi=mouse_input
        )

        sent = user32.SendInput(
            1,
            ctypes.byref(input_event),
            ctypes.sizeof(INPUT)
        )

        if sent != 1:
            raise ctypes.WinError(ctypes.get_last_error())


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


mouse_controller = None

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
# TOUCH INPUT RECEIVER
# --------------------------------------------------

def recv_exact(sock, size):
    data = bytearray()

    while len(data) < size and not stop_event.is_set():
        try:
            chunk = sock.recv(size - len(data))

        except socket.timeout:
            continue

        if not chunk:
            return None

        data.extend(chunk)

    if len(data) != size:
        return None

    return bytes(data)



def touch_reader_worker(sock):
    print("Touch reader started.")

    try:
        while not stop_event.is_set():

            packet = recv_exact(sock, TOUCH_PACKET.size)

            if packet is None:
                break

            magic, action, pointer_id, reserved, x, y = (
                TOUCH_PACKET.unpack(packet)
            )

            if magic != b"TCH1":
                print("Invalid touch packet:", magic)
                continue

            if action == 0:
                print("Touch channel ready: Android handshake received.")
                continue

            action_name = TOUCH_ACTIONS.get(
                action,
                f"UNKNOWN({action})"
            )

            # Step 3A: only the first finger controls the cursor.
            if pointer_id == 0 and action in (1, 2):

                mapped = mouse_controller.map_frame_coordinates(
                    x,
                    y
                )

                if mapped is not None:
                    mouse_controller.move_to(
                        mapped[0],
                        mapped[1]
                    )

            print(
                f"Touch RX | Action: {action_name}"
                f" | Pointer: {pointer_id}"
                f" | X: {x:.4f}"
                f" | Y: {y:.4f}"
            )

    except OSError as error:
        if not stop_event.is_set():
            print("Touch reader error:", error)

    finally:
        print("Touch reader stopped.")

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

            threading.Thread(
                target=touch_reader_worker,
                args=(sock,),
                daemon=True,
                name="TouchReader"
            ).start()

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
# OUTPUT DETECTION
# --------------------------------------------------

P280_MODES = {(1280, 768), (480, 800), (800, 480), (768, 1280)}

OUTPUT_PATTERN = re.compile(
    r"Device\[(\d+)\]\s*Output\[(\d+)\]\s*:\s*Res\s*:\s*"
    r"\(\s*(\d+)\s*,\s*(\d+)\s*\)"
)


def list_outputs(info=None):
    if info is None:
        info = dxcam.output_info()

    rows = [
        tuple(int(value) for value in match.groups())
        for match in OUTPUT_PATTERN.finditer(info)
    ]

    return info, rows


def find_p280_output():
    info, rows = list_outputs()

    for device, output, width, height in rows:
        if (width, height) in P280_MODES:
            return device, output, (width, height)

    return None

def resolve_output_selection():

    info, rows = list_outputs()

    # Automatic P280 detection when no indices are configured.
    if DEVICE_IDX is None and OUTPUT_IDX is None:

        found = find_p280_output()

        if found is None:
            raise RuntimeError(
                "No P280-compatible DXGI output found.\n"
                + info
            )

        return found

    candidates = rows

    if DEVICE_IDX is not None:
        candidates = [
            row for row in candidates
            if row[0] == DEVICE_IDX
        ]

    if OUTPUT_IDX is not None:
        candidates = [
            row for row in candidates
            if row[1] == OUTPUT_IDX
        ]

    elif DEVICE_IDX is not None:
        # If only the adapter is specified, locate its P280 output.
        candidates = [
            row for row in candidates
            if (row[2], row[3]) in P280_MODES
        ]

    if len(candidates) != 1:
        raise RuntimeError(
            "Could not uniquely resolve the DXcam output.\n"
            f"DEVICE_IDX: {DEVICE_IDX}\n"
            f"OUTPUT_IDX: {OUTPUT_IDX}\n"
            f"Candidates: {candidates}\n\n"
            "Specify both --device and --output if necessary.\n\n"
            f"Available outputs:\n{info}"
        )

    device, output, width, height = candidates[0]

    return device, output, (width, height)


def parse_args():
    parser = argparse.ArgumentParser(
        description="Send the P280 virtual monitor to the tablet over USB."
    )
    parser.add_argument("--list", action="store_true",
                        help="print the DXGI outputs DXcam can see and exit")
    parser.add_argument("--device", type=int, help="DXGI adapter index")
    parser.add_argument("--output", type=int, help="DXGI output index")
    parser.add_argument("--rotation",
                        choices=["auto", "ccw", "cw", "none"],
                        help="override ROTATION")
    parser.add_argument("--quality", type=int, help="override JPEG quality")
    return parser.parse_args()


# --------------------------------------------------
# MAIN
# --------------------------------------------------

def main():
    global ROTATION, JPEG_QUALITY
    global DEVICE_IDX, OUTPUT_IDX, mouse_controller

    args = parse_args()

    if args.list:
        info, rows = list_outputs()
        print(info)
        print("P280 candidate:", find_p280_output())
        return

    if args.rotation:
        ROTATION = args.rotation
    if args.quality:
        JPEG_QUALITY = args.quality
    if args.device is not None:
        DEVICE_IDX = args.device
    if args.output is not None:
        OUTPUT_IDX = args.output

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

        # Resolve the exact DXcam adapter/output pair.
        device, output, resolution = resolve_output_selection()

        print(
            f"Selected DXGI output: "
            f"Device {device}, Output {output}, "
            f"{resolution[0]}x{resolution[1]}"
        )

        # Initialize DXcam first.
        camera = dxcam.create(
            device_idx=device,
            output_idx=output,
            output_color="BGR"
        )

        if camera is None:
            raise RuntimeError(
                "DXcam could not initialize virtual display."
            )

        # Bind mouse input to the exact output selected by DXcam.
        mouse_controller = WindowsMouse(
            camera,
            device,
            output
        )

        if OUTPUT_IDX is None:
            found = find_p280_output()

            if found is None:
                info, rows = list_outputs()
                print(info)
                raise RuntimeError(
                    "No output with a P280 resolution "
                    f"{sorted(P280_MODES)} was found. Is the driver "
                    "installed and the monitor extended? Pick an output "
                    "manually with --output N."
                )

            device, output, resolution = found
            print(
                f"Auto-detected P280 virtual monitor: device {device}, "
                f"output {output}, {resolution[0]}x{resolution[1]}"
            )
        else:
            device = DEVICE_IDX if DEVICE_IDX is not None else 0
            output = OUTPUT_IDX
            print(f"Using manual selection: device {device}, output {output}")

        camera = dxcam.create(
            device_idx=device,
            output_idx=output,
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