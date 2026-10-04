package de.uvsight.app.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uvsight.app.SightViewModel
import de.uvsight.core.AppState
import de.uvsight.core.ConnState
import de.uvsight.core.SightController
import de.uvsight.core.fmt
import de.uvsight.core.points
import de.uvsight.core.tr

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrainingScreen(state: AppState, ctl: SightController, vm: SightViewModel, onPhoto: (java.io.File) -> Unit = {}) {
    val uv = LocalUv.current
    val context = LocalContext.current
    val connected = state.conn == ConnState.CONNECTED
    val ses = state.session
    val known = ses?.active == true
    val off = connected && ses != null && !ses.counter
    val idle = connected && ses != null && ses.counter && !ses.active
    val run = known || !connected
    var askSkip by remember { mutableStateOf(false) }
    var askStop by remember { mutableStateOf(false) }

    if (state.photoOnly) { PhotoOnlyScreen(state, ctl, vm, onPhoto); return }
    if (off) { EmptyBox(tr("The shot counter is off.")) { PrimaryButton(tr("Turn shot counter on")) { ctl.counterOn() } }; return }
    if (idle) { EmptyBox(tr("No session running. The first shot starts one automatically.")) { SecondaryButton(tr("Start session now")) { ctl.startSession() } }; return }
    if (!run) { EmptyBox(tr("Connect to the sight on the Status tab first.")); return }

    val dash = "–"
    UvCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(if (known) tr("End {end}", "end" to ses!!.end) else tr("Scoring"), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = uv.ink)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (known) ses!!.endShots.toString() else dash, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = uv.ink)
                Spacer(Modifier.width(4.dp))
                Text(if (known && ses!!.endShots == 1) tr("shot counted") else tr("shots counted"), color = uv.muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat(if (known) ses!!.ends.toString() else dash, "ends")
            Stat(if (known) ses!!.scored.toString() else dash, "arrows")
            Stat(if (known) fmt(ses!!.avg, 2) else dash, "average")
            Stat(if (known) ses!!.x.toString() else dash, "X")
        }
        state.lastEnd?.let { le ->
            Spacer(Modifier.height(10.dp))
            Text(if (le.valid) tr("Last end: {sum} points from {arrows} arrows, average {avg}, {x} X", "sum" to le.sum, "arrows" to le.arrows, "avg" to (fmt(le.avg ?: 0.0, 2)), "x" to le.x)
                 else tr("Last end: invalid, {arrows} arrows ({reason})", "arrows" to le.arrows, "reason" to le.reason), color = uv.muted, fontSize = 14.sp)
            // what the numbers of that end say
            val rec = state.currentSessionKey?.let { k -> state.ends[k]?.firstOrNull { it.n == le.n } }
            val hints = remember(rec, state.hints) { rec?.let { ctl.endHints(it) } ?: emptyList() }
            for (h in hints) Text("• $h", color = uv.ink, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (!connected) { Spacer(Modifier.height(10.dp)); Text(tr("Not connected. Keep scoring: the app reconnects when you tap Save."), color = uv.muted, fontSize = 14.sp) }
    }

    // Distance
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Distance"), color = uv.muted)
        Spacer(Modifier.width(10.dp))
        RoundButton("−") { ctl.distMinus() }
        Text("${state.distM} m", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
        if (state.distNote.isNotEmpty()) Text(state.distNote, color = uv.muted, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        RoundButton("+") { ctl.distPlus() }
        Spacer(Modifier.weight(1f))
        Text(state.lastAng, color = uv.muted, fontSize = 13.sp)
        val lastShot = (ses?.endShots ?: 0) - 1
        if (connected && known && lastShot >= 0 && state.lastAng.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            SecondaryButton(tr("Trace")) { ctl.requestTrace(lastShot) }
        }
    }
    state.trace?.let { tr -> TraceDialog(tr, state.endShots?.shots?.getOrNull(tr.i)) { ctl.clearTrace() } }

    // Entry row
    FlowRow(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp).heightIn(min = 58.dp)
        .border(2.dp, uv.line, SmallShape).padding(8.dp), verticalArrangement = Arrangement.Center) {
        if (state.entries.isEmpty()) Text(tr("Tap the scores of this end"), color = uv.muted, modifier = Modifier.padding(6.dp))
        else {
            for (v in state.entries) Chip(v)
            Text(state.entries.sumOf { points(it) }.toString(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 12.dp))
        }
    }

    val busy = state.awaitingEnd || state.reconnecting
    PhotoButton(state, vm, enabled = !busy, onPhoto = onPhoto)
    Spacer(Modifier.height(10.dp))
    val keysEnabled = !busy && state.entries.size < SightController.MAX_ARROWS
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "X", "M")
    val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (v in row) {
                    Button(onClick = { ctl.pushEntry(v); runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)) } },
                        enabled = keysEnabled, modifier = Modifier.weight(1f).height(60.dp), shape = SmallShape,
                        colors = ButtonDefaults.buttonColors(containerColor = if (v == "M") uv.surface else Ring.colorFor(v), contentColor = Ring.textFor(v, uv.ink),
                            disabledContainerColor = (if (v == "M") uv.surface else Ring.colorFor(v)).copy(alpha = 0.5f), disabledContentColor = Ring.textFor(v, uv.ink).copy(alpha = 0.6f)),
                        border = if (v == "M" || v == "1" || v == "2" || v == "3" || v == "4") androidx.compose.foundation.BorderStroke(1.5.dp, if (v == "M") uv.line else if (v == "3" || v == "4") Color(0xFF555555) else Color(0xFFBFC5BD)) else null) {
                        Text(v, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
        OutlinedButton(onClick = { ctl.undoEntry() }, enabled = !busy && state.entries.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(50.dp), shape = SmallShape,
            border = androidx.compose.foundation.BorderStroke(2.dp, uv.line), colors = ButtonDefaults.outlinedButtonColors(contentColor = uv.ink)) { Text(tr("↶ Undo"), fontWeight = FontWeight.SemiBold) }
    }

    Spacer(Modifier.height(14.dp))
    PrimaryButton(if (state.reconnecting) tr("Connecting…") else if (state.awaitingEnd) tr("Saving…") else tr("Save end ({entries})", "entries" to state.entries.size),
        modifier = Modifier.fillMaxWidth().height(54.dp), enabled = !busy && state.entries.isNotEmpty()) { ctl.sendEnd() }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(tr("Skip end"), Modifier.weight(1f), enabled = !busy && known && (ses?.endShots ?: 0) > 0) { askSkip = true }
        SecondaryButton(tr("End session"), Modifier.weight(1f), enabled = !busy && known) { askStop = true }
    }
    Spacer(Modifier.height(24.dp))

    if (askSkip) {
        val n = ses?.endShots ?: 0
        AlertDialog(onDismissRequest = { askSkip = false }, title = { Text(tr("Skip this end?")) },
            text = { Text(tr("The {n} counted {n2} stored as invalid with 0 points and left out of the average.", "n" to n, "n2" to (if (n == 1) tr("arrow is") else tr("arrows are")))) },
            confirmButton = { TextButton(onClick = { askSkip = false; ctl.skipEnd() }) { Text(tr("Skip end"), color = uv.red) } },
            dismissButton = { TextButton(onClick = { askSkip = false }) { Text(tr("Cancel")) } })
    }
    if (askStop) {
        var text = "The session is saved to the sight's log."
        if (state.entries.isNotEmpty()) text += " Scores you haven't saved yet are lost."
        if ((ses?.endShots ?: 0) > 1) text += " The ${ses!!.endShots} shots of the open end are stored as invalid."
        AlertDialog(onDismissRequest = { askStop = false }, title = { Text(tr("End session?")) }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { askStop = false; ctl.endSession() }) { Text(tr("End session"), color = uv.red) } },
            dismissButton = { TextButton(onClick = { askStop = false }) { Text(tr("Cancel")) } })
    }
}

@Composable
fun RoundButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val uv = LocalUv.current
    Box(Modifier.size(44.dp).border(2.dp, if (enabled) uv.line else uv.line.copy(alpha = 0.4f), CircleShape)
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (enabled) uv.ink else uv.muted)
    }
}

/** Capture into a file like TakePicture, but with the camera app named in the settings (Android 11+ only hands the plain request to the system camera). */
private class TakePictureWith(private val pkg: String) : androidx.activity.result.contract.ActivityResultContract<android.net.Uri, Boolean>() {
    override fun createIntent(context: Context, input: android.net.Uri) =
        android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, input)
            .addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { if (pkg.isNotEmpty()) setPackage(pkg) }
    override fun parseResult(resultCode: Int, intent: android.content.Intent?) = resultCode == android.app.Activity.RESULT_OK
}

/** "Score from photo": camera (system or a chosen app) or the gallery, as set under Settings → Photo scoring. */
@Composable
fun PhotoButton(state: AppState, vm: SightViewModel, enabled: Boolean, onPhoto: (java.io.File) -> Unit) {
    val context = LocalContext.current
    var pendingPhoto by remember { mutableStateOf<java.io.File?>(null) }
    val cameraApp = state.photo.cameraApp
    val takePicture = androidx.activity.compose.rememberLauncherForActivityResult(remember(cameraApp) { TakePictureWith(cameraApp) }) { ok ->
        val f = pendingPhoto; pendingPhoto = null
        if (ok && f != null && f.exists() && f.length() > 0) onPhoto(f) else f?.delete()
    }
    // Alternative for phones whose camera app fails on the capture request: pick the photo from the gallery
    val pickPhoto = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        val f = pendingPhoto; pendingPhoto = null
        if (uri != null && f != null) {
            val ok = runCatching { context.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } } != null }.getOrDefault(false)
            if (ok && f.length() > 0) onPhoto(f) else { f.delete(); vm.toast(tr("Could not read the picture."), true) }
        } else f?.delete()
    }
    SecondaryButton(tr("Score from photo"), Modifier.fillMaxWidth(), enabled = enabled) {
        val dir = java.io.File(context.cacheDir, "photos").apply { mkdirs() }
        val f = java.io.File(dir, "photo_${System.currentTimeMillis()}.jpg")
        pendingPhoto = f
        if (state.photo.source == "gallery") {
            runCatching { pickPhoto.launch("image/*") }.onFailure { vm.toast(tr("No gallery app found."), true); pendingPhoto = null }
        } else {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            runCatching { takePicture.launch(uri) }.onFailure { vm.toast(if (cameraApp.isEmpty()) tr("No camera app found.") else tr("The chosen camera app could not be started."), true); pendingPhoto = null }
        }
    }
}

/** Photo scoring only (no sight): the photo button, the scores it produced and a Done button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoOnlyScreen(state: AppState, ctl: SightController, vm: SightViewModel, onPhoto: (java.io.File) -> Unit) {
    val uv = LocalUv.current
    UvCard {
        Text(tr("Scoring from photos"), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = uv.ink)
        Spacer(Modifier.height(6.dp))
        Text(tr("Photograph the face with the arrows in it, mark the face and the arrows, and the rings are scored. ") +
            if (state.photo.collect) tr("Every scored photo is kept as training data (Settings → Photo scoring → Export).")
            else tr("Turn on Collect training data under Settings → Photo scoring if these photos should help train the arrow detector."), color = uv.muted, fontSize = 14.sp)
    }
    FlowRow(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp).heightIn(min = 58.dp)
        .border(2.dp, uv.line, SmallShape).padding(8.dp), verticalArrangement = Arrangement.Center) {
        if (state.entries.isEmpty()) Text(tr("The scores of the last photo appear here"), color = uv.muted, modifier = Modifier.padding(6.dp))
        else {
            for (v in state.entries) Chip(v)
            Text(state.entries.sumOf { points(it) }.toString(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 12.dp))
        }
    }
    PhotoButton(state, vm, enabled = true, onPhoto = onPhoto)
    Spacer(Modifier.height(10.dp))
    PrimaryButton(tr("Done"), modifier = Modifier.fillMaxWidth().height(54.dp), enabled = state.entries.isNotEmpty()) { ctl.clearEntries() }
    Spacer(Modifier.height(24.dp))
}

/** The aim trace of one shot: the pin's path over the last 1.9 s before the release (cant sideways, angle up/down), plus hold and release figures. */
@Composable
fun TraceDialog(trace: de.uvsight.core.AimTrace, shot: de.uvsight.core.ShotInfo?, onClose: () -> Unit) {
    val uv = LocalUv.current
    val tr = trace
    WideDialog(onDismiss = onClose, title = { Text(tr("Shot {i} of end {end}", "i" to (tr.i + 1), "end" to tr.end)) },
        buttons = { TextButton(onClick = onClose) { Text(tr("Close")) } }) {
            Column {
                if (tr.pitch.size < 3) Text(tr("No aim trace for this shot (the aiming angle needs the cant calibration and a running session)."), color = uv.muted)
                else {
                    val n = tr.pitch.size
                    val hold = tr.ms.indices.filter { tr.ms[it] in 150..1200 }
                    val cp = if (hold.isNotEmpty()) hold.map { tr.pitch[it] }.average() else tr.pitch.average()
                    val cc = if (hold.isNotEmpty()) hold.map { tr.cant[it] }.average() else tr.cant.average()
                    val span = maxOf(0.5, tr.pitch.maxOf { kotlin.math.abs(it - cp) }, tr.cant.maxOf { kotlin.math.abs(it - cc) }) * 1.15
                    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(240.dp)) {
                        val r = minOf(size.width, size.height) / 2 * 0.95f
                        val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
                        for (k in 1..3) drawCircle(uv.line, r * k / 3, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                        drawLine(uv.line, androidx.compose.ui.geometry.Offset(c.x - r, c.y), androidx.compose.ui.geometry.Offset(c.x + r, c.y), 1f)
                        drawLine(uv.line, androidx.compose.ui.geometry.Offset(c.x, c.y - r), androidx.compose.ui.geometry.Offset(c.x, c.y + r), 1f)
                        fun pt(i: Int) = androidx.compose.ui.geometry.Offset(c.x + ((tr.cant[i] - cc) / span * r).toFloat(), c.y - ((tr.pitch[i] - cp) / span * r).toFloat())
                        for (i in 1 until n) {
                            val a = i.toFloat() / n                           // old = faint, new = strong
                            drawLine(uv.ink.copy(alpha = 0.15f + 0.85f * a), pt(i - 1), pt(i), 2.dp.toPx())
                        }
                        drawCircle(uv.red, 5.dp.toPx(), pt(n - 1))             // the release
                        drawCircle(uv.gold, 3.dp.toPx(), pt(0))
                    }
                    Text(tr("Rings: {span}° apart. Sideways is the cant, up/down the aiming angle, relative to the hold. Gold dot: {ms} s before the release, red dot: the release.", "span" to (fmt(span / 3, 1)), "ms" to (fmt(tr.ms.first() / 1000.0, 1))), color = uv.muted, fontSize = 12.sp)
                    val facts = ArrayList<String>()
                    shot?.holdMs?.let { facts.add(tr("held steady {v} s", "v" to (fmt(it / 1000.0, 1)))) }
                    shot?.hold?.let { facts.add(tr("hold ±{v}°", "v" to (fmt(it, 2)))) }
                    shot?.drop?.let { if (it < -0.2) facts.add(tr("sank {v}° before the release", "v" to (fmt(-it, 2)))) }
                    shot?.rate?.let { facts.add(tr("turned {v}°/s at release", "v" to (fmt(it, 0)))) }
                    if (facts.isNotEmpty()) Text(facts.joinToString(", ").replaceFirstChar { it.uppercase() } + ".", color = uv.ink, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
}
