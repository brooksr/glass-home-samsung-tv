import sys, os
from PIL import Image, ImageOps
src, dst = sys.argv[1], sys.argv[2]
for f in sorted(os.listdir(src)):
    if not f.endswith('.jpg'): continue
    try:
        im = ImageOps.exif_transpose(Image.open(os.path.join(src, f))).convert('RGB')
    except Exception as e:
        print(f, 'unreadable', e); continue
    im = ImageOps.fit(im, (1920, 1080), Image.LANCZOS)
    im.save(os.path.join(dst, f), 'JPEG', quality=80, optimize=True, progressive=True)
