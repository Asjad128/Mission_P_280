
import socket
import subprocess
import threading
import time
import queue
import tkinter as tk
from tkinter import ttk, messagebox
from io import BytesIO
from PIL import Image, ImageGrab


# Configuration
ADB = r"F:\platform-tools\adb.exe"
HOST = "127.0.0.1"
PORT = 27183

WIDTH = 800
HEIGHT = 480


class MicromaxDisplayApp:

    def __init__(self, root):
        self.root = root
        self.root.title("Micromax USB Display")
        self.root.geometry("480x680")
        self.root.resizable(False, False)

        self.events = queue.Queue()
        self.stop_event = threading.Event()
        self.worker = None

        self.fps_var = tk.StringVar(value="15")
        self.quality_var = tk.StringVar(value="55")

        self.status_var = tk.StringVar(value="Disconnected")
        self.frames_var = tk.StringVar(value="0")
        self.speed_var = tk.StringVar(value="0 KB/s")
        self.actual_fps_var = tk.StringVar(value="0 FPS")

        self.build_ui()

        self.root.after(100, self.process_events)
        self.root.protocol("WM_DELETE_WINDOW", self.close_app)

    def build_ui(self):

        main = ttk.Frame(self.root, padding=20)
        main.pack(fill="both", expand=True)

        ttk.Label(
            main,
            text="MICROMAX USB DISPLAY",
            font=("Segoe UI", 17, "bold")
        ).pack(pady=(0, 5))

        ttk.Label(
            main,
            text="USB Desktop Streaming",
            foreground="gray"
        ).pack(pady=(0, 20))

        status_frame = ttk.LabelFrame(
            main,
            text="Connection",
            padding=12
        )
        status_frame.pack(fill="x", pady=5)

        self.status_label = ttk.Label(
            status_frame,
            textvariable=self.status_var,
            font=("Segoe UI", 11, "bold")
        )
        self.status_label.pack(anchor="w")

        settings = ttk.LabelFrame(
            main,
            text="Streaming Settings",
            padding=12
        )
        settings.pack(fill="x", pady=12)

        row1 = ttk.Frame(settings)
        row1.pack(fill="x", pady=5)

        ttk.Label(row1, text="FPS:").pack(side="left")

        ttk.Entry(
            row1,
            textvariable=self.fps_var,
            width=8
        ).pack(side="right")

        row2 = ttk.Frame(settings)
        row2.pack(fill="x", pady=5)

        ttk.Label(row2, text="JPEG Quality:").pack(side="left")

        ttk.Entry(
            row2,
            textvariable=self.quality_var,
            width=8
        ).pack(side="right")

        ttk.Label(
            settings,
            text="Resolution: 800 x 480",
            foreground="gray"
        ).pack(anchor="w", pady=(8, 0))

        stats = ttk.LabelFrame(
            main,
            text="Live Statistics",
            padding=12
        )
        stats.pack(fill="x", pady=5)

        ttk.Label(
            stats,
            text="Frames sent:"
        ).grid(row=0, column=0, sticky="w", padx=5, pady=4)

        ttk.Label(
            stats,
            textvariable=self.frames_var
        ).grid(row=0, column=1, sticky="e", padx=5)

        ttk.Label(
            stats,
            text="Streaming FPS:"
        ).grid(row=1, column=0, sticky="w", padx=5, pady=4)

        ttk.Label(
            stats,
            textvariable=self.actual_fps_var
        ).grid(row=1, column=1, sticky="e", padx=5)

        ttk.Label(
            stats,
            text="Data rate:"
        ).grid(row=2, column=0, sticky="w", padx=5, pady=4)

        ttk.Label(
            stats,
            textvariable=self.speed_var
        ).grid(row=2, column=1, sticky="e", padx=5)

        stats.columnconfigure(1, weight=1)

        buttons = ttk.Frame(main)
        buttons.pack(fill="x", pady=15)

        self.start_button = ttk.Button(
            buttons,
            text="Start Streaming",
            command=self.start_streaming
        )
        self.start_button.pack(
            side="left",
            fill="x",
            expand=True,
            padx=(0, 5)
        )

        self.stop_button = ttk.Button(
            buttons,
            text="Stop",
            command=self.stop_streaming,
            state="disabled"
        )
        self.stop_button.pack(
            side="left",
            fill="x",
            expand=True,
            padx=(5, 0)
        )

        ttk.Label(
            main,
            text="Micromax P280 | 800 x 480 | ADB USB",
            foreground="gray",
            font=("Segoe UI", 8)
        ).pack(pady=5)

    def emit(self, event):
        self.events.put(event)

    def process_events(self):

        try:
            while True:
                event = self.events.get_nowait()
                event_type = event[0]

                if event_type == "status":
                    self.status_var.set(event[1])

                elif event_type == "stats":
                    _, frames, fps, speed = event

                    self.frames_var.set(str(frames))
                    self.actual_fps_var.set(f"{fps:.1f} FPS")
                    self.speed_var.set(f"{speed:.1f} KB/s")

                elif event_type == "stopped":
                    self.start_button.config(state="normal")
                    self.stop_button.config(state="disabled")
                    self.status_var.set(event[1])

        except queue.Empty:
            pass

        self.root.after(100, self.process_events)

    def check_device_and_forward(self):

        result = subprocess.run(
            [ADB, "devices"],
            capture_output=True,
            text=True,
            timeout=10
        )

        if result.returncode != 0:
            raise RuntimeError("ADB command failed")

        devices = [
            line.split()[0]
            for line in result.stdout.splitlines()[1:]
            if len(line.split()) >= 2
            and line.split()[1] == "device"
        ]

        if not devices:
            raise RuntimeError(
                "No authorized Android device found. "
                "Check USB debugging and cable."
            )

        subprocess.run(
            [ADB, "forward", f"tcp:{PORT}", f"tcp:{PORT}"],
            capture_output=True,
            text=True,
            timeout=10,
            check=True
        )

    def capture_frame(self, quality):

        screenshot = ImageGrab.grab()

        resampling = getattr(Image, "Resampling", Image).LANCZOS

        screenshot = screenshot.resize(
            (WIDTH, HEIGHT),
            resampling
        )

        buffer = BytesIO()

        screenshot.save(
            buffer,
            format="JPEG",
            quality=quality,
            optimize=True
        )

        return buffer.getvalue()

    def start_streaming(self):

        try:
            fps = int(self.fps_var.get())
            quality = int(self.quality_var.get())

            if not 1 <= fps <= 30:
                raise ValueError("FPS must be between 1 and 30")

            if not 20 <= quality <= 95:
                raise ValueError("JPEG quality must be between 20 and 95")

        except ValueError as error:
            messagebox.showerror("Invalid settings", str(error))
            return

        self.stop_event.clear()

        self.start_button.config(state="disabled")
        self.stop_button.config(state="normal")

        self.frames_var.set("0")
        self.actual_fps_var.set("0 FPS")
        self.speed_var.set("0 KB/s")

        self.worker = threading.Thread(
            target=self.streaming_worker,
            args=(fps, quality),
            daemon=True
        )

        self.worker.start()

    def streaming_worker(self, fps, quality):

        frames = 0
        total_bytes = 0

        stats_start = time.perf_counter()
        stats_frames = 0
        stats_bytes = 0

        frame_interval = 1.0 / fps

        while not self.stop_event.is_set():

            sock = None

            try:
                self.emit(("status", "Checking USB connection..."))

                self.check_device_and_forward()

                self.emit(("status", "Connecting to tablet..."))

                sock = socket.create_connection(
                    (HOST, PORT),
                    timeout=5
                )

                sock.settimeout(3)

                self.emit(("status", "Connected - Streaming"))

                while not self.stop_event.is_set():

                    frame_start = time.perf_counter()

                    jpeg_data = self.capture_frame(quality)

                    header = len(jpeg_data).to_bytes(
                        4,
                        byteorder="big"
                    )

                    sock.sendall(header + jpeg_data)

                    frames += 1
                    total_bytes += len(jpeg_data) + 4

                    stats_frames += 1
                    stats_bytes += len(jpeg_data) + 4

                    now = time.perf_counter()
                    elapsed = now - stats_start

                    if elapsed >= 1.0:

                        actual_fps = stats_frames / elapsed
                        speed = stats_bytes / elapsed / 1024

                        self.emit((
                            "stats",
                            frames,
                            actual_fps,
                            speed
                        ))

                        stats_start = now
                        stats_frames = 0
                        stats_bytes = 0

                    processing_time = time.perf_counter() - frame_start

                    self.stop_event.wait(
                        max(0, frame_interval - processing_time)
                    )

            except Exception as error:

                if not self.stop_event.is_set():
                    self.emit((
                        "status",
                        f"Reconnecting: {str(error)[:65]}"
                    ))

                    self.stop_event.wait(2)

            finally:
                if sock is not None:
                    try:
                        sock.close()
                    except OSError:
                        pass

        self.emit(("stopped", "Disconnected"))

    def stop_streaming(self):

        self.stop_event.set()
        self.stop_button.config(state="disabled")
        self.status_var.set("Stopping...")

    def close_app(self):

        self.stop_event.set()
        self.root.destroy()


if __name__ == "__main__":

    root = tk.Tk()
    app = MicromaxDisplayApp(root)
    root.mainloop()
