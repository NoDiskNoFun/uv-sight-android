package de.uvsight.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uvsight.core.AppState
import de.uvsight.core.ConnState
import de.uvsight.core.SightController
import de.uvsight.core.fmt
import de.uvsight.core.fmtDuration
import kotlin.math.ceil
import de.uvsight.core.tr

@Composable
fun StatusScreen(state: AppState, ctl: SightController, connect: () -> Unit) {
    val uv = LocalUv.current
    state.protoWarning?.let { Banner(it) }
    if (!state.hasData) {
        EmptyBox(if (state.conn == ConnState.CONNECTED) tr("Connected. Waiting for data from the sight…") else tr("Move the bow so the sight wakes up, then connect.")) {
            if (state.conn != ConnState.CONNECTED && state.sights.size > 1) {
                Text(tr("Connect to"), color = uv.muted, fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                    FilterChip(selected = state.preferredSightId == null, onClick = { ctl.preferSight(null) }, label = { Text(tr("Whichever is awake")) }, modifier = Modifier.padding(end = 6.dp))
                    for (si in state.sights) FilterChip(selected = state.preferredSightId == si.id, onClick = { ctl.preferSight(si.id) },
                        label = { Text(si.label) }, leadingIcon = { Box(Modifier.size(10.dp).background(sightColor(si), CircleShape)) }, modifier = Modifier.padding(end = 6.dp))
                }
            }
            if (state.conn != ConnState.CONNECTED) PrimaryButton(tr("Connect"), enabled = state.conn != ConnState.CONNECTING, onClick = connect)
        }
        return
    }
    val s = state.status!!
    if (s.lowbat) Banner(if (state.hasLed) tr("Battery empty. The LED is off until you charge.") else tr("Battery empty. Charge the sight."))
    val stale = state.conn != ConnState.CONNECTED
    val alpha = if (stale) 0.45f else 1f

    // Battery gauge
    val pct = s.pct.coerceIn(0, 100)
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(260.dp).aspectRatio(1f), contentAlignment = Alignment.Center) {
            val ringColor = if (pct <= 15) uv.red else uv.gold
            Canvas(Modifier.size(260.dp)) {
                val r = size.minDimension / 2
                val c = Offset(r, r)
                drawCircle(uv.surface.copy(alpha = alpha), r * 52 / 60, c)
                drawCircle(uv.line.copy(alpha = alpha), r * 40 / 60, c, style = Stroke(1.5.dp.toPx()))
                drawCircle(uv.line.copy(alpha = alpha), r * 28 / 60, c, style = Stroke(1.5.dp.toPx()))
                val ringR = r * 54 / 60
                val stroke = Stroke(8.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(uv.line.copy(alpha = alpha), ringR, c, style = Stroke(8.dp.toPx()))
                drawArc(ringColor.copy(alpha = alpha), -90f, 360f * pct / 100f, false, Offset(c.x - ringR, c.y - ringR), Size(ringR * 2, ringR * 2), style = stroke)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$pct%", fontSize = 56.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, color = uv.ink.copy(alpha = alpha))
                Text(fmt(s.vbat, 2) + " V", color = uv.muted, fontSize = 15.sp)
            }
        }
    }

    UvCard(padding = 0) {
        val ses = state.session
        val sesText = when {
            ses == null || !ses.counter -> tr("Shot counter off")
            !ses.active -> tr("No session")
            else -> tr("End {end}, {endShots} {endShots2}", "end" to ses.end, "endShots" to ses.endShots, "endShots2" to (if (ses.endShots == 1) tr("shot") else tr("shots")))
        }
        ReadingRow(tr("Session"), sesText, first = true)
        val ledText = when (s.led) { "on" -> "On"; "off" -> tr("Off"); "blinking" -> tr("Blinking"); "warning" -> tr("Distance warning"); "pulsing" -> tr("Pulsing, aim fits"); else -> s.led } +
            (if (s.led == "on") ", ${s.bright} %" else "")
        val bm = state.cfgValue("bright_mode")
        val modeText = when (s.mode) { "on" -> tr("switched on"); "off" -> tr("switched off"); else -> if (bm == null) "" else if (bm == 1.0) "auto" else "fixed" }
        if (state.hasLed) {
            ReadingRow(tr("UV light"), ledText, modeText)
            ReadingRow(tr("Ambient light"), if (s.dark) tr("Dark") else tr("Bright"), s.light.toString())
        }
        val lv = state.level
        val tilt = s.tilt
        val tiltText = when {
            tilt != null -> fmt(tilt, 1) + "°"
            lv == null || lv.mode == "off" -> tr("Off")
            !lv.cal -> tr("Not calibrated")
            lv.mode == "auto" -> tr("Waits for a session")
            else -> tr("Waiting for a reading")
        }
        val lvDetail = if (lv != null && lv.mode != "off" && !(tilt == null && lv.mode == "off")) "${lv.mode}, ${lv.style}" else ""
        ReadingRow(tr("Cant"), tiltText, lvDetail)
        val tap = state.cfgValue("tap_ths")
        val thr = if (tap == null) "" else tr("threshold {tap} g", "tap" to (fmt(ceil(tap / 2) * 0.5, 1)))
        val shotG = s.lastShotG
        ReadingRow(tr("Last shot"), if (shotG != null) (if (s.lastShotClip) "≥ 16 g" else fmt(shotG, 1) + " g") else tr("No shot yet"), thr)
        val full = state.bat?.full?.takeIf { s.chg == "charging" }?.let { ", full in ${fmtDuration(it / 60.0)}" } ?: ""
        ReadingRow(tr("Charging"), ctl.chargeLabel(s.chg) + full)
        val rg = state.range
        ReadingRow(tr("Arrow speed"), if (rg?.kmh != null) tr("about {kmh} km/h", "kmh" to rg.kmh) else "–", if (rg?.kmh != null) rg.name else (if (rg?.name?.isNotEmpty() == true) rg.name + ", " else "") + tr("still learning"))
        val b = state.bat
        val bright = state.cfgValue("bright")
        val auto = state.cfgValue("bright_mode") == 1.0
        val src = if (b?.src == "measured") "measured" else "estimated"
        if (s.chg == "charging") { if (state.hasLed) ReadingRow(tr("Runtime with light"), tr("Charging")); ReadingRow(if (state.hasLed) tr("Runtime resting") else tr("Runtime"), tr("Charging")) }
        else {
            if (state.hasLed) ReadingRow(tr("Runtime with light"), if (b != null) fmtDuration(b.light) else "–", if (b != null) tr("LED {bright}{auto}, {src}", "bright" to (bright?.let { "${it.toInt()} %" } ?: ""), "auto" to (if (auto) tr(" (fully dark)") else ""), "src" to src) else "")
            ReadingRow(if (state.hasLed) tr("Runtime resting") else tr("Runtime"), if (b != null) fmtDuration(b.rest) else "–", if (b != null) (if (state.hasLed) tr("at rest, {src}", "src" to src) else src) else "")
        }
    }
    Spacer(Modifier.height(18.dp))
    if (state.conn == ConnState.OFF) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SecondaryButton(tr("Reconnect")) { ctl.reconnect() } }
    Text(tr("Runtimes get more accurate over the first weeks, as the sight learns how much power it uses."), color = uv.muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    state.hello?.let { Text(tr("{name}, firmware {fw}", "name" to it.name, "fw" to it.fw), color = uv.muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)) }
}
