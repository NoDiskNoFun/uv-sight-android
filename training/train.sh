#!/bin/sh
# Train the arrow detector and the face finder from one or more app exports and pack both as int8
# TFLite models, with the evaluation, into model.zip for import into the app.
# Needs: python3, pip install ultralytics pillow
#   ./train.sh export.zip [more.zip ...]        (image size via IMGSZ=320 ./train.sh ..., default 640)
set -e
[ $# -ge 1 ] || { echo "usage: train.sh <uv-sight-training-*.zip> [more.zip ...]" >&2; exit 1; }
IMGSZ=${IMGSZ:-640}
EPOCHS=${EPOCHS:-120}
here=$(dirname "$0")
python3 "$here/prepare_dataset.py" "$@" --out dataset
python3 "$here/prepare_dataset.py" "$@" --out dataset_face --faces
export_int8() {   # model.pt dataset out.tflite: Ultralytics 8.4 (litert/quantize) or older (tflite/int8)
  yolo export model="$1" format=litert quantize=int8 imgsz="$IMGSZ" data="$2/data.yaml" 2>/dev/null \
    || yolo export model="$1" format=tflite int8=True imgsz="$IMGSZ" data="$2/data.yaml"
  f=$(ls "$(dirname "$1")"/best_saved_model/*int8*.tflite "$(dirname "$1")"/*int8*.tflite 2>/dev/null | head -1)
  cp "$f" "$3"
}
# Pose models, nano size: one keypoint per arrow (the entry point); five per face (centre, top, right, bottom, left)
yolo pose train data=dataset/data.yaml model=yolo11n-pose.pt imgsz="$IMGSZ" epochs="$EPOCHS" batch=16 project=runs name=arrows exist_ok=True
export_int8 runs/arrows/weights/best.pt dataset arrows.tflite
yolo pose train data=dataset_face/data.yaml model=yolo11n-pose.pt imgsz="$IMGSZ" epochs="$EPOCHS" batch=16 project=runs name=face exist_ok=True
export_int8 runs/face/weights/best.pt dataset_face face.tflite
python3 - "$IMGSZ" <<'PY'
import json, sys, time
json.dump({"name": time.strftime("train-%Y%m%d-%H%M%S"), "imgsz": int(sys.argv[1]), "task": "pose", "trained": time.time(),
           "arrows": {"file": "arrows.tflite", "kpt": ["entry"]}, "face": {"file": "face.tflite", "kpt": ["centre", "top", "right", "bottom", "left"]}},
          open("model.json", "w"), indent=1)
PY
rm -f model.zip && zip -q model.zip arrows.tflite face.tflite model.json
echo "model: model.zip (import it in the app under Settings -> Photo scoring -> Detection model; the web page also evaluates the result)"
