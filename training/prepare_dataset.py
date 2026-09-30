#!/usr/bin/env python3
"""Turn a UV-Sight training export (ZIP or unpacked folder) into a YOLO pose dataset.

Each arrow becomes one object of class "arrow" with a single keypoint: the entry point.
Optionally (--faces) a second dataset marks the target face: a box around the fitted
ellipse with the tapped centre as keypoint.

The JSON records hold coordinates in the *upright* image (EXIF orientation applied),
so the images are rotated here the same way before they are written out.

Usage:
  python prepare_dataset.py export.zip --out dataset [--val 0.2] [--faces]
"""
import argparse
import json
import random
import sys
import zipfile
from pathlib import Path

try:
    from PIL import Image, ImageOps
except ImportError:  # pragma: no cover
    Image = None

ARROW_BOX = 48.0      # px box around the entry point (the same as the app's COCO export)


def load_records(src: Path, work: Path):
    """Return [(record, image_path)] from a ZIP or a folder with images/ and records/."""
    if src.is_file() and src.suffix == ".zip":
        with zipfile.ZipFile(src) as z:
            z.extractall(work)
        src = work
    recs = []
    for rj in sorted((src / "records").glob("*.json")) if (src / "records").exists() else sorted(src.glob("*.json")):
        r = json.loads(rj.read_text())
        if "arrows" not in r:
            continue
        img = src / "images" / r["image"] if (src / "images").exists() else src / r["image"]
        if img.exists():
            recs.append((r, img))
    return recs


def split_by_session(recs, val_frac, seed=1):
    """Keep all ends of one session on the same side, so validation is honest."""
    keys = sorted({(r.get("sessionKey") or r["id"]) for r, _ in recs})
    random.Random(seed).shuffle(keys)
    n_val = max(1, int(len(keys) * val_frac)) if len(keys) > 1 else 0
    val = set(keys[:n_val])
    return [x for x in recs if (x[0].get("sessionKey") or x[0]["id"]) not in val], [x for x in recs if (x[0].get("sessionKey") or x[0]["id"]) in val]


def write_split(recs, out: Path, name: str, faces: bool):
    (out / "images" / name).mkdir(parents=True, exist_ok=True)
    (out / "labels" / name).mkdir(parents=True, exist_ok=True)
    n_obj = 0
    for r, img_path in recs:
        w, h = r["width"], r["height"]
        if Image is not None:
            im = ImageOps.exif_transpose(Image.open(img_path))
            if im.size != (w, h):
                print(f"warning: {r['id']}: image {im.size} differs from record {w}x{h}", file=sys.stderr)
            im.save(out / "images" / name / (r["id"] + ".jpg"), quality=92)
        else:
            (out / "images" / name / (r["id"] + ".jpg")).write_bytes(img_path.read_bytes())
        lines = []
        if not faces:
            for a in r["arrows"]:
                cx, cy = a["px"] / w, a["py"] / h
                lines.append(f"0 {cx:.6f} {cy:.6f} {ARROW_BOX / w:.6f} {ARROW_BOX / h:.6f} {cx:.6f} {cy:.6f} 2")
        elif r.get("center") and r.get("conic"):
            xs = [p[0] for p in r["edge"]]; ys = [p[1] for p in r["edge"]]
            x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
            cx, cy = (x0 + x1) / 2 / w, (y0 + y1) / 2 / h
            lines.append(f"0 {cx:.6f} {cy:.6f} {(x1 - x0) / w:.6f} {(y1 - y0) / h:.6f} {r['center'][0] / w:.6f} {r['center'][1] / h:.6f} 2")
        n_obj += len(lines)
        (out / "labels" / name / (r["id"] + ".txt")).write_text("\n".join(lines) + ("\n" if lines else ""))
    return n_obj


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source", type=Path, help="export ZIP or unpacked folder")
    ap.add_argument("--out", type=Path, default=Path("dataset"))
    ap.add_argument("--val", type=float, default=0.2, help="share of sessions used for validation")
    ap.add_argument("--faces", action="store_true", help="build the face dataset instead of the arrow dataset")
    args = ap.parse_args()
    work = args.out / "_unpacked"
    recs = load_records(args.source, work)
    if not recs:
        sys.exit("no records found")
    train, val = split_by_session(recs, args.val)
    n_tr = write_split(train, args.out, "train", args.faces)
    n_va = write_split(val, args.out, "val", args.faces)
    cls = "face" if args.faces else "arrow"
    (args.out / "data.yaml").write_text(
        f"path: {args.out.resolve()}\ntrain: images/train\nval: images/val\nkpt_shape: [1, 3]\nnames:\n  0: {cls}\n")
    print(f"{len(train)} train photos ({n_tr} {cls}s), {len(val)} val photos ({n_va} {cls}s) -> {args.out}/data.yaml")


if __name__ == "__main__":
    main()
