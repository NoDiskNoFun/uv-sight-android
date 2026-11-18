#!/bin/sh
# Train the arrow detector and the face finder from one or more app exports and pack both as int8
# TFLite models, with the evaluation, into model.zip for import into the app.
# Needs: python3, pip install -r requirements.txt
#   ./train.sh export.zip [more.zip ...]        (image size via IMGSZ=320 ./train.sh ..., default 640)
# With two or more GPUs the two models train at the same time, the arrow detector on GPU 0 and the face finder on
# GPU 1; with one they take turns. NGPU=1 forces turns. On Linux ARM64 (or with NOCKED_EXPORT=tensorflow) the TFLite
# comes from TensorFlow through onnx2tf, because LiteRT has no converter there.
set -e
[ $# -ge 1 ] || { echo "usage: train.sh <uv-sight-training-*.zip> [more.zip ...]" >&2; exit 1; }
IMGSZ=${IMGSZ:-640}
EPOCHS=${EPOCHS:-120}
BATCH=${BATCH:-16}
here=$(dirname "$0")
NGPU=${NGPU:-$(python3 -c "import torch; print(torch.cuda.device_count())" 2>/dev/null || echo 0)}
case "$(uname -s)-$(uname -m)" in Linux-aarch64|Linux-arm64) NOCKED_EXPORT=tensorflow ;; esac
python3 "$here/prepare_dataset.py" "$@" --out dataset
python3 "$here/prepare_dataset.py" "$@" --out dataset_face --faces
train() {   # name dataset device: pose model, nano size; one keypoint per arrow (the entry point), five per face
  yolo pose train data="$2/data.yaml" model=yolo11n-pose.pt imgsz="$IMGSZ" epochs="$EPOCHS" batch="$BATCH" \
    project="$PWD/runs" name="$1" exist_ok=True device="$3"   # absolute: Ultralytics 8.4 puts a relative one under runs/pose/
}
export_int8() {   # model.pt dataset out.tflite
  d=$(dirname "$1")
  if [ "$NOCKED_EXPORT" = tensorflow ]; then
    # onnx2tf writes *_integer_quant.tflite: int8 inside, float input and output (the app takes NHWC as well)
    yolo export model="$1" format=saved_model quantize=8 imgsz="$IMGSZ" data="$2/data.yaml" device=cpu
    f=$(ls "$d"/best_saved_model/*_integer_quant.tflite 2>/dev/null | grep -v full_integer | grep -v int16 | head -1)
  else   # Ultralytics 8.4 (litert/quantize) or older (tflite/int8)
    yolo export model="$1" format=litert quantize=8 imgsz="$IMGSZ" data="$2/data.yaml" 2>/dev/null \
      || yolo export model="$1" format=tflite int8=True imgsz="$IMGSZ" data="$2/data.yaml"
    f=$(ls "$d"/best_saved_model/*int8*.tflite "$d"/*int8*.tflite 2>/dev/null | head -1)
  fi
  [ -n "$f" ] || { echo "no int8 TFLite found next to $1" >&2; exit 1; }
  cp "$f" "$3"
}
python3 -c "from ultralytics import YOLO; YOLO('yolo11n-pose.pt')" >/dev/null   # fetch the weights once, not twice at the same time
if [ "$NGPU" -ge 2 ]; then
  echo "$NGPU GPUs: arrow detector on GPU 0 and face finder on GPU 1 at the same time"
  train arrows dataset 0 > runs-arrows.log 2>&1 & pa=$!
  train face dataset_face 1 > runs-face.log 2>&1 & pf=$!
  ok=0
  wait $pa || { echo "arrow detector failed, see runs-arrows.log" >&2; ok=1; }
  wait $pf || { echo "face finder failed, see runs-face.log" >&2; ok=1; }
  [ $ok -eq 0 ] || exit 1
else
  dev=$([ "$NGPU" -ge 1 ] && echo 0 || echo cpu)
  train arrows dataset "$dev"
  train face dataset_face "$dev"
fi
# the exports one after the other: they share a calibration file in the working folder
export_int8 runs/arrows/weights/best.pt dataset arrows.tflite
export_int8 runs/face/weights/best.pt dataset_face face.tflite
python3 - "$IMGSZ" <<'PY'
import json, sys, time
json.dump({"name": time.strftime("train-%Y%m%d-%H%M%S"), "imgsz": int(sys.argv[1]), "task": "pose", "trained": time.time(),
           "arrows": {"file": "arrows.tflite", "kpt": ["entry"]}, "face": {"file": "face.tflite", "kpt": ["centre", "top", "right", "bottom", "left"]}},
          open("model.json", "w"), indent=1)
PY
rm -f model.zip && zip -q model.zip arrows.tflite face.tflite model.json
echo "model: model.zip (import it in the app under Settings -> Photo scoring -> Detection model; the web page also evaluates the result)"
