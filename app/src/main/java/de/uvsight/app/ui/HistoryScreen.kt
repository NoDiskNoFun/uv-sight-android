package de.uvsight.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.text.drawText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uvsight.app.SightViewModel
import de.uvsight.core.AppState
import de.uvsight.core.ArchiveSession
import de.uvsight.core.EndDetail
import de.uvsight.core.SightController
import de.uvsight.core.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import de.uvsight.core.tr

fun fmtStamp(ms: Long): String = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ms))
fun fmtDate(r: ArchiveSession): String = r.startedAt?.let { fmtStamp(it) } ?: tr("Before {time}", "time" to fmtStamp(r.importedAt))

@Composable
fun HistoryScreen(state: AppState, ctl: SightController, vm: SightViewModel) {
    val uv = LocalUv.current
    val context = LocalContext.current
    var archer by remember { mutableStateOf<String?>(null) }     // trainer: only this archer's sessions (null = all)
    val archers = remember(state.sessions) { state.sessions.mapNotNull { it.ownerName?.takeIf { n -> n.isNotEmpty() } }.distinct().sorted() }
    val list = state.sessions.filter { !it.hidden || state.showHidden }.filter { archer == null || it.ownerName == archer }
        .sortedWith(compareByDescending<ArchiveSession> { it.sortTime }.thenByDescending { it.id ?: 0 })
    var shown by remember { mutableStateOf<ArchiveSession?>(null) }
    var removing by remember { mutableStateOf<ArchiveSession?>(null) }
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { runCatching { context.contentResolver.openOutputStream(it)?.use { o -> o.write(ctl.exportCsv().toByteArray()) } }.onFailure { e -> vm.toast(tr("Export failed: {message}", "message" to e.message), true) } }
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { runCatching { context.contentResolver.openOutputStream(it)?.use { o -> o.write(ctl.exportBackup().toByteArray()) } }.onFailure { e -> vm.toast(tr("Backup failed: {message}", "message" to e.message), true) } }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val name = it.lastPathSegment ?: ""
            val text = runCatching { context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() } }.getOrNull()
            if (text == null) vm.toast(tr("Could not read the file."), true) else ctl.importText(name, text)
        }
    }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        item {
            if (!state.autoCopy) Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(tr("Get from sight"), Modifier.weight(1f)) { ctl.requestLogNow() }
                SecondaryButton(tr("Copy to sight"), Modifier.weight(1f), enabled = state.copyProgress.isEmpty()) { ctl.copyToSight() }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(tr("CSV"), Modifier.weight(1f), enabled = list.isNotEmpty()) { csvLauncher.launch("nocked-ends-$today.csv") }
                SecondaryButton(tr("Backup"), Modifier.weight(1f)) { backupLauncher.launch("nocked-backup-$today.json") }
                SecondaryButton(tr("Import"), Modifier.weight(1f)) { importLauncher.launch(arrayOf("application/json", "text/csv", "text/comma-separated-values", "text/plain", "*/*")) }
            }
            if (archers.isNotEmpty() && (state.group.role == "trainer" || archers.size > 1)) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 10.dp)) {
                FilterChip(selected = archer == null, onClick = { archer = null }, label = { Text(tr("All archers")) }, modifier = Modifier.padding(end = 6.dp))
                for (a in archers) FilterChip(selected = archer == a, onClick = { archer = if (archer == a) null else a }, label = { Text(a) }, modifier = Modifier.padding(end = 6.dp))
            }
            if (state.syncLine.isNotEmpty()) Text(state.syncLine, color = uv.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
            if (state.copyProgress.isNotEmpty()) Text(state.copyProgress, color = uv.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
            if (state.game.enabled) { BadgesCard(state, ctl, vm, manage = false); Spacer(Modifier.height(14.dp)) }
            if (list.isNotEmpty()) {
                val shown = list.filter { !it.hidden }
                UvCard(padding = 14) {
                    Text(tr("Progress"), fontWeight = FontWeight.SemiBold)
                    TrendsCard(shown, state.ends)
                }
                Spacer(Modifier.height(14.dp))
            }
        }
        if (list.isEmpty()) item { EmptyBox(tr("No sessions yet. Finished sessions appear here once the app has been connected to the sight.")) }
        else item {
            UvCard(padding = 0) {
                list.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = uv.line)
                    Box(Modifier.fillMaxWidth().clickable { shown = r }) {
                    val sightOf = state.sights.firstOrNull { it.id == r.sightId }
                    if (sightOf != null || state.sights.size > 1) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp)) { SightBadge(sightOf, unknown = sightOf == null) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(fmtDate(r) + (r.ownerName?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), fontWeight = FontWeight.SemiBold, color = if (r.hidden) uv.muted else uv.ink)
                            Text(tr("{ends} {ends2}, {scored} arrows, {x} X, {r} min", "ends" to r.ends, "ends2" to (if (r.ends == 1) tr("end") else tr("ends")), "scored" to r.scored, "x" to r.x, "r" to r.min), color = uv.muted, fontSize = 14.sp)
                            val flags = ArrayList<String>()
                            if (r.invalidEnds > 0) flags.add(tr("{invalidEnds} invalid {invalidEnds2} ({invalidArrows} arrows)", "invalidEnds" to r.invalidEnds, "invalidEnds2" to (if (r.invalidEnds == 1) tr("end") else tr("ends")), "invalidArrows" to r.invalidArrows))
                            if (r.mismatch && r.invalidEnds == 0) flags.add(tr("counts corrected by hand"))
                            if (flags.isNotEmpty()) Text(flags.joinToString(", "), color = uv.red, fontSize = 13.sp)
                            if (r.hidden) Text(tr("removed from this phone"), color = uv.muted, fontSize = 13.sp)
                        }
                        Text(fmt(r.avg, 2), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = if (r.hidden) uv.muted else uv.ink, modifier = Modifier.padding(top = if (sightOf != null || state.sights.size > 1) 10.dp else 0.dp))
                    }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    shown?.let { r ->
        SessionDialog(r, state.ends[r.key] ?: emptyList(), ctl, state.sights.firstOrNull { it.id == r.sightId }, onClose = { shown = null }, onRemove = { shown = null; removing = r })
    }
    removing?.let { r ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text(tr("Remove this session?")) },
            text = {
                Column {
                    Text(fmtDate(r) + tr(": {ends} ends, {scored} arrows, average {avg}.", "ends" to r.ends, "scored" to r.scored, "avg" to (fmt(r.avg, 2))), color = uv.muted)
                    Spacer(Modifier.height(12.dp))
                    if (!r.hidden) PrimaryButton(tr("Remove from phone"), Modifier.fillMaxWidth()) { removing = null; ctl.removeFromPhone(r) }
                    Text(tr("The sight keeps its copy as a backup."), color = uv.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                    if (r.hasSightKey) {
                        SecondaryButton(tr("Remove completely"), Modifier.fillMaxWidth(), danger = true) { removing = null; ctl.removeCompletely(r) }
                        Text(tr("Also deleted on the sight. Only your backup files still have it."), color = uv.muted, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { removing = null }) { Text(tr("Cancel")) } })
    }
}

@Composable
private fun SessionDialog(r: ArchiveSession, ends: List<EndDetail>, ctl: SightController, sightOf: de.uvsight.core.SightInfo?, onClose: () -> Unit, onRemove: () -> Unit) {
    val uv = LocalUv.current
    WideDialog(onDismiss = onClose,
        title = { Row(verticalAlignment = Alignment.CenterVertically) { Text(fmtDate(r), Modifier.weight(1f)); SightBadge(sightOf) } },
        buttons = {
            if (r.hidden) TextButton(onClick = { onClose(); ctl.unhideSession(r) }) { Text(tr("Show again")) }
            TextButton(onClick = onRemove) { Text(tr("Remove…")) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text(tr("Close")) }
        }) {
            Column {
                val lines = ArrayList<String>()
                lines.add(tr("{ends} ends, {scored} arrows scored, average {avg}, {x} X, {r} min.", "ends" to r.ends, "scored" to r.scored, "avg" to (fmt(r.avg, 2)), "x" to r.x, "r" to r.min))
                if (r.invalidEnds > 0) lines.add(tr("{invalidEnds} invalid ends with {invalidArrows} arrows.", "invalidEnds" to r.invalidEnds, "invalidArrows" to r.invalidArrows))
                if (r.mismatch) lines.add(tr("Counts corrected by hand."))
                lines.add(if (r.manual) tr("Ended by hand.") else tr("Ended automatically."))
                Text(lines.joinToString(" "), color = uv.muted, fontSize = 14.sp)
                val sessionHints = remember(r.key, ends.size) { ctl.sessionHints(r.key) }
                for (h in sessionHints) Text("• $h", color = uv.ink, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                if (ends.isEmpty()) Note(tr("No end details: this session was not scored in this app."))
                else {
                    GroupTitle(tr("Points per end"))
                    EndsBars(ends)
                    Note(tr("Bar height is the average per arrow, the number above is the end's total. Dashed: invalid end."))
                    if (ends.any { it.dist != null || it.ang != null || it.cant != null }) {
                        GroupTitle(tr("Ends"))
                        Row(Modifier.fillMaxWidth()) { listOf(tr("End"), tr("Distance"), tr("Points"), tr("Angle"), tr("Cant")).forEach { Text(it, Modifier.weight(1f), color = uv.muted, fontSize = 12.sp) } }
                        for (e in ends) {
                            HorizontalDivider(color = uv.line)
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(e.n.toString(), Modifier.weight(1f), fontSize = 13.sp)
                                Text(e.dist?.let { "$it m" } ?: "–", Modifier.weight(1f), fontSize = 13.sp)
                                Text(if (e.valid) e.sum.toString() else tr("invalid"), Modifier.weight(1f), fontSize = 13.sp)
                                Text(e.ang?.let { fmt(it, 2) + "° ±" + fmt(e.angSd ?: 0.0, 2) } ?: "–", Modifier.weight(1f), fontSize = 13.sp)
                                Text(e.cant?.let { fmt(abs(it), 1) + "°" + (if ((e.canted ?: 0) > 0) tr(" {canted} over", "canted" to e.canted) else "") } ?: "–", Modifier.weight(1f), fontSize = 13.sp)
                            }
                            val eh = remember(e) { ctl.endHints(e) }
                            if (eh.isNotEmpty()) Text(eh.joinToString(" "), color = uv.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        Note(tr("Cant: average at release; \"over\" counts arrows outside your cant tolerance."))
                    }
                    if (ends.any { !it.hits.isNullOrEmpty() }) {
                        GroupTitle(tr("Hits (from photos)"))
                        FacePlot(ends.flatMap { e -> (e.hits ?: emptyList()).mapIndexed { i, h ->
                            val shot = e.shotOf?.getOrNull(i)?.let { s -> "·" + (s + 1) + (if (e.shotSrc?.getOrNull(i) == "coin") "?" else "") } ?: ""
                            h to "${e.n}$shot" } })
                        Note(tr("One dot per arrow at its position on the face: end·shot. A \"?\" marks a shot the matching could not tell apart."))
                    }
                    val count = HashMap<String, Int>(); var total = 0
                    for (e in ends) for (v in e.scores ?: emptyList()) { count[v] = (count[v] ?: 0) + 1; total++ }
                    if (total > 0) {
                        GroupTitle(tr("Arrows by score ({total} arrows)", "total" to total))
                        val mx = count.values.max()
                        for (v in listOf("X", "10", "9", "8", "7", "6", "5", "4", "3", "2", "1", "M")) {
                            val c = count[v] ?: 0
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(v, Modifier.width(34.dp), fontSize = 13.sp)
                                Box(Modifier.weight(1f)) {
                                    Box(Modifier.fillMaxWidth(max(0.02f, c.toFloat() / mx)).height(12.dp).background(if (v == "M") uv.line else Ring.colorFor(v).let { if (it == Ring.black) Color(0xFF555555) else if (it == Ring.white) Color(0xFFBFC5BD) else it }, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)))
                                }
                                Text(c.toString(), Modifier.width(30.dp), color = uv.muted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                            }
                        }
                    }
                }
            }
        }
}

/** All hits of a session on a 10-ring face, x right / y up in mm, scaled to the widest hit. */
@Composable
private fun FacePlot(hits: List<Pair<de.uvsight.core.Hit, String>>) {
    val uv = LocalUv.current
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val maxMm = max(60.0, hits.maxOf { hypot(it.first.mmX, it.first.mmY) } * 1.15)
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val r = min(size.width, size.height) / 2 * 0.95f
        val c = Offset(size.width / 2, size.height / 2)
        val ringMm = maxMm / 10.0   // draw 10 rings scaled so the widest hit still shows
        val colors = listOf(Ring.gold, Ring.gold, Ring.red, Ring.red, Ring.blue, Ring.blue, Color(0xFF555555), Color(0xFF555555), Color(0xFFBFC5BD), Color(0xFFBFC5BD))
        for (k in 10 downTo 1) drawCircle(colors[10 - k].copy(alpha = 0.35f), (r * k / 10), c)
        for (k in 1..10) drawCircle(uv.line, r * k / 10, c, style = Stroke(1f))
        val small = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = uv.ink)
        for ((h, n) in hits) {
            val p = Offset(c.x + (h.mmX / maxMm * r).toFloat(), c.y - (h.mmY / maxMm * r).toFloat())
            drawCircle(uv.ink, 5.dp.toPx(), p); drawCircle(Color.White, 3.dp.toPx(), p)
            val tl = textMeasurer.measure(n, small); drawText(tl, topLeft = p + Offset(6.dp.toPx(), -tl.size.height / 2f))
        }
        drawCircle(uv.ink, 2f, c)
        val note = textMeasurer.measure("outer ring = ${fmt(ringMm * 10 / 10, 0)} mm", androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = uv.muted))
        drawText(note, topLeft = Offset(0f, size.height - note.size.height))
    }
}

@Composable
private fun EndsBars(ends: List<EndDetail>) {
    val uv = LocalUv.current
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val l = 26.dp.toPx(); val rgt = 6.dp.toPx(); val t = 14.dp.toPx(); val b = 22.dp.toPx()
        val n = ends.size; val slot = (size.width - l - rgt) / n; val bw = min(28.dp.toPx(), slot * 0.7f)
        val y = { v: Double -> (t + (1 - v / 10) * (size.height - t - b)).toFloat() }
        for (v in listOf(0.0, 5.0, 10.0)) drawLine(uv.line, Offset(l, y(v)), Offset(size.width - rgt, y(v)), 1f)
        val small = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = uv.muted)
        ends.forEachIndexed { i, e ->
            val cx = l + slot * (i + 0.5f)
            val sum = e.sum
            if (e.valid && e.arrows > 0 && sum != null) {
                val avg = sum.toDouble() / e.arrows
                drawRect(Ring.forAvg(avg), Offset(cx - bw / 2, y(avg)), Size(bw, y(0.0) - y(avg)))
                val tl = textMeasurer.measure(sum.toString(), small)
                drawText(tl, topLeft = Offset(cx - tl.size.width / 2, y(avg) - tl.size.height - 2))
            } else {
                drawRect(uv.line, Offset(cx - bw / 2, y(10.0)), Size(bw, y(0.0) - y(10.0)), style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
            }
            if (n <= 16 || i % 2 == 0) { val tl = textMeasurer.measure(e.n.toString(), small); drawText(tl, topLeft = Offset(cx - tl.size.width / 2, size.height - tl.size.height)) }
        }
    }
}

/** Trends over the last sessions: group size, hold steadiness, cant consistency (one line each, newest right). */
@Composable
private fun TrendsCard(list: List<ArchiveSession>, endsStore: Map<String, List<EndDetail>>) {
    val uv = LocalUv.current
    val rows = list.sortedBy { it.sortTime }.mapNotNull { s -> endsStore[s.key]?.takeIf { it.isNotEmpty() }?.let { de.uvsight.core.Insights.sessionStats(it) } }.takeLast(12)
    val avgs: List<Double?> = list.filter { it.scored > 0 }.sortedBy { it.sortTime }.takeLast(12).map { it.avg }
    val series = listOf(
        Triple(tr("Average per session"), avgs, ""),
        Triple(tr("Group size"), rows.map { it.groupRadius }, "cm"),
        Triple(tr("Hold steadiness"), rows.map { it.hold }, "°"),
        Triple(tr("Cant consistency"), rows.map { it.cantSd }, "°"),
        Triple(tr("Hold time"), rows.map { it.holdMs?.div(1000.0) }, "s"),
    ).filter { (_, v, _) -> v.count { it != null } >= 2 }
    if (series.isEmpty()) return
    Column {
        Spacer(Modifier.height(6.dp))
        Text(tr("Trends over the last sessions"), color = uv.muted, fontSize = 13.sp)
        for ((label, values, unit) in series) {
            val vals = values.map { it ?: Double.NaN }
            val present = vals.filter { !it.isNaN() }
            val last = present.last()
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(120.dp)) {
                    Text(label, fontSize = 13.sp, color = uv.ink)
                    Text(tr("now {unit} {unit2}", "unit" to (fmt(last, if (unit == "°" || unit == "") 2 else 1)), "unit2" to unit), fontSize = 12.sp, color = uv.muted)
                }
                Canvas(Modifier.weight(1f).height(36.dp)) {
                    val lo = present.min(); val hi = present.max(); val range = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
                    var prev: Offset? = null
                    for ((i, v) in vals.withIndex()) {
                        if (v.isNaN()) { prev = null; continue }
                        val p = Offset(if (vals.size > 1) size.width * i / (vals.size - 1) else size.width / 2, size.height - ((v - lo) / range * (size.height - 6)).toFloat() - 3)
                        prev?.let { drawLine(uv.gold, it, p, 2.dp.toPx()) }
                        drawCircle(uv.ink, 2.5.dp.toPx(), p)
                        prev = p
                    }
                }
            }
        }
        Note(tr("Lower is better for group size, hold steadiness and cant consistency. Group size is the mean distance of the arrows from their centre (from photos)."))
    }
}
