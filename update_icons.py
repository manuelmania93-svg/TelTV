import os
import glob
from PIL import Image

# Search for the uploaded poster image
candidates = glob.glob("*poster*", recursive=False) + glob.glob("*.png", recursive=False) + glob.glob("app/*poster*", recursive=True)
image_path = None

for c in candidates:
    if os.path.isfile(c) and not c.endswith("update_icons.py"):
        image_path = c
        break

if not image_path:
    # Check if user dragged it anywhere into the workspace
    all_imgs = [f for f in glob.glob("**/*.png", recursive=True) if "mipmap" not in f and "tv_banner" not in f and "brand_poster" not in f]
    if all_imgs:
        image_path = all_imgs[0]

if not image_path:
    print("❌ Error: Could not find any uploaded poster image! Make sure you dragged the image into Codespaces.")
    exit(1)

print(f"👉 Using source image: {image_path}")

img = Image.open(image_path).convert("RGBA")
W, H = img.size

# 1. Update brand_poster.png
res_drawable = "app/src/main/res/drawable"
os.makedirs(res_drawable, exist_ok=True)
img.save(f"{res_drawable}/brand_poster.png")

# 2. Extract top square logo for App Icon
crop_box = (int(W * 0.08), int(H * 0.05), int(W * 0.92), int(H * 0.58))
icon_img = img.crop(crop_box)

for res, size in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
    path = f"app/src/main/res/{res}"
    os.makedirs(path, exist_ok=True)
    icon_img.resize((size, size), Image.Resampling.LANCZOS).save(f"{path}/ic_launcher.png")

# 3. Create 16:9 Android TV Leanback Banner (320x180)
banner = Image.new("RGBA", (320, 180), (0, 0, 0, 255))
scaled_poster = img.copy()
scaled_poster.thumbnail((180, 180), Image.Resampling.LANCZOS)
x_offset = (320 - scaled_poster.width) // 2
banner.paste(scaled_poster, (x_offset, 0), scaled_poster)
banner.save(f"{res_drawable}/tv_banner.png")

print("✅ Successfully updated brand_poster.png, ic_launcher, and tv_banner.png!")
