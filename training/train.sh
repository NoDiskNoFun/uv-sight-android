#!/bin/sh
# Train the arrow entry-point detector from one or more app exports and turn it into an int8 TFLite model.
# Needs: python3, pip install ultralytics pillow
#   ./train.sh export.zip [more.zip ...]        (image size via IMGSZ=320 ./train.sh ..., default 640)
set -e
[ $# -ge 1 ] || { echo "usage: train.sh <uv-sight-training-*.zip> [more.zip ...]" >&2; exit 1; }
IMGSZ=${IMGSZ:-640}
python3 "$(dirname "$0")/prepare_dataset.py" "$@" --out dataset
# Pose model with one keypoint per arrow; nano size so it can later run on small devices
yolo pose train data=dataset/data.yaml model=yolo11n-pose.pt imgsz="$IMGSZ" epochs=120 batch=16 project=runs name=arrows exist_ok=True
# int8 TFLite export (quantisation is calibrated on the validation images)
yolo export model=runs/arrows/weights/best.pt format=tflite int8=True imgsz="$IMGSZ" data=dataset/data.yaml
echo "model: runs/arrows/weights/best_saved_model/best_int8.tflite"
