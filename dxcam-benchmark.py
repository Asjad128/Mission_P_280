import time
import dxcam

camera = dxcam.create(
    device_idx=0,
    output_idx=3,
    output_color="RGB"
)

if camera is None:
    raise RuntimeError("Could not initialize DXcam for output 3")

camera.start(target_fps=30, video_mode=True)

frames = 0
start = time.perf_counter()

try:
    while time.perf_counter() - start < 10:
        frame = camera.get_latest_frame()

        if frame is not None:
            frames += 1

    elapsed = time.perf_counter() - start

    print("Resolution:", frame.shape)
    print("Frames:", frames)
    print("Elapsed:", round(elapsed, 2), "seconds")
    print("Capture FPS:", round(frames / elapsed, 2))

    from PIL import Image
    Image.fromarray(frame).save("dxcam-display4-test.png")
    print("Test image saved.")

finally:
    camera.stop()
