#!/usr/bin/env python3
"""UV-Sight trainer: a local web page that trains the arrow detector on your own GPU.

    pip install -r requirements.txt
    python3 webui.py            # then open http://localhost:8000

Upload the export ZIP from the app (Settings -> Photo scoring -> Export), pick the
parameters, start. The page shows the progress, evaluates the result in archery terms
(arrows found, entry-point error in millimetres, rings right) and offers the int8
TFLite file for import into the app.

--mock trains a stand-in model in seconds (for checking the page without a GPU).
"""
import argparse
import json
import math
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
DATA = ROOT / "data"
RUNS = ROOT / "runs"
MATCH_PX = 40.0              # a prediction within this many pixels of a mark counts as found

LOCK = threading.Lock()
JOB = None                   # the running/last job, see Job


class Job:
    def __init__(self, params):
        self.params = params
        self.name = params["name"]
        self.state = "running"     # running / done / failed / stopped
        self.epoch = 0
        self.epochs = int(params["epochs"])
        self.metrics = []          # [{epoch, loss, ...}]
        self.log = []
        self.error = None
        self.started = time.time()
        self.stop = False
        self.result = None

    def say(self, text):
        self.log.append(f"{time.strftime('%H:%M:%S')} {text}")
        del self.log[:-300]

    def snapshot(self):
        return {k: getattr(self, k) for k in ("name", "state", "epoch", "epochs", "metrics", "log", "error", "started", "result", "params")}


# ----------------------------------------------------------------------------- hardware
def gpu_info():
    try:
        import torch
        if torch.cuda.is_available():
            return {"torch": torch.__version__, "device": "cuda", "name": torch.cuda.get_device_name(0)}
        if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
            return {"torch": torch.__version__, "device": "mps", "name": "Apple GPU"}
        return {"torch": torch.__version__, "device": "cpu", "name": "CPU only (no CUDA/ROCm device found)"}
    except Exception as e:  # torch missing
        return {"torch": None, "device": "none", "name": f"PyTorch not installed ({e.__class__.__name__})"}


def ultralytics_available():
    try:
        import ultralytics  # noqa: F401
        return True
    except Exception:
        return False


# ----------------------------------------------------------------------------- evaluation
def evaluate(val_recs, predict):
    """predict(record, image_path) -> [(x, y, conf)] in upright pixels. Returns archery metrics."""
    found = total = false = ring_ok = ring_n = 0
    px_err = []; mm_err = []
    per_image = []
    for rec, img in val_recs:
        preds = predict(rec, img)
        marks = [(a["px"], a["py"], a) for a in rec["arrows"]]
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
        g = facegeom.geometry_of_record(rec)
        if g:
            dia, min_ring = facegeom.FACES.get(rec["face"], (rec.get("faceDiameterMm", 400), 1))
            for a, (px, py, conf) in matched:
                um = g.to_face((a["px"], a["py"])); up = g.to_face((px, py))
                m1 = facegeom.mm_from_center(um, dia); m2 = facegeom.mm_from_center(up, dia)
                mm_err.append(math.hypot(m1[0] - m2[0], m1[1] - m2[1]))
                ring_n += 1
                if facegeom.ring(up, dia, min_ring, rec.get("arrowMm", 6.0)) == a["ring"]:
                    ring_ok += 1
        per_image.append({"id": rec["id"], "marks": len(marks), "found": len(used), "false": len(preds) - len(used)})
    return {
        "images": len(val_recs), "arrows": total, "found": found, "false_alarms": false,
        "recall": round(found / total, 3) if total else None,
        "precision": round(found / (found + false), 3) if (found + false) else None,
        "px_error_mean": round(sum(px_err) / len(px_err), 1) if px_err else None,
        "mm_error_mean": round(sum(mm_err) / len(mm_err), 1) if mm_err else None,
        "mm_error_median": round(sorted(mm_err)[len(mm_err) // 2], 1) if mm_err else None,
        "ring_accuracy": round(ring_ok / ring_n, 3) if ring_n else None,
        "per_image": per_image,
    }


# ----------------------------------------------------------------------------- training
def run_job(job, mock):
    p = job.params
    run = RUNS / job.name
    try:
        run.mkdir(parents=True, exist_ok=True)
        job.say("unpacking export and preparing the dataset")
        recs = prepare_dataset.load_records(DATA / p["export"], run / "export")
        if not recs:
            raise RuntimeError("no records in the export")
        train, val = prepare_dataset.split_by_session(recs, float(p["val"]))
        ds = run / "dataset"
        n_tr = prepare_dataset.write_split(train, ds, "train", False)
        n_va = prepare_dataset.write_split(val, ds, "val", False)
        (ds / "data.yaml").write_text(f"path: {ds.resolve()}\ntrain: images/train\nval: images/val\nkpt_shape: [1, 3]\nnames:\n  0: arrow\n")
        job.say(f"{len(train)} training photos ({n_tr} arrows), {len(val)} validation photos ({n_va} arrows)")
        if len(val) == 0:
            job.say("warning: no validation photos, the evaluation below will be empty")
        imgsz = int(p["imgsz"])
        if mock:
            predict = mock_train(job, run, imgsz)
        else:
            predict = real_train(job, run, ds, imgsz)
        if job.stop:
            job.state = "stopped"; job.say("stopped"); return
        job.say("evaluating on the validation photos")
        ev = evaluate(val, predict)
        summary = {"name": job.name, "params": p, "finished": time.time(), "duration_s": round(time.time() - job.started),
                   "train_photos": len(train), "val_photos": len(val), "eval": ev, "model": "model.tflite", "mock": mock,
                   "final_metrics": job.metrics[-1] if job.metrics else None}
        (run / "summary.json").write_text(json.dumps(summary, indent=1))
        (run / "model.json").write_text(json.dumps({"name": job.name, "imgsz": imgsz, "task": "pose", "kpt": ["entry"], "trained": time.time(),
                                                    "eval": {k: v for k, v in ev.items() if k != "per_image"}}, indent=1))
        job.result = summary
        job.state = "done"
        job.say("done")
    except Exception as e:
        job.state = "failed"; job.error = f"{e}"; job.say("failed: " + "".join(traceback.format_exception_only(type(e), e)).strip())


def mock_train(job, run, imgsz):
    for ep in range(1, job.epochs + 1):
        if job.stop:
            return None
        time.sleep(0.05)
        job.epoch = ep
        job.metrics.append({"epoch": ep, "loss": round(3.0 / (1 + ep * 0.3), 3), "mAP50": round(min(0.95, 0.2 + ep * 0.04), 3)})
        if ep % 5 == 0 or ep == job.epochs:
            job.say(f"epoch {ep}/{job.epochs} loss {job.metrics[-1]['loss']}")
    (run / "model.tflite").write_bytes(b"TFL3-mock-" + str(time.time()).encode())
    rnd = random.Random(1)

    def predict(rec, img):
        out = []
        for a in rec["arrows"]:
            if rnd.random() < 0.9:
                out.append((a["px"] + rnd.gauss(0, 6), a["py"] + rnd.gauss(0, 6), 0.8))
        if rnd.random() < 0.2:
            out.append((rnd.uniform(0, rec["width"]), rnd.uniform(0, rec["height"]), 0.5))
        return out
    return predict


def real_train(job, run, ds, imgsz):
    from ultralytics import YOLO
    p = job.params
    model = YOLO(f"yolo11{p['size']}-pose.pt")

    def on_fit_epoch_end(trainer):
        job.epoch = trainer.epoch + 1
        m = {"epoch": job.epoch}
        for k, v in (trainer.metrics or {}).items():
            m[k.replace("metrics/", "").replace("(B)", "").replace("(P)", "_kpt")] = round(float(v), 4)
        try:
            m["loss"] = round(float(sum(trainer.tloss)), 4) if hasattr(trainer.tloss, "__iter__") else round(float(trainer.tloss), 4)
        except Exception:
            pass
        job.metrics.append(m)
        job.say(f"epoch {job.epoch}/{job.epochs} " + " ".join(f"{k} {v}" for k, v in m.items() if k != "epoch")[:160])
        if job.stop:
            trainer.stop = True
    model.add_callback("on_fit_epoch_end", on_fit_epoch_end)
    gi = gpu_info()
    device = 0 if gi["device"] == "cuda" else gi["device"] if gi["device"] == "mps" else "cpu"
    job.say(f"training on {gi['name']}")
    model.train(data=str(ds / "data.yaml"), imgsz=imgsz, epochs=job.epochs, batch=int(p["batch"]), project=str(run), name="train",
                exist_ok=True, device=device, verbose=False, plots=False)
    if job.stop:
        return None
    best = run / "train" / "weights" / "best.pt"
    job.say("exporting int8 TFLite")
    trained = YOLO(str(best))
    out = trained.export(format="tflite", int8=True, imgsz=imgsz, data=str(ds / "data.yaml"))
    src = Path(out)
    if src.is_dir():
        cands = sorted(src.glob("*int8*.tflite")) or sorted(src.glob("*.tflite"))
        src = cands[0]
    shutil.copy(src, run / "model.tflite")

    def predict(rec, img):
        res = trained.predict(str(img), imgsz=imgsz, conf=0.25, verbose=False)[0]
        out = []
        if res.keypoints is not None and res.boxes is not None:
            kp = res.keypoints.xy.cpu().numpy(); conf = res.boxes.conf.cpu().numpy()
            for i in range(len(conf)):
                out.append((float(kp[i][0][0]), float(kp[i][0][1]), float(conf[i])))
        return out
    return predict


# ----------------------------------------------------------------------------- state
def runs_list():
    out = []
    for d in sorted(RUNS.glob("*/summary.json")):
        try:
            s = json.loads(d.read_text()); s.pop("eval", None) if False else None
            out.append({"name": s["name"], "finished": s["finished"], "duration_s": s["duration_s"], "train_photos": s["train_photos"],
                        "val_photos": s["val_photos"], "eval": {k: v for k, v in s["eval"].items() if k != "per_image"},
                        "params": s["params"], "mock": s.get("mock", False), "model": (d.parent / "model.tflite").exists()})
        except Exception:
            pass
    return out


def exports_list():
    return [{"name": f.name, "size": f.stat().st_size, "mtime": f.stat().st_mtime} for f in sorted(DATA.glob("*.zip"))]


def preview(run, index):
    exp = RUNS / run / "export"
    recs = prepare_dataset.load_records(exp, exp)
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
            "stats": per.get(rec["id"]), "face": rec["face"], "env": rec.get("environment")}


# ----------------------------------------------------------------------------- http
PAGE = r"""<!doctype html><html lang="en"><head><meta charset="utf-8"><title>UV-Sight trainer</title>
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
<h1>UV-Sight trainer</h1><div class="muted" id="gpu">…</div>
<div class="card"><h2>1. Export from the app</h2>
<div class="row"><input type="file" id="file" accept=".zip"><button class="sec" id="upload">Upload</button><span id="upmsg" class="muted"></span></div>
<div id="exports" class="muted" style="margin-top:8px"></div></div>
<div class="card"><h2>2. Train</h2>
<div class="row">
<label>Export<select id="export"></select></label>
<label>Image size<select id="imgsz"><option>320</option><option selected>640</option><option>800</option></select></label>
<label>Epochs<input id="epochs" type="number" value="120" min="1" style="width:80px"></label>
<label>Model size<select id="size"><option value="n" selected>nano</option><option value="s">small</option></select></label>
<label>Batch<input id="batch" type="number" value="16" min="1" style="width:70px"></label>
<label>Validation share<input id="val" type="number" value="0.2" step="0.05" min="0" max="0.5" style="width:70px"></label>
<label>Run name<input id="name" placeholder="auto"></label>
</div>
<div class="row"><button id="start">Start training</button><button class="sec" id="stop" disabled>Stop</button><span id="jobmsg" class="muted"></span></div>
<progress id="prog" value="0" max="1" hidden></progress>
<canvas id="curve" width="900" height="160" hidden></canvas>
<pre id="log" hidden></pre></div>
<div class="card"><h2>3. Results</h2><div id="runs" class="muted">No runs yet.</div></div>
<div class="card" id="prevcard" hidden><h2>Preview</h2>
<div class="row"><button class="sec" id="prev">◀</button><span id="pinfo" class="muted grow"></span><button class="sec" id="next">▶</button></div>
<canvas id="pv" width="900" height="600"></canvas>
<div class="muted">Your marks: green with the ring. Nothing else is drawn here yet; the model's proposals appear in the app after import. The counts under the picture come from the evaluation.</div></div>
</main><script>
const $=id=>document.getElementById(id);let st=null,prevRun=null,prevIdx=0;
async function api(p,o){const r=await fetch(p,o);return r.json()}
function fmt(v){return v==null?'–':v}
async function refresh(){st=await api('/api/state');
$('gpu').textContent=`GPU: ${st.gpu.name}`+(st.gpu.torch?` · PyTorch ${st.gpu.torch}`:'')+(st.ultralytics?' · Ultralytics ready':' · Ultralytics not installed: pip install -r requirements.txt')+(st.mock?' · MOCK MODE':'');
const sel=$('export');const cur=sel.value;sel.innerHTML=st.exports.map(e=>`<option>${e.name}</option>`).join('');if(cur)sel.value=cur;
$('exports').textContent=st.exports.length?st.exports.map(e=>`${e.name} (${(e.size/1048576).toFixed(1)} MB)`).join(' · '):'No export uploaded yet.';
const j=st.job;const running=j&&j.state==='running';
$('start').disabled=running||!st.exports.length||!(st.ultralytics||st.mock);$('stop').disabled=!running;
if(j){$('jobmsg').textContent=`${j.name}: ${j.state}`+(j.error?` – ${j.error}`:'')+(running?` · epoch ${j.epoch}/${j.epochs}`:'');
$('jobmsg').className=j.state==='failed'?'err':j.state==='done'?'ok':'muted';
$('prog').hidden=false;$('prog').value=j.epoch/j.epochs;$('log').hidden=false;$('log').textContent=j.log.join('\n');$('log').scrollTop=1e9;drawCurve(j.metrics)}
renderRuns(st.runs);}
function drawCurve(m){const c=$('curve');if(!m.length){c.hidden=true;return}c.hidden=false;const g=c.getContext('2d');g.clearRect(0,0,c.width,c.height);
const keys=['loss','mAP50','pose_mAP50','mAP50_kpt'].filter(k=>m.some(x=>k in x));const cols={loss:'#F2C230',mAP50:'#5B93D6',pose_mAP50:'#58B27E',mAP50_kpt:'#58B27E'};
keys.forEach((k,ki)=>{const vs=m.map(x=>x[k]).filter(v=>v!=null);if(!vs.length)return;const lo=Math.min(...vs),hi=Math.max(...vs)||1;g.strokeStyle=cols[k]||'#fff';g.lineWidth=2;g.beginPath();
m.forEach((x,i)=>{if(x[k]==null)return;const X=30+i*(c.width-40)/Math.max(1,m.length-1),Y=10+(1-(x[k]-lo)/((hi-lo)||1))*(c.height-30);i?g.lineTo(X,Y):g.moveTo(X,Y)});g.stroke();
g.fillStyle=cols[k]||'#fff';g.font='12px system-ui';g.fillText(`${k}: ${vs[vs.length-1]}`,30+ki*160,c.height-6)});}
function renderRuns(runs){if(!runs.length){$('runs').textContent='No runs yet.';return}
$('runs').innerHTML='<table><tr><th>Run</th><th>Photos</th><th>Arrows found</th><th>False alarms</th><th>Entry error</th><th>Rings right</th><th>Time</th><th></th></tr>'+runs.slice().reverse().map(r=>{const e=r.eval;
return `<tr><td>${r.name}${r.mock?' <span class="muted">(mock)</span>':''}</td><td>${r.train_photos}+${r.val_photos}</td><td>${e.found}/${e.arrows}${e.recall!=null?` (${Math.round(e.recall*100)} %)`:''}</td><td>${e.false_alarms}</td><td>${e.mm_error_median!=null?e.mm_error_median+' mm median':fmt(e.px_error_mean)+' px'}</td><td>${e.ring_accuracy!=null?Math.round(e.ring_accuracy*100)+' %':'–'}</td><td>${Math.round(r.duration_s/60)} min</td>
<td>${r.model?`<a href="/files/${r.name}/model.tflite" download="${r.name}.tflite"><button>Download .tflite</button></a>`:''} <button class="sec" onclick="openPreview('${r.name}')">Preview</button></td></tr>`}).join('')+'</table>';}
async function openPreview(run,idx=0){prevRun=run;const p=await api(`/api/preview?run=${encodeURIComponent(run)}&i=${idx}`);if(p.error){alert(p.error);return}prevIdx=p.index;
$('prevcard').hidden=false;$('pinfo').textContent=`${run}: photo ${p.index+1} of ${p.count} · ${p.id} · ${p.face} · ${p.env||''}`+(p.stats?` · marks ${p.stats.marks}, found ${p.stats.found}, false ${p.stats.false}`:'');
const img=new Image();img.onload=()=>{const c=$('pv');const k=Math.min(900/img.width,600/img.height);c.width=Math.round(img.width*k);c.height=Math.round(img.height*k);const g=c.getContext('2d');g.drawImage(img,0,0,c.width,c.height);
for(const m of p.marks){g.strokeStyle='#58B27E';g.lineWidth=2;g.beginPath();g.arc(m.x*k,m.y*k,9,0,7);g.stroke();g.fillStyle='#58B27E';g.font='bold 12px system-ui';g.fillText(m.ring===11?'X':m.ring===0?'M':m.ring,m.x*k+12,m.y*k+4)}};
img.src=p.image+'?t='+Date.now();$('prevcard').scrollIntoView({behavior:'smooth'});}
$('prev').onclick=()=>openPreview(prevRun,prevIdx-1);$('next').onclick=()=>openPreview(prevRun,prevIdx+1);
$('upload').onclick=async()=>{const f=$('file').files[0];if(!f)return;$('upmsg').textContent='uploading…';const r=await fetch('/api/upload?name='+encodeURIComponent(f.name),{method:'PUT',body:f});const j=await r.json();$('upmsg').textContent=j.ok?`stored ${j.name}`:j.error;refresh()};
$('start').onclick=async()=>{const body={export:$('export').value,imgsz:$('imgsz').value,epochs:$('epochs').value,size:$('size').value,batch:$('batch').value,val:$('val').value,name:$('name').value};
const j=await api('/api/train',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body)});if(!j.ok)alert(j.error);refresh()};
$('stop').onclick=()=>api('/api/stop',{method:'POST'});
refresh();setInterval(refresh,2000);
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    mock = False

    def log_message(self, *a):  # quiet
        pass

    def send_json(self, obj, code=200):
        data = json.dumps(obj).encode()
        self.send_response(code); self.send_header("content-type", "application/json"); self.send_header("content-length", str(len(data))); self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path); q = urllib.parse.parse_qs(u.query)
        if u.path == "/":
            data = PAGE.encode(); self.send_response(200); self.send_header("content-type", "text/html; charset=utf-8"); self.send_header("content-length", str(len(data))); self.end_headers(); self.wfile.write(data)
        elif u.path == "/api/state":
            with LOCK:
                job = JOB.snapshot() if JOB else None
            self.send_json({"gpu": gpu_info(), "ultralytics": ultralytics_available(), "mock": Handler.mock, "exports": exports_list(), "job": job, "runs": runs_list()})
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
            self.send_response(200); self.send_header("content-type", "application/octet-stream" if f.suffix == ".tflite" else "image/jpeg")
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
            dest.unlink(missing_ok=True); self.send_json({"ok": False, "error": f"not a UV-Sight export: {e}"}); return
        self.send_json({"ok": True, "name": name})

    def do_POST(self):
        global JOB
        u = urllib.parse.urlparse(self.path)
        n = int(self.headers.get("content-length", "0")); body = json.loads(self.rfile.read(n) or b"{}")
        if u.path == "/api/train":
            with LOCK:
                if JOB and JOB.state == "running":
                    self.send_json({"ok": False, "error": "a training is running"}); return
                if not (DATA / Path(body.get("export", "")).name).exists():
                    self.send_json({"ok": False, "error": "upload an export first"}); return
                if not (ultralytics_available() or Handler.mock):
                    self.send_json({"ok": False, "error": "Ultralytics is not installed"}); return
                name = (body.get("name") or "").strip() or time.strftime("run-%Y%m%d-%H%M%S")
                params = {"export": Path(body["export"]).name, "imgsz": int(body.get("imgsz", 640)), "epochs": int(body.get("epochs", 120)),
                          "size": body.get("size", "n"), "batch": int(body.get("batch", 16)), "val": float(body.get("val", 0.2)), "name": name}
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
    a = ap.parse_args()
    Handler.mock = a.mock
    DATA.mkdir(exist_ok=True); RUNS.mkdir(exist_ok=True)
    srv = ThreadingHTTPServer((a.host, a.port), Handler)
    print(f"UV-Sight trainer at http://{'localhost' if a.host == '127.0.0.1' else a.host}:{a.port}" + ("  (mock)" if a.mock else ""))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
