# Training the arrow detector

The app collects training data when *Settings → Photo scoring → Collect training data* is on: every scored photo with the marked face and arrows. This folder turns such an export into a model that later proposes the marks in the app.

## Data format

The export ZIP holds `images/<id>.jpg`, `records/<id>.json` (one per photo, see `PhotoRecord` in `core`) and `coco.json`, a COCO keypoint file over everything (category `arrow` with the keypoint `entry`, category `face` with the centre and the tapped edge points). Pixel coordinates refer to the **upright** image, i.e. after the EXIF orientation is applied; `rotation` in the record says by how much the camera file was turned.

Useful fields per arrow: `ring` (confirmed), `ringAuto` (what the app computed), `moved`, `tool` (`stylus`/`finger`), `mmX`/`mmY` (millimetres from the face centre). Per photo: face type, environment, sight shot count, distance, exposure data and device.

## Steps

```sh
pip install ultralytics pillow
./train.sh uv-sight-training-2026-10-01.zip        # arrow dataset, training, int8 TFLite export
python3 prepare_dataset.py export.zip --faces --out faces   # optional: a face detector dataset
```

`prepare_dataset.py` splits by session so all ends of one session land on the same side. Start with about 50 ends; expect usable results from roughly 150 to 200 ends spread over both environments.

## Small devices

The export is int8-quantised with a chosen input size (`train.sh <zip> 320` for a smaller model). That is what TensorFlow Lite for Microcontrollers needs. Whether a model of this class is fast and accurate enough on an ESP32-S3 has to be measured; the phone runs it without trouble.

## Licence note for public datasets

If public images are mixed in (for example CC BY 4.0 sets from Roboflow Universe), keep their attribution next to the exported model.
