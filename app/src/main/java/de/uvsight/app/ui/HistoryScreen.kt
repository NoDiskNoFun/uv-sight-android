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
