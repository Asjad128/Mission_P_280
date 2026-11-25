"""
P280 virtual monitor -> tablet sender (video out, touch in).

Pipeline : DXcam capture -> fit/rotate -> JPEG -> TCP (through adb forward)
Touch in : tablet sends 16-byte TCH1 packets on the same socket -> gestures -> SendInput
"""

import argparse
import ctypes
import os
import re
import shutil
import socket
import struct
import subprocess
import threading
import time
from ctypes import wintypes

import cv2
import dxcam


# --------------------------------------------------
# DPI AWARENESS (must happen before any monitor / metric query)
# --------------------------------------------------
# Without this, GetMonitorInfo / GetSystemMetrics return "virtualized" values on
# scaled displays and the injected mouse position lands in the wrong place.

def enable_dpi_awareness():
    try:
        if ctypes.windll.user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4)):
            return
    except (AttributeError, OSError):
        pass
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
        return
    except (AttributeError, OSError):
        pass
    try:
        ctypes.windll.user32.SetProcessDPIAware()
    except (AttributeError, OSError):
        pass


enable_dpi_awareness()


# --------------------------------------------------
# CONFIGURATION
# --------------------------------------------------

ADB = r"F:\platform-tools\adb.exe"      # falls back to "adb" on PATH if missing
PORT = 27183

# None = auto-detect the P280 virtual monitor by resolution. Override with --device / --output.
DEVICE_IDX = None
OUTPUT_IDX = None

# Frame sent to the tablet (always landscape, matches the tablet screen).
WIDTH = 800
HEIGHT = 480

# "auto": rotate ccw only when the captured frame is portrait. Also: "ccw", "cw", "none".
ROTATION = "auto"

# "letterbox" keeps proportions (black bars), "stretch" fills the frame.
FIT_MODE = "letterbox"

CAPTURE_FPS = 30
SEND_FPS = 20
JPEG_QUALITY = 50
SEND_BUFFER = 128 * 1024
FRAME_INTERVAL = 1.0 / SEND_FPS

# Touch behaviour
LONG_PRESS_SECONDS = 0.65       # hold still this long -> right click
DRAG_THRESHOLD_PX = 12          # monitor pixels of travel before a tap becomes a drag
DOUBLE_TAP_SECONDS = 0.45       # two taps this close in time ...
DOUBLE_TAP_SLOP_PX = 24         # ... and this close in space are snapped to one point
SCROLL_PIXELS_PER_NOTCH = 50.0  # two-finger travel per wheel notch
NATURAL_SCROLL = True           # True: content follows the fingers (phone style)

DEBUG_TOUCH = False


# --------------------------------------------------
# TOUCH PACKET PROTOCOL
# --------------------------------------------------

# TCH1 | action:u8 | pointer_id:u8 | reserved:u16 | x:f32 | y:f32   (big-endian, 16 bytes)
TOUCH_PACKET = struct.Struct("!4sBBHff")

TOUCH_HELLO, TOUCH_DOWN, TOUCH_MOVE, TOUCH_UP, TOUCH_CANCEL = range(5)
TOUCH_NAMES = {0: "HELLO", 1: "DOWN", 2: "MOVE", 3: "UP", 4: "CANCEL"}


# --------------------------------------------------
# GEOMETRY: fit + rotate for sending, and the exact inverse for touch
# --------------------------------------------------
# GEOMETRY-BEGIN

def resolve_rotation(src_w, src_h):
    if ROTATION == "auto":
        return "ccw" if src_h > src_w else "none"
    return ROTATION


def fit_to_canvas(img, target_w, target_h):
    h, w = img.shape[:2]

    if (w, h) == (target_w, target_h):
        return img

    if FIT_MODE == "stretch":
        return cv2.resize(img, (target_w, target_h), interpolation=cv2.INTER_LINEAR)

    scale = min(target_w / w, target_h / h)
    new_w = min(target_w, max(1, round(w * scale)))
    new_h = min(target_h, max(1, round(h * scale)))

    resized = cv2.resize(img, (new_w, new_h), interpolation=cv2.INTER_LINEAR)

    top = (target_h - new_h) // 2
    bottom = target_h - new_h - top
    left = (target_w - new_w) // 2
    right = target_w - new_w - left

    return cv2.copyMakeBorder(
        resized, top, bottom, left, right,
        cv2.BORDER_CONSTANT, value=(0, 0, 0)
    )


def prepare_frame(frame):
    """Fit the captured frame and rotate it into a WIDTH x HEIGHT landscape frame.

    When rotating, the source is fitted into a portrait canvas (HEIGHT x WIDTH)
    first and then rotated, so the resize works on the source."""
    src_h, src_w = frame.shape[:2]
    mode = resolve_rotation(src_w, src_h)

    if mode == "none":
        return fit_to_canvas(frame, WIDTH, HEIGHT), mode

    portrait = fit_to_canvas(frame, HEIGHT, WIDTH)
    code = (cv2.ROTATE_90_COUNTERCLOCKWISE if mode == "ccw"
            else cv2.ROTATE_90_CLOCKWISE)
    return cv2.rotate(portrait, code), mode


def tablet_to_source(nx, ny, src_w, src_h):
    """Map a normalized tablet touch (0..1 over the WIDTH x HEIGHT frame) back to a
    normalized position (u, v) on the captured monitor. Undoes rotation AND letterbox.
    Returns None if the touch is on a black bar."""
    mode = resolve_rotation(src_w, src_h)
    fx, fy = nx * WIDTH, ny * HEIGHT

    if mode == "none":
        canvas_w, canvas_h = WIDTH, HEIGHT
        cx, cy = fx, fy
    elif mode == "ccw":
        canvas_w, canvas_h = HEIGHT, WIDTH
        cx, cy = HEIGHT - fy, fx
    else:  # cw
        canvas_w, canvas_h = HEIGHT, WIDTH
        cx, cy = fy, WIDTH - fx

    if FIT_MODE == "letterbox":
        scale = min(canvas_w / src_w, canvas_h / src_h)
        content_w = src_w * scale
        content_h = src_h * scale
        pad_x = (canvas_w - content_w) / 2.0
        pad_y = (canvas_h - content_h) / 2.0
        u = (cx - pad_x) / content_w
        v = (cy - pad_y) / content_h
        tolerance = 0.01
        if not (-tolerance <= u <= 1 + tolerance and -tolerance <= v <= 1 + tolerance):
            return None
    else:
        u = cx / canvas_w
        v = cy / canvas_h

    return min(1.0, max(0.0, u)), min(1.0, max(0.0, v))

# GEOMETRY-END


# --------------------------------------------------
# WINDOWS MOUSE INJECTION
# --------------------------------------------------

user32 = ctypes.WinDLL("user32", use_last_error=True)

INPUT_MOUSE = 0
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_VIRTUALDESK = 0x4000
MOUSEEVENTF_ABSOLUTE = 0x8000

SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN = 76, 77
SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN = 78, 79

ULONG_PTR = wintypes.WPARAM


class RECT(ctypes.Structure):
    _fields_ = [("left", wintypes.LONG), ("top", wintypes.LONG),
                ("right", wintypes.LONG), ("bottom", wintypes.LONG)]


class MONITORINFOEX(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", RECT), ("rcWork", RECT),
                ("dwFlags", wintypes.DWORD), ("szDevice", wintypes.WCHAR * 32)]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG),
                ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class INPUTUNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", wintypes.DWORD), ("u", INPUTUNION)]


MONITORENUMPROC = ctypes.WINFUNCTYPE(
    wintypes.BOOL, wintypes.HANDLE, wintypes.HANDLE,
    ctypes.POINTER(RECT), wintypes.LPARAM
)

user32.SendInput.argtypes = [wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int]
user32.SendInput.restype = wintypes.UINT
user32.GetSystemMetrics.argtypes = [ctypes.c_int]
user32.GetSystemMetrics.restype = ctypes.c_int
user32.GetMonitorInfoW.argtypes = [wintypes.HANDLE, ctypes.POINTER(MONITORINFOEX)]
user32.GetMonitorInfoW.restype = wintypes.BOOL
user32.EnumDisplayMonitors.argtypes = [
    wintypes.HANDLE, ctypes.c_void_p, MONITORENUMPROC, wintypes.LPARAM
]
user32.EnumDisplayMonitors.restype = wintypes.BOOL


def find_monitor_by_device_name(device_name):
    """Return (left, top, width, height) of the monitor with this \\\\.\\DISPLAYn name."""
    found = []

    def callback(hmonitor, hdc, rect, data):
        info = MONITORINFOEX()
        info.cbSize = ctypes.sizeof(info)
        if user32.GetMonitorInfoW(hmonitor, ctypes.byref(info)):
            if info.szDevice.casefold() == device_name.casefold():
                r = info.rcMonitor
                found.append((r.left, r.top, r.right - r.left, r.bottom - r.top))
        return True

    user32.EnumDisplayMonitors(None, None, MONITORENUMPROC(callback), 0)

    if len(found) != 1:
        raise RuntimeError(
            f"Could not uniquely identify Windows monitor '{device_name}'. Found: {found}"
        )
    return found[0]


class WindowsMouse:

    def __init__(self, camera, device_idx, output_idx):
        dxgi_output = getattr(camera, "_output", None)
        if dxgi_output is None:
            raise RuntimeError("Could not access the selected DXcam output.")

        device_name = getattr(dxgi_output, "devicename", None)
        if not device_name:
            desc = getattr(dxgi_output, "desc", None)
            device_name = getattr(desc, "DeviceName", None) if desc is not None else None
        if not device_name:
            raise RuntimeError(
                "DXcam did not expose the selected monitor's Windows device name."
            )

        (self.monitor_left, self.monitor_top,
         self.monitor_width, self.monitor_height) = find_monitor_by_device_name(device_name)

        self.virtual_left = user32.GetSystemMetrics(SM_XVIRTUALSCREEN)
        self.virtual_top = user32.GetSystemMetrics(SM_YVIRTUALSCREEN)
        self.virtual_width = user32.GetSystemMetrics(SM_CXVIRTUALSCREEN)
        self.virtual_height = user32.GetSystemMetrics(SM_CYVIRTUALSCREEN)

        # Size of the captured frame (decides rotation / letterbox). Starts as the
        # monitor size and is replaced by the real frame size once known.
        self.src_w = self.monitor_width
        self.src_h = self.monitor_height

        print(f"Mouse target: Device {device_idx}, Output {output_idx} -> {device_name} | "
              f"{self.monitor_width}x{self.monitor_height} at "
              f"({self.monitor_left}, {self.monitor_top})")

    def set_source_size(self, width, height):
        self.src_w, self.src_h = width, height

    def map_frame_coordinates(self, nx, ny):
        """Normalized tablet coordinates -> absolute desktop pixel, or None on a black bar."""
        uv = tablet_to_source(nx, ny, self.src_w, self.src_h)
        if uv is None:
            return None
        u, v = uv
        # width-1 / height-1: the last valid pixel, never the neighbouring monitor.
        return (self.monitor_left + u * (self.monitor_width - 1),
                self.monitor_top + v * (self.monitor_height - 1))

    def _send(self, dx, dy, data, flags):
        event = INPUT(type=INPUT_MOUSE,
                      mi=MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0))
        if user32.SendInput(1, ctypes.byref(event), ctypes.sizeof(INPUT)) != 1:
            raise ctypes.WinError(ctypes.get_last_error())

    def move_to(self, screen_x, screen_y):
        ax = round((screen_x - self.virtual_left) * 65535 / max(1, self.virtual_width - 1))
        ay = round((screen_y - self.virtual_top) * 65535 / max(1, self.virtual_height - 1))
        ax = min(65535, max(0, ax))
        ay = min(65535, max(0, ay))
        self._send(ax, ay, 0,
                   MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK)

    def left_down(self):
        self._send(0, 0, 0, MOUSEEVENTF_LEFTDOWN)

    def left_up(self):
        self._send(0, 0, 0, MOUSEEVENTF_LEFTUP)

    def left_click(self):
        self.left_down()
        self.left_up()

    def right_click(self):
        self._send(0, 0, 0, MOUSEEVENTF_RIGHTDOWN)
        self._send(0, 0, 0, MOUSEEVENTF_RIGHTUP)

    def scroll(self, delta):
        if delta:
            self._send(0, 0, delta, MOUSEEVENTF_WHEEL)


# --------------------------------------------------
# TOUCH GESTURES
# --------------------------------------------------
#   1 finger, tap            -> left click (double tap = double click)
#   1 finger, move           -> left-button drag
#   1 finger, hold still     -> right click
#   2+ fingers, move up/down -> mouse wheel

def _distance(a, b):
    return ((a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2) ** 0.5


class TouchGestureHandler:

    def __init__(self, mouse):
        self.mouse = mouse
        self.lock = threading.RLock()
        self.long_press_timer = None
        self.last_tap_time = 0.0
        self.last_tap_pos = None
        self.reset()

    # ---- state ----

    def reset(self):
        self.cancel_long_press()
        self.active = {}              # pointer_id -> (nx, ny)
        self.mode = "idle"            # idle | single | ignored | multi
        self.primary_id = None
        self.start = None             # desktop position where the finger landed
        self.last = None
        self.dragging = False
        self.long_press_fired = False
        self.multi_last = None
        self.scroll_remainder = 0.0

    def cancel_long_press(self):
        if self.long_press_timer is not None:
            self.long_press_timer.cancel()
            self.long_press_timer = None

    def start_long_press(self):
        self.cancel_long_press()
        self.long_press_timer = threading.Timer(LONG_PRESS_SECONDS, self.fire_long_press)
        self.long_press_timer.daemon = True
        self.long_press_timer.start()

    def fire_long_press(self):
        with self.lock:
            if (self.mode != "single" or self.primary_id not in self.active
                    or self.dragging or self.long_press_fired or self.start is None):
                return
            self.mouse.move_to(*self.start)
            self.mouse.right_click()
            self.long_press_fired = True
            print("Gesture: Right click")

    def snap_to_previous_tap(self, pos):
        """Make the two taps of a double-tap land on exactly the same pixel."""
        if (self.last_tap_pos is not None
                and time.monotonic() - self.last_tap_time < DOUBLE_TAP_SECONDS
                and _distance(pos, self.last_tap_pos) <= DOUBLE_TAP_SLOP_PX):
            return self.last_tap_pos
        return pos

    def centroid(self):
        points = [p for p in (self.mouse.map_frame_coordinates(nx, ny)
                              for nx, ny in self.active.values()) if p is not None]
        if len(points) < 2:
            return None
        return (sum(p[0] for p in points) / len(points),
                sum(p[1] for p in points) / len(points))

    # ---- events ----

    def handle_down(self, pointer_id, nx, ny):
        self.active[pointer_id] = (nx, ny)

        if len(self.active) == 1:
            self.primary_id = pointer_id
            self.dragging = False
            self.long_press_fired = False
            mapped = self.mouse.map_frame_coordinates(nx, ny)
            if mapped is None:                   # landed on a letterbox bar
                self.mode = "ignored"
                return
            self.mode = "single"
            self.start = self.last = self.snap_to_previous_tap(mapped)
            self.mouse.move_to(*self.start)
            self.start_long_press()
            return

        # Second finger: switch to scrolling.
        self.cancel_long_press()
        if self.dragging:
            self.mouse.left_up()
        self.dragging = False
        self.mode = "multi"
        self.primary_id = None
        self.multi_last = self.centroid()
        self.scroll_remainder = 0.0
        if self.multi_last is not None:
            self.mouse.move_to(*self.multi_last)   # wheel acts under the cursor
        print("Gesture: Scroll")

    def handle_move(self, pointer_id, nx, ny):
        if pointer_id not in self.active:
            return
        self.active[pointer_id] = (nx, ny)

        if self.mode == "single" and pointer_id == self.primary_id:
            mapped = self.mouse.map_frame_coordinates(nx, ny)
            if mapped is None:
                return
            self.last = mapped

            if not self.dragging:
                if self.long_press_fired:
                    return                        # after a right click, ignore wobble
                if _distance(mapped, self.start) < DRAG_THRESHOLD_PX:
                    return                        # keep the cursor still so taps stay accurate
                self.cancel_long_press()
                self.mouse.move_to(*self.start)
                self.mouse.left_down()
                self.dragging = True
                print("Gesture: Drag")

            self.mouse.move_to(*mapped)

        elif self.mode == "multi":
            centroid = self.centroid()
            if centroid is None:
                self.multi_last = None
                return
            if self.multi_last is not None:
                delta_y = centroid[1] - self.multi_last[1]
                direction = 1.0 if NATURAL_SCROLL else -1.0
                self.scroll_remainder += direction * delta_y * 120.0 / SCROLL_PIXELS_PER_NOTCH
                whole = int(self.scroll_remainder)
                if whole:
                    self.mouse.scroll(whole)
                    self.scroll_remainder -= whole
            self.multi_last = centroid

    def handle_up(self, pointer_id, nx, ny):
        if pointer_id not in self.active:
            return

        if self.mode == "single" and pointer_id == self.primary_id:
            self.cancel_long_press()
            if self.dragging:
                self.mouse.left_up()
                print("Gesture: Drag end")
            elif not self.long_press_fired:
                self.mouse.move_to(*self.start)
                self.mouse.left_click()
                self.last_tap_time = time.monotonic()
                self.last_tap_pos = self.start
                print("Gesture: Click")

        self.active.pop(pointer_id, None)
        if not self.active:
            self.reset()

    def handle_cancel(self):
        with self.lock:
            if self.dragging:
                self.mouse.left_up()
            self.reset()
        print("Gesture: Cancelled")

    def handle(self, action, pointer_id, nx, ny):
        with self.lock:
            if action == TOUCH_DOWN:
                self.handle_down(pointer_id, nx, ny)
            elif action == TOUCH_MOVE:
                self.handle_move(pointer_id, nx, ny)
            elif action == TOUCH_UP:
                self.handle_up(pointer_id, nx, ny)
            elif action == TOUCH_CANCEL:
                self.handle_cancel()


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
    "captured": 0, "encoded": 0, "sent": 0,
    "capture_wait_ms": 0.0, "copy_ms": 0.0, "resize_ms": 0.0,
    "jpeg_ms": 0.0, "send_ms": 0.0, "jpeg_bytes": 0, "dropped_raw": 0,
}

mouse_controller = None
gesture_handler = None


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
        raise RuntimeError(f"timeBeginPeriod failed with error: {result}")
    print("Windows timer resolution: 1 ms")
    return True


def disable_high_resolution_timer():
    result = winmm.timeEndPeriod(1)
    if result != 0:
        print(f"Warning: timeEndPeriod returned {result}")


# --------------------------------------------------
# USB / ADB
# --------------------------------------------------

def resolve_adb():
    if os.path.isfile(ADB):
        return ADB
    return shutil.which("adb") or ADB


def setup_usb():
    adb = resolve_adb()

    result = subprocess.run([adb, "get-state"], capture_output=True, text=True)
    if result.stdout.strip() != "device":
        raise RuntimeError("P280 not detected by ADB.")

    subprocess.run([adb, "forward", "--remove", f"tcp:{PORT}"], capture_output=True)
    subprocess.run([adb, "forward", f"tcp:{PORT}", f"tcp:{PORT}"], check=True)
    print("USB forwarding ready.")


def connect_receiver():
    while not stop_event.is_set():
        try:
            sock = socket.create_connection(("127.0.0.1", PORT), timeout=5)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, SEND_BUFFER)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.settimeout(2)
            print("Android receiver connected.")
            return sock
        except OSError as error:
            print("Waiting for Android receiver:", error)
            stop_event.wait(2)
    return None


# --------------------------------------------------
# CAPTURE
# --------------------------------------------------

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


# --------------------------------------------------
# ENCODING
# --------------------------------------------------

def encode_worker():
    global latest_packet, packet_generation, logged_frame_info

    last_generation = 0

    while not stop_event.is_set():
        with condition:
            while raw_generation == last_generation and not stop_event.is_set():
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
            resized, applied = prepare_frame(frame)
            resize_ms = (time.perf_counter() - resize_start) * 1000

            # Tell the touch mapper the real captured size (decides rotation/letterbox).
            src_h, src_w = frame.shape[:2]
            if mouse_controller is not None:
                mouse_controller.set_source_size(src_w, src_h)

            if not logged_frame_info:
                logged_frame_info = True
                out_h, out_w = resized.shape[:2]
                print(f"Source frame: {src_w}x{src_h} | rotation applied: {applied} | "
                      f"sent frame: {out_w}x{out_h}")

            jpeg_start = time.perf_counter()
            success, encoded = cv2.imencode(
                ".jpg", resized,
                [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY, cv2.IMWRITE_JPEG_OPTIMIZE, 0]
            )
            jpeg_ms = (time.perf_counter() - jpeg_start) * 1000

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


# --------------------------------------------------
# TOUCH RECEIVER
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

    return bytes(data) if len(data) == size else None


def touch_reader_worker(sock):
    print("Touch reader started.")

    try:
        while not stop_event.is_set():
            packet = recv_exact(sock, TOUCH_PACKET.size)
            if packet is None:
                break

            magic, action, pointer_id, _reserved, x, y = TOUCH_PACKET.unpack(packet)

            if magic != b"TCH1":
                # Stream is out of sync; drop the connection so the sender reconnects.
                print("Invalid touch packet, resetting connection:", magic)
                sock.close()
                break

            if action == TOUCH_HELLO:
                print("Touch channel ready: Android handshake received.")
                continue

            if gesture_handler is not None:
                gesture_handler.handle(action, pointer_id, x, y)

            if DEBUG_TOUCH and action != TOUCH_MOVE:
                print(f"Touch RX | {TOUCH_NAMES.get(action, action)} | "
                      f"Pointer {pointer_id} | X {x:.4f} | Y {y:.4f}")

    except OSError as error:
        if not stop_event.is_set():
            print("Touch reader error:", error)

    finally:
        if gesture_handler is not None:
            gesture_handler.handle_cancel()
        print("Touch reader stopped.")


# --------------------------------------------------
# TRANSMISSION
# --------------------------------------------------

def send_worker():
    sock = None
    last_generation = 0
    next_send_time = 0.0

    while not stop_event.is_set():
        with condition:
            while packet_generation == last_generation and not stop_event.is_set():
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
            threading.Thread(target=touch_reader_worker, args=(sock,),
                             daemon=True, name="TouchReader").start()

        try:
            start = time.perf_counter()
            sock.sendall(packet)
            send_ms = (time.perf_counter() - start) * 1000
            next_send_time = time.perf_counter() + FRAME_INTERVAL

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
# STATS
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

        def avg(total, count):
            return total / count if count else 0

        print(f"Capture: {values['captured'] / elapsed:.1f} FPS | "
              f"Encode: {values['encoded'] / elapsed:.1f} FPS | "
              f"TX: {values['sent'] / elapsed:.1f} FPS | "
              f"Wait: {avg(values['capture_wait_ms'], values['captured']):.1f} ms | "
              f"Copy: {avg(values['copy_ms'], values['captured']):.1f} ms | "
              f"Resize+Rot: {avg(values['resize_ms'], values['encoded']):.1f} ms | "
              f"JPEG: {avg(values['jpeg_ms'], values['encoded']):.1f} ms | "
              f"Send: {avg(values['send_ms'], values['sent']):.1f} ms | "
              f"Size: {avg(values['jpeg_bytes'], values['encoded']) / 1024:.1f} KB | "
              f"Dropped: {values['dropped_raw']}")


# --------------------------------------------------
# OUTPUT DETECTION
# --------------------------------------------------

P280_MODES = {(1280, 768), (480, 800), (800, 480), (768, 1280)}

OUTPUT_PATTERN = re.compile(
    r"Device\[(\d+)\]\s*Output\[(\d+)\]\s*:\s*Res\s*:\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)"
)


def list_outputs():
    info = dxcam.output_info()
    rows = [tuple(int(v) for v in m.groups()) for m in OUTPUT_PATTERN.finditer(info)]
    return info, rows


def find_p280_output():
    _info, rows = list_outputs()
    for device, output, width, height in rows:
        if (width, height) in P280_MODES:
            return device, output, (width, height)
    return None


def resolve_output_selection():
    info, rows = list_outputs()

    if DEVICE_IDX is None and OUTPUT_IDX is None:
        found = find_p280_output()
        if found is None:
            raise RuntimeError("No P280-compatible DXGI output found.\n" + info)
        return found

    candidates = rows
    if DEVICE_IDX is not None:
        candidates = [r for r in candidates if r[0] == DEVICE_IDX]
    if OUTPUT_IDX is not None:
        candidates = [r for r in candidates if r[1] == OUTPUT_IDX]
    elif DEVICE_IDX is not None:
        candidates = [r for r in candidates if (r[2], r[3]) in P280_MODES]

    if len(candidates) != 1:
        raise RuntimeError(
            "Could not uniquely resolve the DXcam output.\n"
            f"DEVICE_IDX: {DEVICE_IDX}\nOUTPUT_IDX: {OUTPUT_IDX}\n"
            f"Candidates: {candidates}\n\n"
            "Specify both --device and --output if necessary.\n\n"
            f"Available outputs:\n{info}"
        )

    device, output, width, height = candidates[0]
    return device, output, (width, height)


def parse_args():
    parser = argparse.ArgumentParser(
        description="Send the P280 virtual monitor to the tablet over USB.")
    parser.add_argument("--list", action="store_true",
                        help="print the DXGI outputs DXcam can see and exit")
    parser.add_argument("--device", type=int, help="DXGI adapter index")
    parser.add_argument("--output", type=int, help="DXGI output index")
    parser.add_argument("--rotation", choices=["auto", "ccw", "cw", "none"],
                        help="override ROTATION")
    parser.add_argument("--quality", type=int, help="override JPEG quality")
    parser.add_argument("--adb", help="path to adb.exe")
    parser.add_argument("--debug-touch", action="store_true",
                        help="print every touch packet received")
    return parser.parse_args()


# --------------------------------------------------
# MAIN
# --------------------------------------------------

def main():
    global ROTATION, JPEG_QUALITY, DEVICE_IDX, OUTPUT_IDX, ADB, DEBUG_TOUCH
    global mouse_controller, gesture_handler

    args = parse_args()

    if args.list:
        info, _rows = list_outputs()
        print(info)
        print("P280 candidate:", find_p280_output())
        return

    if args.rotation:
        ROTATION = args.rotation
    if args.quality is not None:
        JPEG_QUALITY = args.quality
    if args.device is not None:
        DEVICE_IDX = args.device
    if args.output is not None:
        OUTPUT_IDX = args.output
    if args.adb:
        ADB = args.adb
    if args.debug_touch:
        DEBUG_TOUCH = True

    timer_enabled = False
    camera = None
    threads = []

    try:
        if ROTATION not in ("ccw", "cw", "auto", "none"):
            raise RuntimeError(f"Invalid ROTATION '{ROTATION}'.")
        if FIT_MODE not in ("letterbox", "stretch"):
            raise RuntimeError(f"Invalid FIT_MODE '{FIT_MODE}'.")
        if not 1 <= JPEG_QUALITY <= 100:
            raise RuntimeError("JPEG_QUALITY must be between 1 and 100.")

        timer_enabled = enable_high_resolution_timer()
        cv2.setNumThreads(1)
        setup_usb()

        device, output, resolution = resolve_output_selection()
        print(f"Selected DXGI output: Device {device}, Output {output}, "
              f"{resolution[0]}x{resolution[1]}")

        camera = dxcam.create(device_idx=device, output_idx=output, output_color="BGR")
        if camera is None:
            raise RuntimeError("DXcam could not initialize the selected virtual display.")

        # Mouse is bound to the same output that is being captured.
        mouse_controller = WindowsMouse(camera, device, output)
        gesture_handler = TouchGestureHandler(mouse_controller)

        camera.start(target_fps=CAPTURE_FPS, video_mode=True)

        print(f"Output: {WIDTH}x{HEIGHT} | Rotation: {ROTATION} | Fit: {FIT_MODE}")
        print(f"Capture {CAPTURE_FPS} FPS | Send {SEND_FPS} FPS | JPEG quality {JPEG_QUALITY}")
        print("Touch gestures: enabled")

        threads = [
            threading.Thread(target=capture_worker, args=(camera,), daemon=True, name="CaptureWorker"),
            threading.Thread(target=encode_worker, daemon=True, name="EncodeWorker"),
            threading.Thread(target=send_worker, daemon=True, name="SendWorker"),
            threading.Thread(target=report_stats, daemon=True, name="StatsWorker"),
        ]
        for thread in threads:
            thread.start()

        while not stop_event.wait(0.5):
            if not any(t.is_alive() for t in threads[:3]):
                break

    except KeyboardInterrupt:
        print("\nStopping sender...")

    finally:
        stop_event.set()
        with condition:
            condition.notify_all()

        if gesture_handler is not None:
            try:
                gesture_handler.handle_cancel()
            except Exception as error:
                print("Touch cleanup warning:", error)

        if camera is not None:
            try:
                camera.stop()
            except Exception as error:
                print("DXcam cleanup warning:", error)

        for thread in threads:
            thread.join(timeout=2)

        if timer_enabled:
            disable_high_resolution_timer()

        print("Sender stopped.")


if __name__ == "__main__":
    main()