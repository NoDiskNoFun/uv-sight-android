#!/usr/bin/env python3
"""Turn a UV-Sight training export (ZIP or unpacked folder) into a YOLO pose dataset.

Each arrow becomes one object of class "arrow" with a single keypoint: the entry point.
With --faces the dataset marks the target face instead: a box around the blue edge with five
keypoints, the centre and the highest, rightmost, lowest and leftmost point of the edge
(computed from the fitted ellipse, so they are the same for every photo). Photos without a
usable face marking are left out of the face dataset.

The JSON records hold coordinates in the *upright* image (EXIF orientation applied),
so the images are rotated here the same way before they are written out.

Usage:
  python prepare_dataset.py export.zip [more.zip ...] --out dataset [--val 0.2] [--faces]
"""
import argparse
import json
import random
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import facegeom  # noqa: E402

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


def load_many(sources, work: Path):
    """Records of several exports (ZIPs or already unpacked folders) in one list.

    With more than one export, photo ids and session keys get the export's name as prefix so two
    phones can never collide, and every record notes its export in "_export"."""
    sources = [Path(s) for s in sources]
    out = []
    for src in sources:
        stem = src.stem if src.suffix == ".zip" else src.name
        recs = load_records(src, work / stem) if src.suffix == ".zip" else load_records(src, src)
        for r, img in recs:
            r["_export"] = stem
            if len(sources) > 1:
                r["id"] = f"{stem}__{r['id']}"
                if r.get("sessionKey"):
                    r["sessionKey"] = f"{stem}:{r['sessionKey']}"
        out.extend(recs)
    return out


def split_by_session(recs, val_frac, seed=1):
    """Keep all ends of one session on the same side, so validation is honest."""
    keys = sorted({(r.get("sessionKey") or r["id"]) for r, _ in recs})
    random.Random(seed).shuffle(keys)
    n_val = max(1, int(len(keys) * val_frac)) if len(keys) > 1 else 0
    val = set(keys[:n_val])
    return [x for x in recs if (x[0].get("sessionKey") or x[0]["id"]) not in val], [x for x in recs if (x[0].get("sessionKey") or x[0]["id"]) in val]


def face_label(r):
    """YOLO pose line for the face of a record, or None without a usable marking."""
    g = facegeom.geometry_of_record(r)
    if g is None:
        return None
    w, h = r["width"], r["height"]
    k = facegeom.face_keypoints(g)
    x0, y0, x1, y1 = facegeom.face_box(k, w, h)
    if x1 - x0 < 8 or y1 - y0 < 8:
        return None
    return f"0 {(x0 + x1) / 2 / w:.6f} {(y0 + y1) / 2 / h:.6f} {(x1 - x0) / w:.6f} {(y1 - y0) / h:.6f} " + " ".join(f"{p[0] / w:.6f} {p[1] / h:.6f} 2" for p in k)


def with_face(recs):
    """The records whose face marking gives a geometry (what the face model can learn from)."""
    return [x for x in recs if face_label(x[0]) is not None]


def data_yaml(out: Path, faces: bool):
    if faces:
        return f"path: {out.resolve()}\ntrain: images/train\nval: images/val\nkpt_shape: [5, 3]\nflip_idx: [0, 1, 4, 3, 2]\nnames:\n  0: face\n"
    return f"path: {out.resolve()}\ntrain: images/train\nval: images/val\nkpt_shape: [1, 3]\nflip_idx: [0]\nnames:\n  0: arrow\n"


def write_split(recs, out: Path, name: str, faces: bool):
    (out / "images" / name).mkdir(parents=True, exist_ok=True)
    (out / "labels" / name).mkdir(parents=True, exist_ok=True)
    n_obj = 0
    if faces:
        recs = with_face(recs)
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
        else:
            lines.append(face_label(r))
        n_obj += len(lines)
        (out / "labels" / name / (r["id"] + ".txt")).write_text("\n".join(lines) + ("\n" if lines else ""))
    return n_obj


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source", type=Path, nargs="+", help="export ZIPs or unpacked folders (several are merged)")
    ap.add_argument("--out", type=Path, default=Path("dataset"))
    ap.add_argument("--val", type=float, default=0.2, help="share of sessions used for validation")
    ap.add_argument("--faces", action="store_true", help="build the face dataset instead of the arrow dataset")
    args = ap.parse_args()
    work = args.out / "_unpacked"
    recs = load_many(args.source, work)
    if not recs:
        sys.exit("no records found")
    train, val = split_by_session(recs, args.val)
    if not val:
        print("only one session: validating on the training photos (optimistic numbers)", file=sys.stderr)
        val = train
    n_tr = write_split(train, args.out, "train", args.faces)
    n_va = write_split(val, args.out, "val", args.faces)
    cls = "face" if args.faces else "arrow"
    (args.out / "data.yaml").write_text(data_yaml(args.out, args.faces))
    print(f"{len(train)} train photos ({n_tr} {cls}s), {len(val)} val photos ({n_va} {cls}s) -> {args.out}/data.yaml")


if __name__ == "__main__":
    main()
