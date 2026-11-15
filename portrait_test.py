import io, socket, struct, sys, time
from PIL import Image, ImageDraw, ImageFont

mode = sys.argv[1] if len(sys.argv) > 1 else "ccw"

card = Image.new("RGB", (480, 800), (20, 20, 20))
d = ImageDraw.Draw(card)
try:
    f = ImageFont.truetype("arial.ttf", 64)
except Exception:
    f = ImageFont.load_default()
d.rectangle([0, 0, 479, 799], outline=(255, 255, 255), width=6)
d.polygon([(240, 120), (160, 240), (320, 240)], fill=(255, 200, 0))
d.text((240, 60), "TOP", fill=(255, 255, 255), font=f, anchor="mm")
d.text((240, 400), "PORTRAIT", fill=(0, 220, 120), font=f, anchor="mm")
d.text((240, 740), "BOTTOM", fill=(255, 255, 255), font=f, anchor="mm")

img = card.rotate(90 if mode == "ccw" else -90, expand=True)
print("Frame size:", img.size, "mode:", mode)
buf = io.BytesIO()
img.save(buf, "JPEG", quality=70)
data = buf.getvalue()

try:
    s = socket.create_connection(("127.0.0.1", 27183), timeout=5)
except Exception as e:
    sys.exit("Connect failed: %r (is the tablet app open and adb forward set?)" % (e,))
for _ in range(100):
    s.sendall(struct.pack(">I", len(data)) + data)
    time.sleep(0.2)
s.close()
print("Done")