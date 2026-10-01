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
    if (off) { EmptyBox("The shot counter is off.") { PrimaryButton("Turn shot counter on") { ctl.counterOn() } }; return }
    if (idle) { EmptyBox("No session running. The first shot starts one automatically.") { SecondaryButton("Start session now") { ctl.startSession() } }; return }
    if (!run) { EmptyBox("Connect to the sight on the Status tab first."); return }

    val dash = "–"
    UvCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(if (known) "End ${ses!!.end}" else "Scoring", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = uv.ink)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (known) ses!!.endShots.toString() else dash, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = uv.ink)
                Spacer(Modifier.width(4.dp))
                Text(if (known && ses!!.endShots == 1) "shot counted" else "shots counted", color = uv.muted)
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
            Text(if (le.valid) "Last end: ${le.sum} points from ${le.arrows} arrows, average ${fmt(le.avg ?: 0.0, 2)}, ${le.x} X"
                 else "Last end: invalid, ${le.arrows} arrows (${le.reason})", color = uv.muted, fontSize = 14.sp)
        }
        if (!connected) { Spacer(Modifier.height(10.dp)); Text("Not connected. Keep scoring: the app reconnects when you tap Save.", color = uv.muted, fontSize = 14.sp) }
    }

    // Distance
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Distance", color = uv.muted)
        Spacer(Modifier.width(10.dp))
        RoundButton("−") { ctl.distMinus() }
        Text("${state.distM} m", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
        if (state.distNote.isNotEmpty()) Text(state.distNote, color = uv.muted, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        RoundButton("+") { ctl.distPlus() }
        Spacer(Modifier.weight(1f))
        Text(state.lastAng, color = uv.muted, fontSize = 13.sp)
    }

    // Entry row
    FlowRow(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp).heightIn(min = 58.dp)
        .border(2.dp, uv.line, SmallShape).padding(8.dp), verticalArrangement = Arrangement.Center) {
        if (state.entries.isEmpty()) Text("Tap the scores of this end", color = uv.muted, modifier = Modifier.padding(6.dp))
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
            border = androidx.compose.foundation.BorderStroke(2.dp, uv.line), colors = ButtonDefaults.outlinedButtonColors(contentColor = uv.ink)) { Text("↶ Undo", fontWeight = FontWeight.SemiBold) }
    }

    Spacer(Modifier.height(14.dp))
    PrimaryButton(if (state.reconnecting) "Connecting…" else if (state.awaitingEnd) "Saving…" else "Save end (${state.entries.size})",
        modifier = Modifier.fillMaxWidth().height(54.dp), enabled = !busy && state.entries.isNotEmpty()) { ctl.sendEnd() }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton("Skip end", Modifier.weight(1f), enabled = !busy && known && (ses?.endShots ?: 0) > 0) { askSkip = true }
        SecondaryButton("End session", Modifier.weight(1f), enabled = !busy && known) { askStop = true }
    }
    Spacer(Modifier.height(24.dp))

    if (askSkip) {
        val n = ses?.endShots ?: 0
        AlertDialog(onDismissRequest = { askSkip = false }, title = { Text("Skip this end?") },
            text = { Text("The $n counted ${if (n == 1) "arrow is" else "arrows are"} stored as invalid with 0 points and left out of the average.") },
            confirmButton = { TextButton(onClick = { askSkip = false; ctl.skipEnd() }) { Text("Skip end", color = uv.red) } },
            dismissButton = { TextButton(onClick = { askSkip = false }) { Text("Cancel") } })
    }
    if (askStop) {
        var text = "The session is saved to the sight's log."
        if (state.entries.isNotEmpty()) text += " Scores you haven't saved yet are lost."
        if ((ses?.endShots ?: 0) > 1) text += " The ${ses!!.endShots} shots of the open end are stored as invalid."
        AlertDialog(onDismissRequest = { askStop = false }, title = { Text("End session?") }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { askStop = false; ctl.endSession() }) { Text("End session", color = uv.red) } },
            dismissButton = { TextButton(onClick = { askStop = false }) { Text("Cancel") } })
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
            if (ok && f.length() > 0) onPhoto(f) else { f.delete(); vm.toast("Could not read the picture.", true) }
        } else f?.delete()
    }
    SecondaryButton("Score from photo", Modifier.fillMaxWidth(), enabled = enabled) {
        val dir = java.io.File(context.cacheDir, "photos").apply { mkdirs() }
        val f = java.io.File(dir, "photo_${System.currentTimeMillis()}.jpg")
        pendingPhoto = f
        if (state.photo.source == "gallery") {
            runCatching { pickPhoto.launch("image/*") }.onFailure { vm.toast("No gallery app found.", true); pendingPhoto = null }
        } else {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            runCatching { takePicture.launch(uri) }.onFailure { vm.toast(if (cameraApp.isEmpty()) "No camera app found." else "The chosen camera app could not be started.", true); pendingPhoto = null }
        }
    }
}

/** Photo scoring only (no sight): the photo button, the scores it produced and a Done button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoOnlyScreen(state: AppState, ctl: SightController, vm: SightViewModel, onPhoto: (java.io.File) -> Unit) {
    val uv = LocalUv.current
    UvCard {
        Text("Scoring from photos", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = uv.ink)
        Spacer(Modifier.height(6.dp))
        Text("Photograph the face with the arrows in it, mark the face and the arrows, and the rings are scored. " +
            if (state.photo.collect) "Every scored photo is kept as training data (Settings → Photo scoring → Export)."
            else "Turn on Collect training data under Settings → Photo scoring if these photos should help train the arrow detector.", color = uv.muted, fontSize = 14.sp)
    }
    FlowRow(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp).heightIn(min = 58.dp)
        .border(2.dp, uv.line, SmallShape).padding(8.dp), verticalArrangement = Arrangement.Center) {
        if (state.entries.isEmpty()) Text("The scores of the last photo appear here", color = uv.muted, modifier = Modifier.padding(6.dp))
        else {
            for (v in state.entries) Chip(v)
            Text(state.entries.sumOf { points(it) }.toString(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 12.dp))
        }
    }
    PhotoButton(state, vm, enabled = true, onPhoto = onPhoto)
    Spacer(Modifier.height(10.dp))
    PrimaryButton("Done", modifier = Modifier.fillMaxWidth().height(54.dp), enabled = state.entries.isNotEmpty()) { ctl.clearEntries() }
    Spacer(Modifier.height(24.dp))
}
