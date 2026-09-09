import os
from PIL import Image

if not os.path.exists("logo.png"):
    print("❌ Error: logo.png not found in the project root! Please drag and drop your image into Codespaces as logo.png first.")
    exit(1)

img = Image.open("logo.png")
w, h = img.size
print(f"Loaded logo.png ({w}x{h})")

# 1. Crop the golden TV + paper airplane emblem
# Based on the golden rounded rectangle bounds
pad = int(w * 0.025)
crop_box = (
    max(0, int(w * 0.12) - pad),
    max(0, int(h * 0.07) - pad),
    min(w, int(w * 0.88) + pad),
    min(h, int(h * 0.57) + pad)
)
emblem = img.crop(crop_box)

# Square the emblem with a matching dark border
max_dim = max(emblem.size)
square_icon = Image.new("RGB", (max_dim, max_dim), (0, 0, 0))
paste_pos = ((max_dim - emblem.size[0]) // 2, (max_dim - emblem.size[1]) // 2)
square_icon.paste(emblem, paste_pos)

# 2. Generate all Android TV & Google TV launcher mipmap icons
mipmap_sizes = {
    "app/src/main/res/mipmap-mdpi": 48,
    "app/src/main/res/mipmap-hdpi": 72,
    "app/src/main/res/mipmap-xhdpi": 96,
    "app/src/main/res/mipmap-xxhdpi": 144,
    "app/src/main/res/mipmap-xxxhdpi": 192,
}

for folder, size in mipmap_sizes.items():
    os.makedirs(folder, exist_ok=True)
    resized = square_icon.resize((size, size), Image.Resampling.LANCZOS)
    resized.save(os.path.join(folder, "ic_launcher.png"), "PNG")
    resized.save(os.path.join(folder, "ic_launcher_round.png"), "PNG")
    print(f"✅ Generated {size}x{size} icon for {folder}")

# 3. Generate standard Android TV Leanback Banner (16:9 widescreen)
# Crop emblem + TeLTv typography for widescreen banner
banner_crop = img.crop((0, int(h * 0.05), w, int(h * 0.82)))
banner_16_9 = Image.new("RGB", (1920, 1080), (0, 0, 0))

# Scale banner content to fit comfortably
banner_crop.thumbnail((1000, 1000), Image.Resampling.LANCZOS)
bx = (1920 - banner_crop.size[0]) // 2
by = (1080 - banner_crop.size[1]) // 2
banner_16_9.paste(banner_crop, (bx, by))

# Save 320x180 leanback banner (Android TV standard)
os.makedirs("app/src/main/res/drawable", exist_ok=True)
os.makedirs("app/src/main/res/drawable-xhdpi", exist_ok=True)

tv_banner_small = banner_16_9.resize((320, 180), Image.Resampling.LANCZOS)
tv_banner_small.save("app/src/main/res/drawable/tv_banner.png", "PNG")
tv_banner_small.save("app/src/main/res/drawable-xhdpi/tv_banner.png", "PNG")
print("✅ Generated 320x180 Android TV Leanback Banners!")

# Also save high-res brand banner for splash / login screen
os.makedirs("app/src/main/res/drawable-nodpi", exist_ok=True)
banner_16_9.save("app/src/main/res/drawable-nodpi/brand_poster.png", "PNG")
print("✅ Generated 1920x1080 high-res brand banner for TV login!")

print("\n🎉 All icons & TV banners successfully updated!")
