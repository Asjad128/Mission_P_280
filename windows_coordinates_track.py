
import ctypes
from ctypes import wintypes

user32 = ctypes.WinDLL("user32", use_last_error=True)

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

MONITORENUMPROC = ctypes.WINFUNCTYPE(
    wintypes.BOOL,
    wintypes.HANDLE,
    wintypes.HDC,
    ctypes.POINTER(RECT),
    wintypes.LPARAM
)

def callback(hMonitor, hdc, rect, data):
    info = MONITORINFOEX()
    info.cbSize = ctypes.sizeof(info)

    if user32.GetMonitorInfoW(hMonitor, ctypes.byref(info)):
        r = info.rcMonitor
        print(
            f"{info.szDevice}: "
            f"{r.right-r.left}x{r.bottom-r.top} "
            f"| Left={r.left}, Top={r.top}, "
            f"Right={r.right}, Bottom={r.bottom} "
            f"| Primary={bool(info.dwFlags & 1)}"
        )

    return True

user32.EnumDisplayMonitors(
    None,
    None,
    MONITORENUMPROC(callback),
    0
)
