# The trainer as a container, for x86_64 and ARM64 (aarch64) Linux with NVIDIA GPUs.
#
#   docker build -t nocked-trainer training/
#   docker run --gpus all --ipc=host -p 127.0.0.1:8000:8000 \
#       -v "$PWD/trainer-data:/data" -v "$PWD/trainer-runs:/runs" nocked-trainer
#
# then open http://localhost:8000. The host needs the NVIDIA driver and the NVIDIA Container Toolkit; the CUDA
# libraries come with the PyTorch wheel (CUDA 13 at the time of writing: driver 580 or newer). With one GPU the two
# models train one after the other, with two or more at the same time; which GPUs the container sees is set by
# --gpus '"device=1,2"' or the compose file (docker-compose.yml). --ipc=host (or --shm-size=8g) is needed by the
# data loaders. The page has no login: keep the port on 127.0.0.1.
#
# The same requirements.txt serves both platforms: on ARM64 it installs TensorFlow and onnx2tf for the TFLite
# export (LiteRT has no Linux ARM64 converter), on x86_64 litert-torch.
FROM python:3.12-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 zip \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Ultralytics must not pip-install at run time; its settings and the multi-process files go to a writable folder
ENV YOLO_AUTOINSTALL=false \
    YOLO_CONFIG_DIR=/app/.config \
    NOCKED_DATA=/data \
    NOCKED_RUNS=/runs \
    PYTHONUNBUFFERED=1

# The pretrained weights of the three model sizes, and one small export as a check that the TFLite path of this
# platform works (it also fetches the calibration sample onnx2tf needs); its files are thrown away.
RUN python -c "from ultralytics import YOLO; [YOLO(f'yolo11{s}-pose.pt') for s in 'nsm']" \
 && if [ "$(uname -m)" = aarch64 ]; then fmt=saved_model; else fmt=litert; fi \
 && mkdir /tmp/check && cp yolo11n-pose.pt /tmp/check/ \
 && yolo export model=/tmp/check/yolo11n-pose.pt format=$fmt imgsz=64 device=cpu \
 && ls /tmp/check/*.tflite /tmp/check/*_saved_model/*.tflite 2>/dev/null | grep -q . \
 && rm -rf /tmp/check \
 && mkdir -p /data /runs "$YOLO_CONFIG_DIR" && chmod -R a+rwX /app /data /runs

COPY facegeom.py prepare_dataset.py webui.py train.sh ./

EXPOSE 8000
VOLUME ["/data", "/runs"]
CMD ["python", "webui.py", "--host", "0.0.0.0", "--port", "8000"]
