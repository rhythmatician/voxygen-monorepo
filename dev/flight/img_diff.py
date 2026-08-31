"""Programmatic before/after diff for flight-tour screenshots.

Replaces the manual GIMP workflow: computes a pixel-difference mask between
two screenshots, reports changed-pixel statistics, and writes a highlighted
overlay so the changed region is visible without opening an editor.

Usage:
  python img_diff.py before.png after.png
  python img_diff.py before.png after.png --region L,T,R,B   # focus box (pixels)
  python img_diff.py before.png after.png --out overlay.png

Exit code 0 = images effectively identical (within threshold), 1 = differ.
"""

import argparse
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("before", type=Path)
    ap.add_argument("after", type=Path)
    ap.add_argument("--out", type=Path, default=None, help="write highlighted overlay PNG here")
    ap.add_argument(
        "--region",
        type=int,
        nargs=4,
        metavar=("L", "T", "R", "B"),
        default=None,
        help="only consider this pixel box",
    )
    ap.add_argument(
        "--threshold",
        type=int,
        default=24,
        help="per-channel delta below this counts as unchanged",
    )
    args = ap.parse_args()

    before = Image.open(args.before).convert("RGB")
    after = Image.open(args.after).convert("RGB")
    if before.size != after.size:
        print(f"SIZE MISMATCH: {before.size} vs {after.size}")
        return 1

    diff = ImageChops.difference(before, after)
    # Grayscale max-channel difference per pixel.
    gray = diff.convert("L")
    # Threshold: zero out small deltas (compression noise).
    mask = gray.point(lambda p: 255 if p > args.threshold else 0)

    if args.region:
        left, top, right, bottom = args.region
        region_mask = Image.new("L", mask.size, 0)
        region_mask.paste(mask.crop((left, top, right, bottom)), (left, top))
        mask = region_mask

    bbox = mask.getbbox()
    hist = mask.histogram()
    changed = hist[255]
    total = mask.size[0] * mask.size[1]

    print(f"before : {args.before}")
    print(f"after  : {args.after}")
    print(f"size   : {mask.size[0]}x{mask.size[1]}")
    print(f"changed pixels (> {args.threshold}): {changed} ({100.0 * changed / total:.3f}%)")

    if bbox:
        bw, bh = bbox[2] - bbox[0], bbox[3] - bbox[1]
        cx, cy = (bbox[0] + bbox[2]) // 2, (bbox[1] + bbox[3]) // 2
        print(f"bbox   : {bbox}  ({bw}x{bh}, center {cx},{cy})")
    else:
        print("bbox   : none — images are effectively identical")
        return 0

    if args.out:
        overlay = after.copy()
        tint = Image.new("RGB", mask.size, (255, 0, 255))
        # The mask is already binary (0/255 from the threshold step), so use
        # it directly — composite shows full-strength tint on changed pixels.
        overlay = Image.composite(tint, overlay, mask)
        draw = ImageDraw.Draw(overlay)
        draw.rectangle(bbox, outline=(255, 0, 255), width=3)
        overlay.save(args.out)
        print(f"overlay: {args.out}")

    return 1


if __name__ == "__main__":
    sys.exit(main())
