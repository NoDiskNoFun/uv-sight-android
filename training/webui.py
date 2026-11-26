#!/usr/bin/env python3
"""Nocked trainer: a local web page that trains the arrow detector on your own GPU.

    pip install -r requirements.txt
    python3 webui.py            # then open http://localhost:8000

Upload the export ZIP from the app (Settings -> Photo scoring -> Export), pick the
parameters, start. Two models are trained: the arrow detector (entry point per arrow) and
the face finder (centre and four edge points of the blue ring). The page shows the
progress, evaluates the result in archery terms (arrows found, entry-point error in
millimetres, rings right, face found) and offers a ZIP with both int8 TFLite files and
the evaluation (model.json) for import into the app.

--mock trains a stand-in model in seconds (for checking the page without a GPU).

With one GPU the two models train one after the other; with two or more, the arrow detector trains on
the first and the face finder on the second at the same time. Each model trains in a process of its
own, which reports its epochs through a file in the run folder.
"""
import argparse
import copy
import json
import math
import multiprocessing
import os
import platform
import random
import shutil
import sys
import threading
import time
import traceback
import urllib.parse
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import facegeom              # noqa: E402
import prepare_dataset       # noqa: E402

ROOT = Path(__file__).parent
DATA = Path(os.environ.get("NOCKED_DATA") or ROOT / "data").resolve()    # uploaded exports
RUNS = Path(os.environ.get("NOCKED_RUNS") or ROOT / "runs").resolve()    # one folder per run (absolute: Ultralytics
                                                                          # moves a relative project under runs/pose/)
MATCH_PX = 40.0              # a prediction within this many pixels of a mark counts as found
# Linux on ARM64: no LiteRT converter exists there, the TFLite export goes through TensorFlow instead
ARM64 = sys.platform.startswith("linux") and platform.machine().lower() in ("aarch64", "arm64")
# NOCKED_EXPORT=tensorflow takes that path elsewhere too (as a fallback, or to check the ARM64 path on a PC)
TF_EXPORT = ARM64 or os.environ.get("NOCKED_EXPORT", "").lower() == "tensorflow"
STAGES = ("arrows", "face")

LOCK = threading.Lock()
JOB = None                   # the running/last job, see Job
MOCK_GPUS = None             # --mock-gpus: pretend this many CUDA devices exist


class Job:
    def __init__(self, params):
        self.params = params
        self.name = params["name"]
        self.state = "running"     # running / done / failed / stopped
        self.epochs = int(params["epochs"])
        self.parallel = False      # both models at the same time, each on its own GPU
        # per model: {state: waiting/training/exporting/done/failed/stopped, device, epoch, metrics: [{epoch, loss, ...}]}
        self.stages = {}
        self.log = []
        self.error = None
        self.started = time.time()
        self.stop = False
        self.result = None

    def say(self, text):
        self.log.append(f"{time.strftime('%H:%M:%S')} {text}")
        del self.log[:-300]

    def snapshot(self):   # a copy: the training thread keeps changing the job while the page reads it
        return copy.deepcopy({k: getattr(self, k) for k in ("name", "state", "parallel", "stages", "epochs", "log", "error", "started", "result", "params")})


# ----------------------------------------------------------------------------- hardware
def gpu_info():
    if MOCK_GPUS:
        return {"torch": "mock", "device": "cuda", "count": MOCK_GPUS, "names": [f"Mock GPU {i}" for i in range(MOCK_GPUS)],
                "name": f"{MOCK_GPUS}× Mock GPU"}
    try:
        import torch
        if torch.cuda.is_available():
            n = torch.cuda.device_count()
            names = [torch.cuda.get_device_name(i) for i in range(n)]
            name = names[0] if n == 1 else f"{n} GPUs: " + ", ".join(f"{i}: {x}" for i, x in enumerate(names))
            return {"torch": torch.__version__, "device": "cuda", "count": n, "names": names, "name": name}
        if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
            return {"torch": torch.__version__, "device": "mps", "count": 1, "names": ["Apple GPU"], "name": "Apple GPU"}
        return {"torch": torch.__version__, "device": "cpu", "count": 0, "names": [], "name": "CPU only (no CUDA/ROCm device found)"}
    except Exception as e:  # torch missing
        return {"torch": None, "device": "none", "count": 0, "names": [], "name": f"PyTorch not installed ({e.__class__.__name__})"}


def plan_devices(gi, stages):
    """Device per model and whether they train at the same time: with two or more CUDA devices the arrow detector
    takes the first and the face finder the second; otherwise both take turns on the one device there is."""
    if gi["device"] == "cuda" and gi.get("count", 1) >= 2 and len(stages) >= 2:
        return {s: i for i, s in enumerate(stages)}, True
    dev = 0 if gi["device"] == "cuda" else gi["device"] if gi["device"] == "mps" else "cpu"
    return {s: dev for s in stages}, False


def device_label(dev):
    return f"GPU {dev}" if isinstance(dev, int) else str(dev).upper()


def dataloader_workers(parallel):
    """Ultralytics takes up to 8 loader processes per training; two trainings at once on a 12-core ARM board
    would otherwise ask for 16 and fight over the cores."""
    n = os.cpu_count() or 4
    return max(1, min(8, (n // (2 if parallel else 1)) - 1))


def ultralytics_available():
    try:
        import ultralytics  # noqa: F401
        return True
    except Exception:
        return False


def ultralytics_version():
    from ultralytics import __version__ as v
    return tuple(int(x) for x in v.split(".")[:2])


def export_missing():
    """Names of the packages the TFLite export needs that this Python lacks (Ultralytics 8.4 uses LiteRT; older
    versions bring their own converter). Checked before a run starts, so the hours of training are not wasted
    on an export that fails and that Ultralytics cannot repair by installing into a system Python."""
    try:
        major, minor = ultralytics_version()
    except Exception:
        return []
    if TF_EXPORT:   # TensorFlow path (format=saved_model), whatever the Ultralytics version
        need = (("tensorflow", "tensorflow"), ("tf_keras", "tf_keras"), ("onnx2tf", "onnx2tf"), ("onnx", "onnx"),
                ("onnxruntime", "onnxruntime"), ("onnx_graphsurgeon", "onnx_graphsurgeon"), ("sng4onnx", "sng4onnx"))
    elif (major, minor) < (8, 4):
        return []
    else:
        need = (("litert_torch", "litert-torch"), ("ai_edge_litert", "ai-edge-litert"))
    missing = []
    for mod, pkg in need:
        try:
            __import__(mod)
        except Exception:
            missing.append(pkg)
    return missing


def env_note():
    """One line about the Python in use, so a run outside the virtual environment is spotted at once."""
    venv = sys.prefix != getattr(sys, "base_prefix", sys.prefix)
    docker = Path("/.dockerenv").exists()
    return f"Python {sys.version.split()[0]} at {sys.executable}" + (" (Docker)" if docker else "" if venv else " (no virtual environment)")


# ----------------------------------------------------------------------------- evaluation
def evaluate(val_recs, predict, predict_face=None, truth="hand"):
    """predict(record, image_path) -> [(x, y, conf)] in upright pixels;
    predict_face(record, image_path) -> [(conf, [centre, top, right, bottom, left])], best first, [] for none.
    Returns archery metrics: arrows found, entry error, rings right; faces found (every marked face
    counts, so a spot face counts three), centre error and how often the user's arrows get the same
    ring with the model's face as with the user's.
    truth="hand": only hand-made marks count, the honest measurement. truth="accepted": only the parts an
    earlier model proposed and the archer accepted untouched count; that is agreement with the old model,
    not accuracy, and serves as a check that a new model has not fallen behind where the old one was right."""
    found = total = false = ring_ok = ring_n = 0
    px_err = []; mm_err = []
    face_n = face_found = face_ring_ok = face_ring_n = 0; face_mm = []
    per_image = []
    for rec, img in val_recs:
        # which parts of the record are the truth here (see truth above)
        arrows_hand = prepare_dataset.hand_marked(rec, "arrows") == (truth == "hand") and bool(rec.get("arrows"))
        face_hand = prepare_dataset.hand_marked(rec, "face") == (truth == "hand")
        preds = predict(rec, img) if arrows_hand else []
        face_hit = None
        g_users = facegeom.geometries_of_record(rec)
        if predict_face and g_users and face_hand:
            # every marked face wants a prediction whose centre lies inside it (within the blue ring);
            # the arrows nearest to that face are then re-scored with the predicted geometry
            face_hit = True
            preds_f = [(c, k, facegeom.fit(k[0], k[1:5])) for c, k in (predict_face(rec, img) or []) if len(k) >= 5]
            preds_f = [(c, k, g) for c, k, g in preds_f if g is not None]
            dia, min_ring = facegeom.FACES.get(rec["face"], (rec.get("faceDiameterMm", 400), 1))
            for gu in g_users:
                face_n += 1
                hit = None
                for c, k, gp in preds_f:
                    if math.hypot(*gu.to_face(k[0])) < 1.0 and (hit is None or c > hit[0]):
                        hit = (c, k, gp)
                if hit is None:
                    face_hit = False
                    continue
                face_found += 1
                face_mm.append(math.hypot(*facegeom.mm_from_center(gu.to_face(hit[1][0]), dia)))
                for a in rec["arrows"]:
                    if facegeom.nearest_geometry(g_users, (a["px"], a["py"])) is not gu:
                        continue
                    face_ring_n += 1
                    if facegeom.ring(hit[2].to_face((a["px"], a["py"])), dia, min_ring, rec.get("arrowMm", 6.0)) == a["ring"]:
                        face_ring_ok += 1
        marks = [(a["px"], a["py"], a) for a in rec["arrows"]] if arrows_hand else []
        total += len(marks)
        used = set(); matched = []
        for mx, my, a in marks:
            best = None
            for i, (px, py, conf) in enumerate(preds):
                if i in used:
                    continue
                d = math.hypot(px - mx, py - my)
                if d <= MATCH_PX and (best is None or d < best[0]):
                    best = (d, i)
            if best:
                used.add(best[1]); found += 1; px_err.append(best[0])
                matched.append((a, preds[best[1]]))
        false += len(preds) - len(used)
        gs = facegeom.geometries_of_record(rec)
        if gs:
            dia, min_ring = facegeom.FACES.get(rec["face"], (rec.get("faceDiameterMm", 400), 1))
            for a, (px, py, conf) in matched:
                g = facegeom.nearest_geometry(gs, (a["px"], a["py"]))     # the face (spot) this arrow sits on
                um = g.to_face((a["px"], a["py"])); up = g.to_face((px, py))
                m1 = facegeom.mm_from_center(um, dia); m2 = facegeom.mm_from_center(up, dia)
                mm_err.append(math.hypot(m1[0] - m2[0], m1[1] - m2[1]))
                ring_n += 1
                if facegeom.ring(up, dia, min_ring, rec.get("arrowMm", 6.0)) == a["ring"]:
                    ring_ok += 1
        per_image.append({"id": rec["id"], "export": rec.get("_export"), "marks": len(marks), "found": len(used), "false": len(preds) - len(used), "face": face_hit})
    per_export = {}
    for pi in per_image:
        e = per_export.setdefault(pi["export"] or "", {"photos": 0, "arrows": 0, "found": 0, "false_alarms": 0})
        e["photos"] += 1; e["arrows"] += pi["marks"]; e["found"] += pi["found"]; e["false_alarms"] += pi["false"]
    return {
        "per_export": per_export,
        "images": len(val_recs), "arrows": total, "found": found, "false_alarms": false,
        "recall": round(found / total, 3) if total else None,
        "precision": round(found / (found + false), 3) if (found + false) else None,
        "px_error_mean": round(sum(px_err) / len(px_err), 1) if px_err else None,
        "mm_error_mean": round(sum(mm_err) / len(mm_err), 1) if mm_err else None,
        "mm_error_median": round(sorted(mm_err)[len(mm_err) // 2], 1) if mm_err else None,
        "ring_accuracy": round(ring_ok / ring_n, 3) if ring_n else None,
        "face_photos": face_n, "face_found": face_found,
        "face_center_mm_median": round(sorted(face_mm)[len(face_mm) // 2], 1) if face_mm else None,
        "face_ring_accuracy": round(face_ring_ok / face_ring_n, 3) if face_ring_n else None,
        "per_image": per_image,
    }


# ----------------------------------------------------------------------------- training
def run_job(job, mock):
    p = job.params
    run = RUNS / job.name
    try:
        run.mkdir(parents=True, exist_ok=True)
        (run / "STOP").unlink(missing_ok=True)
        job.say("unpacking export and preparing the dataset")
        exports = p.get("exports") or [p["export"]]
        recs = prepare_dataset.load_many([DATA / n for n in exports], run / "export")
        if not recs:
            raise RuntimeError("no records in the export")
        if len(exports) > 1:
            job.say(f"{len(exports)} exports merged: " + ", ".join(exports))
        n_arrow_hand = len(prepare_dataset.for_arrows(recs)); n_face_hand = len(prepare_dataset.with_face(recs))
        job.say(f"{len(recs)} photos: arrows marked by hand on {n_arrow_hand}, face on {n_face_hand} (what a model proposed in the app is left out)")
        if n_arrow_hand == 0:
            raise RuntimeError("no photo with hand-marked arrows: switch the arrow detector off in the app to collect some")
        # only photos with a hand-marked part train or validate; the ones accepted untouched are the regression check below
        usable = [x for x in recs if prepare_dataset.hand_marked(x[0], "arrows") or prepare_dataset.hand_marked(x[0], "face")]
        train, val = prepare_dataset.split_by_session(usable, float(p["val"]))
        if not val:
            # a single session (or a single photo): the trainer needs a validation set, so the
            # training photos serve as one; the numbers below are then optimistic
            val = train
            job.say("only one session in the export: validating on the training photos, the evaluation is optimistic")
        ds = run / "dataset"
        n_tr = prepare_dataset.write_split(train, ds, "train", False)
        n_va = prepare_dataset.write_split(val, ds, "val", False)
        if n_tr == 0:
            raise RuntimeError("no hand-marked arrows in the training split; use a smaller validation share or collect more photos")
        (ds / "data.yaml").write_text(prepare_dataset.data_yaml(ds, False))
        job.say(f"{len(train)} training photos ({n_tr} arrows), {len(val)} validation photos ({n_va} arrows)")
        # the face finder learns from the photos whose marking gives a geometry
        dsf = run / "dataset_face"
        f_tr = prepare_dataset.write_split(train, dsf, "train", True)
        f_va = prepare_dataset.write_split(val, dsf, "val", True)
        (dsf / "data.yaml").write_text(prepare_dataset.data_yaml(dsf, True))
        train_face = f_tr > 0 and f_va > 0
        job.say(f"face marked on {f_tr} training and {f_va} validation photos" + ("" if train_face else ": no face model this run"))
        imgsz = int(p["imgsz"])
        stages = ["arrows", "face"] if train_face else ["arrows"]
        devices, parallel = plan_devices(gpu_info(), stages)
        job.parallel = parallel
        job.stages = {s: {"state": "waiting", "device": device_label(devices[s]), "epoch": 0, "metrics": []} for s in stages}
        job.say(("both models at the same time: " if parallel else "one model after the other: ")
                + ", ".join(f"{s} on {device_label(devices[s])}" for s in stages))
        if not mock:
            fetch_weights(job, p.get("size", "n"))
        datasets = {"arrows": ds, "face": dsf}
        workers = dataloader_workers(parallel)
        jobs = [{"stage": s, "run": str(run), "ds": str(datasets[s]), "imgsz": imgsz, "epochs": job.epochs, "batch": int(p["batch"]),
                 "size": p.get("size", "n"), "precision": p.get("precision", "int8"), "device": devices[s], "workers": workers, "mock": mock}
                for s in stages]
        run_stages(job, run, [jobs] if parallel else [[j] for j in jobs])
        if job.stop:
            job.state = "stopped"; job.say("stopped"); return
        failed = [s for s in stages if job.stages[s]["state"] != "done"]
        if "arrows" in failed:
            raise RuntimeError("the arrow detector did not finish: " + (job.stages["arrows"].get("error") or "see the log"))
        if failed:   # the face finder failed but the arrow detector is fine: keep the run, without a face model
            job.say("the face finder did not finish, the model has the arrow detector only")
            (run / "face.tflite").unlink(missing_ok=True)
        job.say("evaluating on the validation photos")
        if mock:
            predict, predict_face = mock_predictors(train_face and "face" not in failed)
        else:
            predict = make_predict(run / "train_arrows" / "weights" / "best.pt", imgsz, "arrows")
            predict_face = make_predict(run / "train_face" / "weights" / "best.pt", imgsz, "face") if train_face and "face" not in failed else None
        ev = evaluate(val, predict, predict_face)
        ev["train_photos"] = len(train); ev["val_photos"] = len(val)
        # the regression check: the photos whose proposals the archer accepted untouched never train or validate
        # (they carry the old model's points); the new model should still agree with them where the old one was right
        accepted = [x for x in recs if not prepare_dataset.hand_marked(x[0], "arrows") or not prepare_dataset.hand_marked(x[0], "face")]
        if accepted:
            job.say(f"agreement check on {len(accepted)} photos with accepted proposals")
            ag = evaluate(accepted, predict, predict_face, truth="accepted")
            ev["agreement"] = {k: ag.get(k) for k in ("images", "arrows", "found", "false_alarms", "recall", "ring_accuracy", "face_photos", "face_found", "face_ring_accuracy")}
        has_face = (run / "face.tflite").exists()
        info = {"name": job.name, "imgsz": imgsz, "task": "pose", "trained": time.time(), "train_photos": len(train), "val_photos": len(val),
                "size": p.get("size", "n"), "precision": p.get("precision", "int8"),
                "arrows": {"file": "arrows.tflite", "kpt": ["entry"]},
                "face": {"file": "face.tflite", "kpt": facegeom.FACE_KPT} if has_face else None,
                "eval": {k: v for k, v in ev.items() if k != "per_image"}}
        (run / "model.json").write_text(json.dumps(info, indent=1))
        with zipfile.ZipFile(run / "model.zip", "w", zipfile.ZIP_DEFLATED) as z:
            z.write(run / "arrows.tflite", "arrows.tflite")
            if has_face:
                z.write(run / "face.tflite", "face.tflite")
            z.write(run / "model.json", "model.json")
        last = job.stages["arrows"]["metrics"]
        summary = {"name": job.name, "params": p, "finished": time.time(), "duration_s": round(time.time() - job.started),
                   "train_photos": len(train), "val_photos": len(val), "eval": ev, "model": "model.zip", "face_model": has_face, "mock": mock,
                   "parallel": parallel, "devices": {s: device_label(devices[s]) for s in stages},
                   "final_metrics": last[-1] if last else None}
        (run / "summary.json").write_text(json.dumps(summary, indent=1))
        job.result = summary
        job.state = "done"
        job.say("done")
    except Exception as e:
        job.stop = True   # a stage process still running is told to end
        (run / "STOP").touch() if run.exists() else None
        job.state = "failed"; job.error = f"{e}"; job.say("failed: " + "".join(traceback.format_exception_only(type(e), e)).strip())


def fetch_weights(job, size):
    """Downloads the pretrained weights once, before two processes would race for the same file."""
    name = f"yolo11{size}-pose.pt"
    if not Path(name).exists():
        job.say(f"fetching the pretrained {name}")
        from ultralytics import YOLO
        YOLO(name)


def run_stages(job, run, waves):
    """Starts each wave of stage processes together and waits for it before the next: [[a, b]] trains a and b at
    the same time, [[a], [b]] one after the other. The processes report through run/<stage>.events.jsonl;
    Stop on the page creates run/STOP, which they look for after every epoch."""
    ctx = multiprocessing.get_context("spawn")   # a fresh interpreter per model: no CUDA or Ultralytics state is shared
    export_lock = ctx.Lock()                     # one TFLite conversion at a time: they share files and are heavy on memory
    for wave in waves:
        if job.stop:
            return
        procs = {}
        for cfg in wave:
            ev = run / f"{cfg['stage']}.events.jsonl"
            ev.unlink(missing_ok=True)
            pr = ctx.Process(target=stage_main, args=(cfg, export_lock), name=f"train-{cfg['stage']}", daemon=False)
            pr.start()
            procs[cfg["stage"]] = [pr, ev, 0]
            job.stages[cfg["stage"]]["state"] = "training"
        while procs:
            if job.stop and not (run / "STOP").exists():
                (run / "STOP").touch()
            for stage, entry in list(procs.items()):
                pr, ev, pos = entry
                alive = pr.is_alive()
                entry[2] = read_events(job, stage, ev, pos)
                if not alive:
                    pr.join()
                    st = job.stages[stage]
                    if st["state"] not in ("done", "stopped", "failed"):
                        st["state"] = "failed"; st["error"] = st.get("error") or f"process ended with code {pr.exitcode}"
                    if st["state"] == "failed":
                        job.say(f"{stage} failed: {st.get('error')}")
                        if stage == "arrows":   # without the arrow detector there is no model: end the face finder too
                            (run / "STOP").touch()
                    del procs[stage]
            time.sleep(0.5)
        if any(job.stages[c["stage"]]["state"] != "done" for c in wave if c["stage"] == "arrows"):
            return   # no point in the face finder after a failed arrow detector


def read_events(job, stage, path, pos):
    """Takes the new lines of a stage's event file into the job; returns the new read position."""
    if not path.exists():
        return pos
    with path.open() as f:
        f.seek(pos)
        while True:
            line = f.readline()
            if not line.endswith("\n"):   # nothing more, or a line still being written
                break
            pos = f.tell()
            try:
                e = json.loads(line)
            except ValueError:
                continue
            with LOCK:
                st = job.stages[stage]
                if "metrics" in e:
                    st["metrics"].append(e["metrics"]); st["epoch"] = e["metrics"]["epoch"]
                if "state" in e:
                    st["state"] = e["state"]
                if "error" in e:
                    st["error"] = e["error"]
                if "say" in e:
                    job.say(f"{stage}: {e['say']}")
    return pos


def finite(v, digits=4):
    """A metric as a rounded float, or None when it is NaN or infinite (Ultralytics reports NaN for some metrics in
    the first epochs; JSON has no NaN, and the browser refuses the whole state when it meets one)."""
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return round(f, digits) if math.isfinite(f) else None


def json_safe(o):
    """o with every NaN or infinite float replaced by None, so json.dumps writes valid JSON."""
    if isinstance(o, float):
        return o if math.isfinite(o) else None
    if isinstance(o, dict):
        return {k: json_safe(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [json_safe(v) for v in o]
    return o


class Events:
    """The stage process's side of the event file: one JSON object per line."""

    def __init__(self, path):
        self.path = Path(path)

    def emit(self, **kw):
        with self.path.open("a") as f:
            f.write(json.dumps(kw) + "\n")

    def say(self, text):
        self.emit(say=text)


def stage_main(cfg, export_lock):
    """Entry of a stage process: trains one pose model ("arrows": 1 keypoint, "face": 5) on cfg["device"] and exports
    it as run/<stage>.tflite."""
    run = Path(cfg["run"]); stage = cfg["stage"]
    ev = Events(run / f"{stage}.events.jsonl")
    try:
        if cfg["mock"]:
            stage_mock(cfg, run, ev, export_lock)
        else:
            stage_train(cfg, run, ev, export_lock)
    except BaseException as e:   # noqa: BLE001  report everything, also KeyboardInterrupt, then fail the process
        ev.emit(state="failed", error=f"{e.__class__.__name__}: {str(e)[:300]}")
        traceback.print_exc()
        sys.exit(1)


def stage_mock(cfg, run, ev, export_lock):
    ev.say(f"--- {cfg['stage']} model (mock) on {device_label(cfg['device'])} ---")
    for ep in range(1, cfg["epochs"] + 1):
        if (run / "STOP").exists():
            ev.emit(state="stopped"); return
        time.sleep(0.05)
        ev.emit(metrics={"epoch": ep, "loss": round(3.0 / (1 + ep * 0.3), 3), "mAP50": round(min(0.95, 0.2 + ep * 0.04), 3)})
        if ep % 5 == 0 or ep == cfg["epochs"]:
            ev.say(f"epoch {ep}/{cfg['epochs']}")
    ev.emit(state="exporting")
    with export_lock:
        time.sleep(0.3)
        (run / f"{cfg['stage']}.tflite").write_bytes(b"TFL3-mock-" + str(time.time()).encode())
    ev.emit(state="done")


def stage_train(cfg, run, ev, export_lock):
    from ultralytics import YOLO
    stage = cfg["stage"]
    ev.say(f"--- {stage} model on {device_label(cfg['device'])} ---")
    model = YOLO(f"yolo11{cfg['size']}-pose.pt")
    stop_file = run / "STOP"

    def on_fit_epoch_end(trainer):
        if stop_file.exists():
            trainer.stop = True
        if trainer.epoch + 1 > cfg["epochs"]:   # Ultralytics calls this once more for its final validation
            return
        m = {"epoch": trainer.epoch + 1}
        for k, v in (trainer.metrics or {}).items():
            m[k.replace("metrics/", "").replace("(B)", "").replace("(P)", "_kpt")] = finite(v)
        try:
            m["loss"] = finite(sum(trainer.tloss) if hasattr(trainer.tloss, "__iter__") else trainer.tloss)
        except Exception:
            pass
        ev.emit(metrics=m)
        ev.say(f"epoch {m['epoch']}/{cfg['epochs']} " + " ".join(f"{k} {v}" for k, v in m.items() if k != "epoch")[:160])
    model.add_callback("on_fit_epoch_end", on_fit_epoch_end)
    model.train(data=str(Path(cfg["ds"]) / "data.yaml"), imgsz=cfg["imgsz"], epochs=cfg["epochs"], batch=cfg["batch"], project=str(run),
                name=f"train_{stage}", exist_ok=True, device=cfg["device"], workers=cfg["workers"], verbose=False, plots=False)
    if stop_file.exists():
        ev.emit(state="stopped"); return
    ev.emit(state="exporting")
    if not export_lock.acquire(block=False):
        ev.say("waiting for the other model's export to finish")
        export_lock.acquire()
    ev.say(f"exporting {cfg['precision']} TFLite")
    best = run / f"train_{stage}" / "weights" / "best.pt"
    try:
        export_model(ev.say, YOLO(str(best)), cfg["imgsz"], Path(cfg["ds"]), run / f"{stage}.tflite", cfg["precision"])
    finally:
        export_lock.release()
    ev.emit(state="done")


def mock_predictors(train_face):
    rnd = random.Random(1)

    def predict(rec, img):
        out = []
        for a in rec["arrows"]:
            if rnd.random() < 0.9:
                out.append((a["px"] + rnd.gauss(0, 6), a["py"] + rnd.gauss(0, 6), 0.8))
        if rnd.random() < 0.2:
            out.append((rnd.uniform(0, rec["width"]), rnd.uniform(0, rec["height"]), 0.5))
        return out

    def predict_face(rec, img):
        return [(0.85, [(x + rnd.gauss(0, 4), y + rnd.gauss(0, 4)) for x, y in facegeom.face_keypoints(g)])
                for g in facegeom.geometries_of_record(rec) if rnd.random() >= 0.1]
    return predict, (predict_face if train_face else None)


def pick_tflite(folder, int8):
    """The TFLite file of an export folder. LiteRT names its int8 file *int8*; onnx2tf (the TensorFlow path) writes
    *_integer_quant.tflite (int8 inside, float input and output, calibrated), *_full_integer_quant.tflite (int8 also
    at input and output), *_int8.tflite (int8 weights only, float activations) and *_float32/16.tflite."""
    names = sorted(folder.glob("*.tflite"))
    if int8:
        order = [lambda n: n.endswith("_integer_quant.tflite") and "full_integer" not in n and "int16" not in n,
                 lambda n: "full_integer_quant" in n and "int16" not in n,
                 lambda n: "int8" in n]
    else:
        order = [lambda n: "float32" in n,
                 lambda n: "int8" not in n and "w8a" not in n and "quant" not in n and "float16" not in n]
    for test in order:
        hit = [f for f in names if test(f.name)]
        if hit:
            return hit[0]
    if names:
        return names[0]
    raise RuntimeError(f"no .tflite in {folder}")


def export_model(say, trained, imgsz, ds, dest, precision="int8"):
    """TFLite of a trained model. int8 is calibrated on all photos of the run (not only the few validation ones) and
    is the smallest and fastest; float32 keeps the keypoints a little more exact at a larger model (the phone's GPU
    delegate runs it as float16; LiteRT has no separate float16 file). Ultralytics 8.4 calls the format "litert"
    and takes quantize=8 or 32; older versions take tflite with int8=True. On Linux ARM64 there is no LiteRT
    converter: the TFLite comes from TensorFlow through onnx2tf (format saved_model), in NHWC layout, which the app
    takes as well."""
    int8 = precision == "int8"
    calib = ds / "calib.yaml"
    calib.write_text((ds / "data.yaml").read_text().replace("val: images/val", "val: [images/train, images/val]"))
    kw = {"imgsz": imgsz}
    if int8: kw["data"] = str(calib)
    if TF_EXPORT:
        say("TFLite through TensorFlow (onnx2tf)" + (": LiteRT has no Linux ARM64 build" if ARM64 else ""))
        # on the CPU: the GPU would ask for onnxruntime-gpu, which has no ARM64 wheel on PyPI
        q = ({"quantize": 8} if ultralytics_version() >= (8, 4) else {"int8": True}) if int8 else {}
        out = trained.export(format="saved_model", device="cpu", **q, **kw)
    else:
        try:
            out = trained.export(format="litert", quantize=8 if int8 else 32, **kw)
        except Exception as e:
            say(f"litert export not available ({e.__class__.__name__}: {str(e)[:120]}), using the tflite export of older Ultralytics")
            out = trained.export(format="tflite", int8=int8, **kw)
    src = Path(out[0] if isinstance(out, (tuple, list)) else out)
    if src.is_dir():
        src = pick_tflite(src, int8)
    shutil.copy(src, dest)
    say(f"{dest.name}: {dest.stat().st_size // 1024} kB (from {src.name})")


def make_predict(best, imgsz, stage):
    """The prediction function the evaluation uses, from the trained PyTorch weights (in this process, on the first GPU)."""
    from ultralytics import YOLO
    trained = YOLO(str(best))

    def predict(rec, img):
        res = trained.predict(str(img), imgsz=imgsz, conf=0.25, verbose=False)[0]
        out = []
        if res.keypoints is not None and res.boxes is not None:
            kp = res.keypoints.xy.cpu().numpy(); conf = res.boxes.conf.cpu().numpy()
            for i in range(len(conf)):
                out.append((float(kp[i][0][0]), float(kp[i][0][1]), float(conf[i])))
        return out

    def predict_face(rec, img):
        res = trained.predict(str(img), imgsz=imgsz, conf=0.25, verbose=False)[0]
        if res.keypoints is None or res.boxes is None or len(res.boxes.conf) == 0:
            return []
        conf = res.boxes.conf.cpu().numpy(); kps = res.keypoints.xy.cpu().numpy()
        out = [(float(conf[i]), [(float(x), float(y)) for x, y in kps[i]]) for i in range(len(conf))]
        return sorted(out, key=lambda t: -t[0])
    return predict_face if stage == "face" else predict


# ----------------------------------------------------------------------------- state
def runs_list():
    out = []
    for d in sorted(RUNS.glob("*/summary.json")):
        try:
            s = json.loads(d.read_text()); s.pop("eval", None) if False else None
            out.append({"name": s["name"], "finished": s["finished"], "duration_s": s["duration_s"], "train_photos": s["train_photos"],
                        "val_photos": s["val_photos"], "eval": {k: v for k, v in s["eval"].items() if k != "per_image"},
                        "params": s["params"], "exports": s["params"].get("exports") or [s["params"].get("export")],
                        "mock": s.get("mock", False), "parallel": s.get("parallel", False), "model": (d.parent / "model.zip").exists(), "face_model": s.get("face_model", False)})
        except Exception:
            pass
    return out


def exports_list():
    return [{"name": f.name, "size": f.stat().st_size, "mtime": f.stat().st_mtime} for f in sorted(DATA.glob("*.zip"))]


def preview(run, index):
    exp = RUNS / run / "export"
    recs = prepare_dataset.load_many(sorted(d for d in exp.iterdir() if d.is_dir()), exp) if exp.exists() else []
    if not recs:
        return {"error": "no records"}
    train, val = prepare_dataset.split_by_session(recs, float(json.loads((RUNS / run / "summary.json").read_text())["params"]["val"]))
    pool = val or train
    index = max(0, min(index, len(pool) - 1))
    rec, img = pool[index]
    summary = json.loads((RUNS / run / "summary.json").read_text())
    per = {p["id"]: p for p in summary["eval"].get("per_image", [])}
    return {"index": index, "count": len(pool), "id": rec["id"], "width": rec["width"], "height": rec["height"], "rotation": rec.get("rotation", 0),
            "image": f"/files/{run}/dataset/images/{'val' if val else 'train'}/{rec['id']}.jpg",
            "marks": [{"x": a["px"], "y": a["py"], "ring": a["ring"], "source": a.get("source", "user")} for a in rec["arrows"]],
            "center": rec.get("center"), "edge": rec.get("edge") or [], "faces": [{"center": list(c), "edge": [list(p) for p in e]} for c, e in facegeom.faces_of_record(rec)],
            "stats": per.get(rec["id"]), "face": rec["face"], "env": rec.get("environment"), "export": rec.get("_export")}


# ----------------------------------------------------------------------------- http
PAGE = r"""<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Nocked trainer</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{font:15px/1.45 system-ui,sans-serif;margin:0;background:#141C17;color:#E3E9E2}
main{max-width:1100px;margin:0 auto;padding:18px}
h1{font-size:1.4rem;margin:0 0 4px}h2{font-size:1.05rem;margin:18px 0 8px}
.card{background:#1E2922;border-radius:14px;padding:14px 16px;margin-bottom:14px}
.muted{color:#93A198}.err{color:#E0584A}.ok{color:#58B27E}
button{border:0;border-radius:10px;padding:9px 16px;background:#F2C230;color:#1B1B1B;font-weight:650;cursor:pointer}
button.sec{background:transparent;color:#E3E9E2;box-shadow:inset 0 0 0 2px #33423A}button:disabled{opacity:.45;cursor:default}
input,select{background:#141C17;color:#E3E9E2;border:2px solid #33423A;border-radius:8px;padding:6px 8px;font:inherit}
label{display:inline-flex;flex-direction:column;gap:3px;margin:0 12px 8px 0;font-size:.85rem;color:#93A198}
table{border-collapse:collapse;width:100%;font-size:.9rem}td,th{text-align:left;padding:6px 8px;border-top:1px solid #33423A}th{color:#93A198;font-weight:600}
pre{background:#0f1512;border-radius:8px;padding:10px;max-height:220px;overflow:auto;font-size:12px}
canvas{max-width:100%;background:#0f1512;border-radius:8px}
.row{display:flex;gap:12px;flex-wrap:wrap;align-items:center}.grow{flex:1}
progress{width:100%;height:14px}
</style></head><body><main>
<h1>Nocked trainer</h1><div class="muted" id="gpu">…</div>
<div class="card"><h2>1. Export from the app</h2>
<div class="row"><input type="file" id="file" accept=".zip" multiple><button class="sec" id="upload">Upload</button><span id="upmsg" class="muted"></span></div>
<div class="muted" style="margin-top:8px">Exports from several phones can be uploaded and trained together; tick the ones to use. Every run trains two models: the arrow detector and the face finder (centre and four edge points of the blue ring; on a spot face each of the three spots is one face).</div>
<div id="exports" style="margin-top:6px"></div></div>
<div class="card"><h2>2. Train</h2>
<div class="row">
<label>Image size<select id="imgsz"><option>320</option><option selected>640</option><option>800</option></select></label>
<label>Epochs (per model)<input id="epochs" type="number" value="120" min="1" style="width:80px"></label>
<label>Model size<select id="size"><option value="n" selected>nano</option><option value="s">small</option><option value="m">medium</option></select></label>
<label>Precision<select id="precision"><option value="int8" selected>int8 (small, fast)</option><option value="fp32">float32 (most exact; the phone's GPU runs it as float16)</option></select></label>
<label>Batch<input id="batch" type="number" value="16" min="1" style="width:70px"></label>
<label>Validation share<input id="val" type="number" value="0.2" step="0.05" min="0" max="0.5" style="width:70px"></label>
<label>Run name<input id="name" placeholder="auto"></label>
</div>
<div class="row"><button id="start">Start training</button><button class="sec" id="stop" disabled>Stop</button><span id="jobmsg" class="muted"></span></div>
<div id="stages"></div>
<pre id="log" hidden></pre></div>
<div class="card"><h2>3. Results</h2><div id="runs" class="muted">No runs yet.</div></div>
<div class="card" id="prevcard" hidden><h2>Preview</h2>
<div class="row"><button class="sec" id="prev">◀</button><span id="pinfo" class="muted grow"></span><button class="sec" id="next">▶</button></div>
<canvas id="pv" width="900" height="600"></canvas>
<div class="muted">Your marks: arrows green with the ring, face centre gold, edge points blue. The models' proposals appear in the app after import. The counts under the picture come from the evaluation.</div></div>
</main><script>
const $=id=>document.getElementById(id);let st=null,prevRun=null,prevIdx=0;const chosen=new Map();
async function api(p,o){const r=await fetch(p,o);return r.json()}
function fmt(v){return v==null?'–':v}
async function refresh(){try{await update()}catch(e){$('jobmsg').textContent=`page could not read the trainer's state (${e.message}); retrying`;$('jobmsg').className='err'}}
async function update(){st=await api('/api/state');
$('gpu').textContent=`GPU: ${st.gpu.name}`+(st.gpu.torch?` · PyTorch ${st.gpu.torch}`:'')+(st.ultralytics?' · Ultralytics ready':' · Ultralytics not installed: pip install -r requirements.txt')+(st.gpu.device==='cuda'?(st.gpu.count>=2?' · the two models train at the same time on GPU 0 and 1':' · the two models train one after the other'):'')+(st.mock?' · MOCK MODE':'')+` · ${st.env}`;
$('gpu').className=st.export_missing&&st.export_missing.length?'err':'muted';if(st.export_missing&&st.export_missing.length)$('gpu').textContent+=` · export needs ${st.export_missing.join(', ')}: pip install -r requirements.txt in this Python`;
for(const e of st.exports)if(!chosen.has(e.name))chosen.set(e.name,true);
$('exports').innerHTML=st.exports.length?st.exports.map(e=>`<label style="flex-direction:row;align-items:center;gap:6px"><input type="checkbox" data-n="${e.name}" ${chosen.get(e.name)?'checked':''}> ${e.name} <span class="muted">(${(e.size/1048576).toFixed(1)} MB)</span></label>`).join('<br>'):'<span class="muted">No export uploaded yet.</span>';
for(const cb of $('exports').querySelectorAll('input'))cb.onchange=()=>{chosen.set(cb.dataset.n,cb.checked);refresh()};
const j=st.job;const running=j&&j.state==='running';
$('start').disabled=running||!st.exports.some(e=>chosen.get(e.name))||!(st.ultralytics||st.mock)||(!st.mock&&st.export_missing&&st.export_missing.length>0);$('stop').disabled=!running;
if(j){$('jobmsg').textContent=`${j.name}: ${j.state}`+(j.error?` – ${j.error}`:'')+(running&&Object.keys(j.stages||{}).length?(j.parallel?' · both models at the same time':' · one model after the other'):'');
$('jobmsg').className=j.state==='failed'?'err':j.state==='done'?'ok':'muted';
$('log').hidden=false;$('log').textContent=j.log.join('\n');$('log').scrollTop=1e9;renderStages(j)}
renderRuns(st.runs);}
function renderStages(j){const box=$('stages');const ss=j.stages||{};
for(const el of [...box.children])if(!ss[el.dataset.s])el.remove();
for(const [s,v] of Object.entries(ss)){let el=box.querySelector(`[data-s="${s}"]`);
if(!el){el=document.createElement('div');el.dataset.s=s;el.style.margin='8px 0';el.innerHTML='<div class="muted lbl"></div><progress value="0" max="1"></progress><canvas width="900" height="120" hidden></canvas>';box.appendChild(el)}
el.querySelector('.lbl').textContent=`${s==='arrows'?'Arrow detector':'Face finder'} · ${v.device} · ${v.state}`+(v.epoch?` · epoch ${v.epoch}/${j.epochs}`:'')+(v.error?` – ${v.error}`:'');
el.querySelector('.lbl').className='lbl '+(v.state==='failed'?'err':v.state==='done'?'ok':'muted');
el.querySelector('progress').value=v.state==='done'?1:v.epoch/j.epochs;drawCurve(el.querySelector('canvas'),v.metrics)}}
function drawCurve(c,m){if(!m.length){c.hidden=true;return}c.hidden=false;const g=c.getContext('2d');g.clearRect(0,0,c.width,c.height);
const keys=['loss','mAP50','pose_mAP50','mAP50_kpt'].filter(k=>m.some(x=>k in x));const cols={loss:'#F2C230',mAP50:'#5B93D6',pose_mAP50:'#58B27E',mAP50_kpt:'#58B27E'};
keys.forEach((k,ki)=>{const vs=m.map(x=>x[k]).filter(v=>v!=null);if(!vs.length)return;const lo=Math.min(...vs),hi=Math.max(...vs)||1;g.strokeStyle=cols[k]||'#fff';g.lineWidth=2;g.beginPath();
m.forEach((x,i)=>{if(x[k]==null)return;const X=30+i*(c.width-40)/Math.max(1,m.length-1),Y=10+(1-(x[k]-lo)/((hi-lo)||1))*(c.height-30);i?g.lineTo(X,Y):g.moveTo(X,Y)});g.stroke();
g.fillStyle=cols[k]||'#fff';g.font='12px system-ui';g.fillText(`${k}: ${vs[vs.length-1]}`,30+ki*160,c.height-6)});}
function renderRuns(runs){if(!runs.length){$('runs').textContent='No runs yet.';return}
$('runs').innerHTML='<table><tr><th>Run</th><th>Photos</th><th>Arrows found</th><th>False alarms</th><th>Entry error</th><th>Rings right</th><th>Face found</th><th title="On the photos whose proposals were accepted untouched: how far the new model agrees with the old one. A regression check, not accuracy.">Agreement</th><th>Time</th><th></th></tr>'+runs.slice().reverse().map(r=>{const e=r.eval;const ag=e.agreement;
const agTxt=ag&&ag.arrows?`${ag.found}/${ag.arrows}${ag.recall!=null?` (${Math.round(ag.recall*100)} %)`:''}, ${ag.false_alarms} false<br><span class="muted" style="font-size:.8rem">rings ${ag.ring_accuracy!=null?Math.round(ag.ring_accuracy*100)+' %':'–'}${ag.face_photos?`, face ${ag.face_found}/${ag.face_photos}`:''} · ${ag.images} photos</span>`:'–';
const face=e.face_photos?`${e.face_found}/${e.face_photos}`+(e.face_center_mm_median!=null?`<br><span class="muted" style="font-size:.8rem">centre ${e.face_center_mm_median} mm off, rings ${e.face_ring_accuracy!=null?Math.round(e.face_ring_accuracy*100)+' %':'–'} the same</span>`:''):'–';
const pe=e.per_export||{};const peTxt=Object.keys(pe).length>1?'<br><span class="muted" style="font-size:.8rem">'+Object.entries(pe).map(([k,v])=>`${k}: ${v.found}/${v.arrows}, ${v.false_alarms} false`).join('<br>')+'</span>':'';
return `<tr><td>${r.name}${r.mock?' <span class="muted">(mock)</span>':''}${peTxt}</td><td>${r.train_photos}+${r.val_photos}</td><td>${e.found}/${e.arrows}${e.recall!=null?` (${Math.round(e.recall*100)} %)`:''}</td><td>${e.false_alarms}</td><td>${e.mm_error_median!=null?e.mm_error_median+' mm median':fmt(e.px_error_mean)+' px'}</td><td>${e.ring_accuracy!=null?Math.round(e.ring_accuracy*100)+' %':'–'}</td><td>${face}</td><td>${agTxt}</td><td>${Math.round(r.duration_s/60)} min${r.parallel?'<br><span class="muted" style="font-size:.8rem">2 GPUs</span>':''}</td>
<td>${r.model?`<a href="/files/${r.name}/model.zip" download="${r.name}.zip"><button>Download model (.zip)</button></a>`:''} <button class="sec" onclick="openPreview('${r.name}')">Preview</button></td></tr>`}).join('')+'</table>';}
async function openPreview(run,idx=0){prevRun=run;const p=await api(`/api/preview?run=${encodeURIComponent(run)}&i=${idx}`);if(p.error){alert(p.error);return}prevIdx=p.index;
$('prevcard').hidden=false;$('pinfo').textContent=`${run}: photo ${p.index+1} of ${p.count} · ${p.id} · ${p.face} · ${p.env||''}`+(p.export?` · from ${p.export}`:'')+(p.stats?` · marks ${p.stats.marks}, found ${p.stats.found}, false ${p.stats.false}`+(p.stats.face==null?'':p.stats.face?', face found':', face missed'):'');
const img=new Image();img.onload=()=>{const c=$('pv');const k=Math.min(900/img.width,600/img.height);c.width=Math.round(img.width*k);c.height=Math.round(img.height*k);const g=c.getContext('2d');g.drawImage(img,0,0,c.width,c.height);
for(const f of (p.faces||[])){const c=f.center;g.strokeStyle='#F2C230';g.lineWidth=2;g.beginPath();g.moveTo(c[0]*k-8,c[1]*k);g.lineTo(c[0]*k+8,c[1]*k);g.moveTo(c[0]*k,c[1]*k-8);g.lineTo(c[0]*k,c[1]*k+8);g.stroke();
for(const e of f.edge){g.strokeStyle='#5B93D6';g.lineWidth=2;g.beginPath();g.arc(e[0]*k,e[1]*k,6,0,7);g.stroke()}}
for(const m of p.marks){g.strokeStyle='#58B27E';g.lineWidth=2;g.beginPath();g.arc(m.x*k,m.y*k,9,0,7);g.stroke();g.fillStyle='#58B27E';g.font='bold 12px system-ui';g.fillText(m.ring===11?'X':m.ring===0?'M':m.ring,m.x*k+12,m.y*k+4)}};
img.src=p.image+'?t='+Date.now();$('prevcard').scrollIntoView({behavior:'smooth'});}
$('prev').onclick=()=>openPreview(prevRun,prevIdx-1);$('next').onclick=()=>openPreview(prevRun,prevIdx+1);
$('upload').onclick=async()=>{const fs=[...$('file').files];if(!fs.length)return;const done=[];for(const f of fs){$('upmsg').textContent=`uploading ${f.name}…`;const r=await fetch('/api/upload?name='+encodeURIComponent(f.name),{method:'PUT',body:f});const j=await r.json();if(!j.ok){$('upmsg').textContent=j.error;return}done.push(j.name)}$('upmsg').textContent=`stored ${done.join(', ')}`;refresh()};
$('start').onclick=async()=>{const body={exports:st.exports.map(e=>e.name).filter(n=>chosen.get(n)),imgsz:$('imgsz').value,epochs:$('epochs').value,size:$('size').value,precision:$('precision').value,batch:$('batch').value,val:$('val').value,name:$('name').value};
const j=await api('/api/train',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body)});if(!j.ok)alert(j.error);refresh()};
$('stop').onclick=()=>api('/api/stop',{method:'POST'});
refresh();setInterval(refresh,2000);
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    mock = False

    def log_message(self, *a):  # quiet
        pass

    def send_json(self, obj, code=200):
        data = json.dumps(json_safe(obj), allow_nan=False).encode()
        self.send_response(code); self.send_header("content-type", "application/json"); self.send_header("content-length", str(len(data))); self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path); q = urllib.parse.parse_qs(u.query)
        if u.path == "/":
            data = PAGE.encode(); self.send_response(200); self.send_header("content-type", "text/html; charset=utf-8"); self.send_header("content-length", str(len(data))); self.end_headers(); self.wfile.write(data)
        elif u.path == "/api/state":
            with LOCK:
                job = JOB.snapshot() if JOB else None
            self.send_json({"gpu": gpu_info(), "ultralytics": ultralytics_available(), "export_missing": export_missing(), "env": env_note(),
                            "mock": Handler.mock, "exports": exports_list(), "job": job, "runs": runs_list()})
        elif u.path == "/api/preview":
            try:
                self.send_json(preview(Path(q["run"][0]).name, int(q.get("i", ["0"])[0])))
            except Exception as e:
                self.send_json({"error": str(e)})
        elif u.path.startswith("/files/"):
            rel = urllib.parse.unquote(u.path[len("/files/"):])
            f = (RUNS / rel).resolve()
            if not str(f).startswith(str(RUNS.resolve())) or not f.is_file():
                self.send_json({"error": "not found"}, 404); return
            data = f.read_bytes()
            self.send_response(200); self.send_header("content-type", {".tflite": "application/octet-stream", ".zip": "application/zip", ".json": "application/json"}.get(f.suffix, "image/jpeg"))
            self.send_header("content-length", str(len(data))); self.end_headers(); self.wfile.write(data)
        else:
            self.send_json({"error": "not found"}, 404)

    def do_PUT(self):
        u = urllib.parse.urlparse(self.path); q = urllib.parse.parse_qs(u.query)
        if u.path != "/api/upload":
            self.send_json({"error": "not found"}, 404); return
        name = Path(q.get("name", ["export.zip"])[0]).name
        if not name.endswith(".zip"):
            name += ".zip"
        n = int(self.headers.get("content-length", "0"))
        DATA.mkdir(exist_ok=True)
        dest = DATA / name
        with dest.open("wb") as f:
            left = n
            while left > 0:
                chunk = self.rfile.read(min(1 << 20, left)); left -= len(chunk); f.write(chunk)
                if not chunk:
                    break
        try:
            with zipfile.ZipFile(dest) as z:
                if not any(x.endswith(".json") for x in z.namelist()):
                    raise ValueError("no records in the zip")
        except Exception as e:
            dest.unlink(missing_ok=True); self.send_json({"ok": False, "error": f"not a Nocked export: {e}"}); return
        self.send_json({"ok": True, "name": name})

    def do_POST(self):
        global JOB
        u = urllib.parse.urlparse(self.path)
        n = int(self.headers.get("content-length", "0")); body = json.loads(self.rfile.read(n) or b"{}")
        if u.path == "/api/train":
            with LOCK:
                if JOB and JOB.state == "running":
                    self.send_json({"ok": False, "error": "a training is running"}); return
                exports = [Path(n).name for n in (body.get("exports") or ([body["export"]] if body.get("export") else []))]
                exports = [n for n in exports if (DATA / n).exists()]
                if not exports:
                    self.send_json({"ok": False, "error": "upload an export first and tick at least one"}); return
                if not (ultralytics_available() or Handler.mock):
                    self.send_json({"ok": False, "error": "Ultralytics is not installed"}); return
                miss = export_missing() if not Handler.mock else []
                if miss:
                    self.send_json({"ok": False, "error": "the TFLite export needs " + ", ".join(miss) + f": run pip install -r requirements.txt with the Python this page uses ({sys.executable})"}); return
                name = (body.get("name") or "").strip() or time.strftime("run-%Y%m%d-%H%M%S")
                params = {"exports": exports, "imgsz": int(body.get("imgsz", 640)), "epochs": int(body.get("epochs", 120)),
                          "size": body.get("size", "n"), "precision": body.get("precision", "int8"), "batch": int(body.get("batch", 16)), "val": float(body.get("val", 0.2)), "name": name}
                JOB = Job(params)
                threading.Thread(target=run_job, args=(JOB, Handler.mock), daemon=True).start()
            self.send_json({"ok": True, "name": name})
        elif u.path == "/api/stop":
            with LOCK:
                if JOB and JOB.state == "running":
                    JOB.stop = True
            self.send_json({"ok": True})
        else:
            self.send_json({"error": "not found"}, 404)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--host", default="127.0.0.1", help="use 0.0.0.0 to reach it from another machine")
    ap.add_argument("--mock", action="store_true", help="fake trainer, for checking the page without a GPU")
    ap.add_argument("--mock-gpus", type=int, default=0, help="with --mock: pretend this many GPUs (2 trains both models at the same time)")
    a = ap.parse_args()
    global MOCK_GPUS
    Handler.mock = a.mock
    MOCK_GPUS = a.mock_gpus if a.mock else None
    DATA.mkdir(exist_ok=True); RUNS.mkdir(exist_ok=True)
    srv = ThreadingHTTPServer((a.host, a.port), Handler)
    print(f"Nocked trainer at http://{'localhost' if a.host == '127.0.0.1' else a.host}:{a.port}" + ("  (mock)" if a.mock else ""))
    print(env_note())
    print("GPU: " + gpu_info()["name"])
    miss = export_missing()
    if miss and not a.mock:
        print("the TFLite export needs " + ", ".join(miss) + ": pip install -r requirements.txt (with this Python; use the virtual environment, a system Python refuses)")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
