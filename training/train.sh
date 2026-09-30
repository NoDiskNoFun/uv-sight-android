#!/bin/sh
# Train the arrow entry-point detector from an app export and turn it into an int8 TFLite model.
# Needs: python3, pip install ultralytics pillow
set -e
EXPORT=${1:?usage: train.sh <uv-sight-training-*.zip> [imgsz]}
IMGSZ=${2:-640}
python3 "$(dirname "$0")/prepare_dataset.py" "$EXPORT" --out dataset
# Pose model with one keypoint per arrow; nano size so it can later run on small devices
yolo pose train data=dataset/data.yaml model=yolo11n-pose.pt imgsz="$IMGSZ" epochs=120 batch=16 project=runs name=arrows exist_ok=True
# int8 TFLite export (quantisation is calibrated on the validation images)
yolo export model=runs/arrows/weights/best.pt format=tflite int8=True imgsz="$IMGSZ" data=dataset/data.yaml
echo "model: runs/arrows/weights/best_saved_model/best_int8.tflite"
