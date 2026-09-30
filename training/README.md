# Training the arrow detector

The app collects training data when *Settings → Photo scoring → Collect training data* is on: every scored photo with the marked face and arrows. This folder turns such an export into a model that later proposes the marks in the app.

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

On the page: upload the ZIP from *Settings → Photo scoring → Export*, choose image size (640 is the default; 320 for a smaller, faster model), epochs, model size (`n` is enough to start) and press *Start training*. The loss curve and the log update while it runs; *Stop* aborts the run (nothing is exported, start a new run with fewer epochs instead). When it finishes, the results table shows, in archery terms, how many arrows were found and missed, the false alarms, the median entry-point error in millimetres and how often the ring was right, plus a preview of the validation photos with the marks (green = yours, blue = model). *Download .tflite* gives the int8 model. Copy it to the phone and import it under *Settings → Photo scoring → Detection model*; from then on *Detect* on the photo page is active.

`python3 webui.py --mock` trains a stand-in model in seconds, for checking the page without a GPU. `train.sh` does the same as the page from the command line.

Start with about 50 ends; expect usable results from roughly 150 to 200 ends spread over both environments. `prepare_dataset.py` splits by session so all ends of one session land on the same side.

## Small devices

The export is int8-quantised with a chosen input size (`train.sh <zip> 320` for a smaller model). That is what TensorFlow Lite for Microcontrollers needs. Whether a model of this class is fast and accurate enough on an ESP32-S3 has to be measured; the phone runs it without trouble.

## Licence note for public datasets

If public images are mixed in (for example CC BY 4.0 sets from Roboflow Universe), keep their attribution next to the exported model.
