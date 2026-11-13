import time
import dxcam
import cv2

camera = dxcam.create(
    device_idx=0,
    output_idx=3,
    output_color="BGR"
)

camera.start(target_fps=30, video_mode=True)

try:
    time.sleep(2)

    samples = []

    for _ in range(100):
        frame = camera.get_latest_frame()

        if frame is None:
            continue

        start = time.perf_counter()

        resized = cv2.resize(
            frame,
            (640, 360),
            interpolation=cv2.INTER_AREA
        )

        success, encoded = cv2.imencode(
            ".jpg",
            resized,
            [cv2.IMWRITE_JPEG_QUALITY, 25]
        )

        elapsed = (time.perf_counter() - start) * 1000

        if success:
            samples.append((elapsed, len(encoded)))

    print("Samples:", len(samples))
    print("Average processing:", round(sum(x[0] for x in samples) / len(samples), 2), "ms")
    print("Average JPEG size:", round(sum(x[1] for x in samples) / len(samples) / 1024, 2), "KB")

finally:
    camera.stop()
