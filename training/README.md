# Training the arrow detector and the face finder

The app collects training data when *Settings → Photo scoring → Collect training data* is on: every scored photo with the marked face and arrows. This folder turns such an export into two models that later propose the marks in the app: the **arrow detector** (one keypoint per arrow, the entry point) and the **face finder** (one object per photo with five keypoints: the centre and the highest, rightmost, lowest and leftmost point of the blue ring's edge, computed from the fitted ellipse so they are the same whatever the user tapped). Photos without a usable face marking are left out of the face dataset.

## Data format

The export ZIP holds `images/<id>.jpg`, `records/<id>.json` (one per photo, see `PhotoRecord` in `core`) and `coco.json`, a COCO keypoint file over everything (category `arrow` with the keypoint `entry`, category `face` with the centre and the tapped edge points). Pixel coordinates refer to the **upright** image, i.e. after the EXIF orientation is applied; `rotation` in the record says by how much the camera file was turned.

Useful fields per arrow: `ring` (confirmed), `ringAuto` (what the app computed), `moved`, `tool` (`stylus`/`finger`), `mmX`/`mmY` (millimetres from the face centre). Per photo: face type, environment, sight shot count, distance, exposure data and device.

## Training in the browser (Linux, Chromium, local GPU)

`webui.py` is a small local web page that runs the whole chain on your own machine: upload the export, train on your GPU, judge the result, download the model. Nothing is sent anywhere; the server only listens on `127.0.0.1`.

```sh
cd training
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt        # ultralytics pulls in PyTorch
python3 webui.py                        # then open http://localhost:8000 in Chromium
```

The page shows which device PyTorch found. It should say `cuda` and your card's name:

- **NVIDIA:** the driver has to be installed (`nvidia-smi` works); the PyTorch wheel that `pip` installs brings its own CUDA libraries.
- **AMD:** install the ROCm build of PyTorch first (`pip install torch --index-url https://download.pytorch.org/whl/rocm6.2`), then the requirements; the page reports the card as `cuda` too, that is how ROCm presents itself.
- **CPU only:** works, but a run of 120 epochs takes hours instead of minutes.

On the page: upload the ZIP from *Settings → Photo scoring → Export*, choose image size (640 is the default; 320 for a smaller, faster model), epochs, model size (`n` is enough to start) and press *Start training*. The loss curve and the log update while it runs; *Stop* aborts the run (nothing is exported, start a new run with fewer epochs instead). Every run trains both models one after the other (the epochs apply to each). When it finishes, the results table shows, in archery terms, how many arrows were found and missed, the false alarms, the median entry-point error in millimetres, how often the ring was right, and for the face finder how many faces were found, how far its centre was off and how many of your arrows keep their ring when scored with its face instead of yours; plus a preview of the validation photos with your marks. *Download model (.zip)* gives `arrows.tflite`, `face.tflite` and `model.json` with these numbers. Copy the ZIP to the phone and import it under *Settings → Photo scoring → Detection models*; the app shows the evaluation on the settings card, and from then on *Score from photo* finds the face and the arrows itself. Keep *Collect training data* on: the photos you correct are the training data of the next run.

The trainer's own numbers are the ones to look at. Ultralytics' mAP values printed in the console come from its validation split, which with a few photos is one picture and says nothing. The int8 quantisation is calibrated on all photos of the run, not only the validation ones.

Several exports can be uploaded, for example from club mates' phones, and ticked for one run: the records are merged, photo ids and session keys get the export's name as prefix, and the results table lists the hit rate per export when the validation photos come from more than one. `prepare_dataset.py` and `train.sh` take several ZIPs on the command line the same way.

`python3 webui.py --mock` trains stand-in models in seconds, for checking the page without a GPU. `train.sh` does the same as the page from the command line (`IMGSZ` and `EPOCHS` as environment variables) and writes `model.zip`.

The export goes through Ultralytics' LiteRT path (`format=litert`, `quantize=int8`) where available and falls back to the older `format=tflite`, `int8=True` on older versions. LiteRT exports keep the PyTorch input layout `[1, 3, N, N]`; the app takes both layouts.

Start with about 50 ends; expect usable results from roughly 150 to 200 ends spread over both environments. `prepare_dataset.py` splits by session so all ends of one session land on the same side.

## Small devices

The export is int8-quantised with a chosen input size (`IMGSZ=320 ./train.sh <zip>` for a smaller model). That is what TensorFlow Lite for Microcontrollers needs. Whether a model of this class is fast and accurate enough on an ESP32-S3 has to be measured; the phone runs it without trouble.

## Licence note for public datasets

If public images are mixed in (for example CC BY 4.0 sets from Roboflow Universe), keep their attribution next to the exported model.
