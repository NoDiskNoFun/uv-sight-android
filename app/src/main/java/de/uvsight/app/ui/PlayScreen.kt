package de.uvsight.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import de.uvsight.app.SightViewModel
import de.uvsight.core.AppState
import de.uvsight.core.BadgeDef
import de.uvsight.core.Badges
import de.uvsight.core.CoreEvent
import de.uvsight.core.EarnedBadge
import de.uvsight.core.GoalDef
import de.uvsight.core.Goals
import de.uvsight.core.Metric
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import de.uvsight.core.Game
import de.uvsight.core.SightController
import de.uvsight.core.fmt
import de.uvsight.core.tr
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

private fun tzOffsetMs() = TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()

/** Avatar colour from the palette for a name: the same name always gets the same colour (used on the board too). */
fun colorForName(name: String): Color = sightColors[Math.floorMod(name.hashCode(), sightColors.size)]

@Composable
fun Avatar(name: String, color: Color, emoji: String, size: Int = 44) {
    Box(Modifier.size(size.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(emoji.ifEmpty { name.take(1).uppercase().ifEmpty { "?" } }, color = Color.White, fontSize = (size * 0.45).sp, fontWeight = FontWeight.Bold)
    }
}

/** The play-mode card on the Training tab: who, streak, today's goal, steady hand, personal bests. */
@Composable
fun PlayCard(state: AppState, ctl: SightController) {
    val uv = LocalUv.current
    val g = state.game
    val tz = remember { tzOffsetMs() }
    val now = System.currentTimeMillis()
    val sessions = state.sessions; val ends = state.ends
    val streak = remember(sessions) { Game.streakDays(sessions, now, tz) }
    val bests = remember(sessions, ends) { Game.bests(sessions, ends) }
    val noMiss = remember(sessions, ends) { Game.endsWithoutMiss(sessions, ends) }
    val lastEnd = state.currentSessionKey?.let { k -> ends[k]?.maxByOrNull { it.n } }
    val steady = lastEnd?.let { Game.steadyHand(it) }
    val name = state.group.name.ifEmpty { tr("You") }
    UvCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, sightColors[Math.floorMod(g.color, sightColors.size)], g.emoji)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = uv.ink)
                Text(if (streak > 0) tr("🔥 {n} {days} in a row", "n" to streak, "days" to (if (streak == 1) tr("day") else tr("days"))) else tr("Shoot today to start a streak"), color = uv.muted, fontSize = 13.sp)
            }
            if (noMiss >= 3) Text("🧹 $noMiss", color = uv.ink, fontSize = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        // the archer's goals: one at a time, swipe sideways for the next (they are set under Settings → Play mode)
        if (state.goals.isEmpty()) Text(tr("No goals yet. Add some under Settings → Play mode."), color = uv.muted, fontSize = 13.sp)
        else {
            val pager = rememberPagerState(pageCount = { state.goals.size })
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth(), pageSpacing = 12.dp) { i ->
                val goal = state.goals[i]
                val p = remember(goal, sessions, ends, state.currentSessionKey) { Goals.progress(goal, sessions, ends, now, tz, state.currentSessionKey) }
                val frac by animateFloatAsState(p.fraction, tween(600), label = "goal")
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text((goal.emoji.ifEmpty { "🎯" }) + " " + goal.title + " · " + Goals.periodLabel(goal.period), color = uv.ink, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(p.text + if (p.reached) " ✅" else "", color = if (p.reached) uv.ok else uv.muted, fontSize = 13.sp)
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).background(uv.line, CircleShape)) {
                        Box(Modifier.fillMaxWidth(frac).height(8.dp).background(if (p.reached) uv.ok else uv.gold, CircleShape))
                    }
                }
            }
            if (state.goals.size > 1) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
                for (i in state.goals.indices) Box(Modifier.padding(horizontal = 3.dp).size(if (i == pager.currentPage) 8.dp else 6.dp).background(if (i == pager.currentPage) uv.ink else uv.line, CircleShape))
            }
        }
        if (steady != null) {
            Spacer(Modifier.height(10.dp))
            val sf by animateFloatAsState(steady / 100f, tween(800), label = "steady")
            Text(tr("Steady hand, last end: {v}", "v" to steady) + (bests.steady?.let { if (steady >= it) " 🏅" else "" } ?: ""), color = uv.ink, fontSize = 14.sp)
            Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).background(uv.line, CircleShape)) {
                Box(Modifier.fillMaxWidth(sf).height(8.dp).background(if (steady >= 80) uv.ok else uv.blue, CircleShape))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat(bests.endSum?.toString() ?: "–", tr("best end"))
            Stat(bests.sessionAvg?.let { fmt(it, 2) } ?: "–", tr("best average"))
            Stat(bests.sessionX?.toString() ?: "–", tr("most X"))
            Stat(bests.steady?.toString() ?: "–", tr("steadiest"))
        }
    }
}

val BRONZE = Color(0xFFCD7F32); val SILVER = Color(0xFFA8A9AD); val GOLD = Color(0xFFD4AF37)
fun levelColor(l: Int): Color? = when (l) { 1 -> BRONZE; 2 -> SILVER; 3 -> GOLD; else -> null }

/** One badge as shown: the rule (own, from a sight, or only what was earned of a rule gone) and the level reached. */
private data class BadgeView(val def: BadgeDef, val level: Int, val earned: EarnedBadge?, val own: Boolean, val retired: Boolean)

/** All badges with their level, in the history: tap for details, edit or delete own ones, new ones, templates, share. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BadgesCard(state: AppState, ctl: SightController, vm: SightViewModel, manage: Boolean) {
    val uv = LocalUv.current
    val context = LocalContext.current
    val tz = remember { tzOffsetMs() }
    val now = System.currentTimeMillis()
    val defs = remember(state.badgeDefs, state.sightBadges) { ctl.allBadgeDefs() }
    val views = remember(defs, state.earned, state.sessions, state.ends) {
        val earnedBy = state.earned.associateBy { it.id }
        defs.map { d -> BadgeView(d, max(earnedBy[d.id]?.level ?: 0, Badges.levelReached(d, state.sessions, state.ends, tz, now)), earnedBy[d.id], state.badgeDefs.any { it.id == d.id }, false) } +
            state.earned.filter { e -> defs.none { it.id == e.id } }.map { BadgeView(it.def, it.level, it, false, true) }
    }
    var open by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<BadgeDef?>(null) }
    var templates by remember { mutableStateOf(false) }
    val saveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { runCatching { context.contentResolver.openOutputStream(it)?.use { o -> o.write(ctl.exportBadges().toByteArray()) } }.onFailure { e -> vm.toast(tr("Export failed: {message}", "message" to e.message), true) } }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val text = runCatching { context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() } }.getOrNull()
            val n = text?.let { t -> ctl.importBadges(t) }
            vm.toast(if (n == null) tr("Not a badge file.") else tr("{n} badges added", "n" to n), n == null)
        }
    }
    // in the history a plain card, in the settings part of the play-mode card
    val wrap: @Composable (@Composable ColumnScope.() -> Unit) -> Unit = { c -> if (manage) Column { c() } else UvCard { c() } }
    wrap {
        Text(tr("Badges") + " · ${views.count { it.level > 0 }}/${views.size}", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (v in views) {
                val b = v.def
                // every box the same size; the background names the level
                val bg = levelColor(v.level)?.copy(alpha = 0.45f) ?: uv.line.copy(alpha = 0.5f)
                Column(Modifier.width(96.dp).height(96.dp).background(bg, SmallShape)
                    .then(if (open == b.id) Modifier.border(2.dp, uv.ink, SmallShape) else Modifier)
                    .clickable { open = if (open == b.id) null else b.id }.padding(horizontal = 6.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {
                    Text(if (v.level > 0) b.emoji.ifEmpty { "🏅" } else "🔒", fontSize = 26.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(b.title, color = if (v.level > 0) uv.ink else uv.muted, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        views.firstOrNull { it.def.id == open }?.let { v ->
            val b = v.def
            Spacer(Modifier.height(8.dp))
            Text("${b.emoji} ${b.title}: " + Badges.describe(b), color = uv.ink, fontSize = 13.sp)
            val best = remember(b, state.sessions, state.ends) { Badges.best(b, state.sessions, state.ends, tz, now) }
            Text((best?.let { tr("Your best so far: {v}", "v" to Badges.fmtValue(b, it)) } ?: tr("No value yet.")) +
                (v.earned?.let { " · " + tr("{level} earned on {date}", "level" to Badges.levelName(it.level), "date" to fmtStamp(it.at)) } ?: "") +
                (if (v.retired) " · " + tr("no longer awarded, but earned is earned") else "") +
                (if (!v.own && !v.retired) " · " + tr("from the sight") else ""), color = uv.muted, fontSize = 12.sp)
            if (manage && v.own && state.group.role != "user") Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(tr("Edit")) { editing = b }
                SecondaryButton(tr("Delete"), danger = true) { ctl.deleteBadge(b.id); open = null }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (manage && state.group.role != "user") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(tr("New"), Modifier.weight(1f)) { editing = BadgeDef(Badges.newId(), "🏅", "", "steady", "end", "min", listOf(60.0, 70.0, 80.0)) }
            SecondaryButton(tr("Templates"), Modifier.weight(1f)) { templates = true }
            SecondaryButton(tr("Share"), Modifier.weight(1f), enabled = state.badgeDefs.isNotEmpty()) {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/json").putExtra(android.content.Intent.EXTRA_TEXT, ctl.exportBadges()).putExtra(android.content.Intent.EXTRA_SUBJECT, "Nocked badges")
                runCatching { context.startActivity(android.content.Intent.createChooser(send, tr("Share badges"))) }.onFailure { saveLauncher.launch("nocked-badges.json") }
            }
            SecondaryButton(tr("Import"), Modifier.weight(1f)) { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        }
        if (manage) Text(when (state.group.role) {
            "trainer" -> tr("Your set goes to every sight you connect to as trainer; the archers fetch it from there. Share writes the set as a file others can import.")
            "user" -> tr("The badges of your group come from the sight, the trainer manages them. They stay here when you are not connected, and what you earned stays for good.")
            else -> tr("Your own badges, kept on this phone; share them as a file or import others.")
        }, color = uv.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
    editing?.let { d -> BadgeEditor(d, state, ctl, onClose = { editing = null }) }
    if (templates) WideDialog(onDismiss = { templates = false }, title = { Text(tr("Templates")) }, buttons = { TextButton(onClick = { templates = false }) { Text(tr("Close")) } }) {
        for (t in Badges.templates()) {
            val have = defs.any { it.id == t.id || it.sameRule(t) }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.emoji, fontSize = 22.sp); Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) { Text(t.title, color = uv.ink); Text(Badges.describe(t), color = uv.muted, fontSize = 12.sp) }
                SecondaryButton(if (have) tr("Added") else tr("Add"), enabled = !have) { editing = t.copy(template = false) }
            }
        }
    }
}

/** Create or change a badge: emoji, title, what, where, direction, thresholds; with a preview from real data. */
@Composable
private fun BadgeEditor(initial: BadgeDef, state: AppState, ctl: SightController, onClose: () -> Unit) {
    val uv = LocalUv.current
    val tz = remember { tzOffsetMs() }
    val now = System.currentTimeMillis()
    var emoji by remember { mutableStateOf(initial.emoji) }
    var title by remember { mutableStateOf(initial.title) }
    var metric by remember { mutableStateOf(initial.metric) }
    var scope by remember { mutableStateOf(initial.scope) }
    var op by remember { mutableStateOf(initial.op) }
    val lv = remember { mutableStateOf(initial.levels.map { Badges.fmtValue(initial, it).trimEnd('°', 'm', 's') }.let { l -> List(3) { i -> l.getOrNull(i) ?: "" } }) }
    val m = Badges.metric(metric)
    val levels = lv.value.mapNotNull { it.replace(',', '.').toDoubleOrNull() }
    val def = BadgeDef(initial.id, emoji.trim(), title.trim(), metric, scope, op, levels)
    val valid = title.isNotBlank() && levels.isNotEmpty() && m != null && scope in (m.scopes)
    WideDialog(onDismiss = onClose, title = { Text(if (state.badgeDefs.any { it.id == initial.id }) tr("Edit badge") else tr("New badge")) }, buttons = {
        TextButton(onClick = onClose) { Text(tr("Cancel")) }
        TextButton(onClick = { ctl.saveBadge(def); onClose() }, enabled = valid) { Text(tr("Save")) }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiField(emoji, onChange = { emoji = it })
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(value = title, onValueChange = { title = it.take(18) }, singleLine = true, modifier = Modifier.weight(1f), label = { Text(tr("Title")) })
        }
        Text(tr("What"), color = uv.ink, modifier = Modifier.padding(top = 10.dp))
        MetricDropdown(Badges.METRICS, metric, { Badges.metricLabel(it) }) { mm -> metric = mm.id; op = mm.defaultOp; if (scope !in mm.scopes) scope = mm.scopes.first() }
        Text(tr("Where"), color = uv.ink, modifier = Modifier.padding(top = 6.dp))
        Row { for (sc in (m?.scopes ?: Badges.SCOPES)) FilterChip(selected = scope == sc, onClick = { scope = sc }, label = { Text(Badges.scopeLabel(sc)) }, modifier = Modifier.padding(end = 6.dp)) }
        Text(tr("Direction"), color = uv.ink, modifier = Modifier.padding(top = 6.dp))
        Row {
            FilterChip(selected = op == "min", onClick = { op = "min" }, label = { Text(tr("at least")) }, modifier = Modifier.padding(end = 6.dp))
            FilterChip(selected = op == "max", onClick = { op = "max" }, label = { Text(tr("at most")) })
        }
        Text(tr("Thresholds: bronze, silver, gold (silver and gold may stay empty)"), color = uv.ink, modifier = Modifier.padding(top = 6.dp))
        Row {
            for (i in 0..2) OutlinedTextField(value = lv.value[i], onValueChange = { v -> lv.value = lv.value.toMutableList().also { it[i] = v.take(7) } }, singleLine = true,
                modifier = Modifier.weight(1f).padding(end = if (i < 2) 6.dp else 0.dp), label = { Text(Badges.medal(i + 1)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
        }
        // the preview: this archer's history, or every archer's for a trainer
        Spacer(Modifier.height(8.dp))
        if (levels.isNotEmpty() && m != null) {
            Text(Badges.describe(def), color = uv.muted, fontSize = 13.sp)
            val byArcher = remember(state.sessions) { state.sessions.filter { !it.hidden }.groupBy { it.ownerName?.takeIf { n -> n.isNotEmpty() } } }
            if (state.group.role == "trainer" && byArcher.keys.any { it != null }) {
                for ((name, list) in byArcher.filterKeys { it != null }) {
                    val best = Badges.best(def, list, state.ends, tz, now)
                    val lvl = Badges.level(def, best)
                    Text("${Badges.medal(lvl).ifEmpty { "·" }} $name: " + (best?.let { Badges.fmtValue(def, it) } ?: "–"), color = if (lvl > 0) uv.ink else uv.muted, fontSize = 13.sp)
                }
            } else {
                val best = Badges.best(def, state.sessions, state.ends, tz, now)
                val lvl = Badges.level(def, best)
                Text(best?.let { tr("Your best so far: {v}", "v" to Badges.fmtValue(def, it)) + " · " + (if (lvl > 0) tr("you would have {level}", "level" to Badges.levelName(lvl)) else tr("not reached yet")) } ?: tr("No value for this yet."), color = uv.muted, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Confetti and a big number for a personal best or a new badge; drawn in code (no picture files),
 * goes away on its own or on a tap.
 */
@Composable
fun Celebration(e: CoreEvent.Celebrate, onDone: () -> Unit) {
    val uv = LocalUv.current
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(e) {
        val t0 = withFrameMillis { it }
        while (t < 3.8f) { t = (withFrameMillis { it } - t0) / 1000f }
        onDone()
    }
    val seed = remember(e) { Random(e.hashCode()) }
    val parts = remember(e) { List(90) { floatArrayOf(seed.nextFloat(), seed.nextFloat() * 0.6f, 0.6f + seed.nextFloat(), seed.nextFloat() * 6.28f, seed.nextInt(sightColors.size).toFloat()) } }
    val pop by animateFloatAsState(if (t > 0.05f) 1f else 0.3f, tween(500), label = "pop")
    Box(Modifier.fillMaxSize().clickable(onClick = onDone), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val fade = (1f - (t - 2.8f).coerceIn(0f, 1f))
            for (p in parts) {
                val x = (p[0] + 0.08f * sin(t * p[2] * 2f + p[3])) * size.width
                val y = (p[1] + t * p[2] * 0.35f) * size.height
                if (y > size.height) continue
                val c = sightColors[p[4].toInt()].copy(alpha = fade)
                drawRect(c, Offset(x, y), Size(10f + 6f * abs(sin(t * 3f + p[3])), 14f))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.background(uv.paper.copy(alpha = 0.92f), SmallShape).padding(28.dp)) {
            Text(e.title, color = uv.muted, fontSize = 16.sp)
            Text(e.value, color = uv.ink, fontSize = (64 * pop).sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

/** "What" as a drop-down: the list is long, chips would run off the screen. */
@Composable
fun MetricDropdown(metrics: List<Metric>, current: String, label: (String) -> String, onPick: (Metric) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SecondaryButton(label(current) + "  ▾", Modifier.fillMaxWidth()) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (m in metrics) DropdownMenuItem(text = { Text(label(m.id)) }, onClick = { onPick(m); expanded = false })
        }
    }
}

/** Create or change a goal: emoji, title, what, where, direction, target and the period. */
@Composable
fun GoalEditor(initial: GoalDef, state: AppState, ctl: SightController, onClose: () -> Unit) {
    val uv = LocalUv.current
    val tz = remember { tzOffsetMs() }
    val now = System.currentTimeMillis()
    var emoji by remember { mutableStateOf(initial.emoji) }
    var title by remember { mutableStateOf(initial.title) }
    var metric by remember { mutableStateOf(initial.metric) }
    var scope by remember { mutableStateOf(initial.scope) }
    var op by remember { mutableStateOf(initial.op) }
    var period by remember { mutableStateOf(initial.period) }
    var target by remember { mutableStateOf(Goals.fmtValue(initial, initial.target).trimEnd('°', 'm', 's')) }
    val m = Goals.metric(metric)
    val t = target.replace(',', '.').toDoubleOrNull()
    val def = GoalDef(initial.id, emoji, title.trim().ifEmpty { Goals.metricLabel(metric) }, metric, scope, op, t ?: 0.0, period)
    val existing = state.goals.any { it.id == initial.id }
    WideDialog(onDismiss = onClose, title = { Text(if (existing) tr("Edit goal") else tr("New goal")) }, buttons = {
        if (existing) TextButton(onClick = { ctl.deleteGoal(initial.id); onClose() }) { Text(tr("Delete"), color = uv.red) }
        TextButton(onClick = onClose) { Text(tr("Cancel")) }
        TextButton(onClick = { ctl.saveGoal(def); onClose() }, enabled = t != null && t > 0 && m != null) { Text(tr("Save")) }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiField(emoji, onChange = { emoji = it })
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(value = title, onValueChange = { title = it.take(24) }, singleLine = true, modifier = Modifier.weight(1f), label = { Text(tr("Title")) }, placeholder = { Text(Goals.metricLabel(metric)) })
        }
        Text(tr("What"), color = uv.ink, modifier = Modifier.padding(top = 10.dp))
        MetricDropdown(Goals.METRICS, metric, { Goals.metricLabel(it) }) { mm -> metric = mm.id; op = mm.defaultOp; if (scope !in mm.scopes) scope = mm.scopes.first() }
        if ((m?.scopes?.size ?: 0) > 1) {
            Text(tr("Where"), color = uv.ink, modifier = Modifier.padding(top = 6.dp))
            Row { for (sc in m!!.scopes) FilterChip(selected = scope == sc, onClick = { scope = sc }, label = { Text(Badges.scopeLabel(sc)) }, modifier = Modifier.padding(end = 6.dp)) }
        }
        Text(tr("Direction"), color = uv.ink, modifier = Modifier.padding(top = 6.dp))
        Row {
            FilterChip(selected = op == "min", onClick = { op = "min" }, label = { Text(tr("at least")) }, modifier = Modifier.padding(end = 6.dp))
            FilterChip(selected = op == "max", onClick = { op = "max" }, label = { Text(tr("at most")) })
        }
        Text(tr("Period"), color = uv.ink, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) { for (pp in Goals.PERIODS) FilterChip(selected = period == pp, onClick = { period = pp }, label = { Text(Goals.periodLabel(pp)) }, modifier = Modifier.padding(end = 6.dp)) }
        OutlinedTextField(value = target, onValueChange = { target = it.take(7) }, singleLine = true, modifier = Modifier.width(140.dp).padding(top = 6.dp), label = { Text(tr("Target")) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
        if (t != null && m != null) {
            Spacer(Modifier.height(8.dp))
            Text(Goals.describe(def), color = uv.muted, fontSize = 13.sp)
            val pr = Goals.progress(def, state.sessions, state.ends, now, tz, state.currentSessionKey)
            Text(tr("Right now: {text}", "text" to pr.text) + if (pr.reached) " ✅" else "", color = uv.muted, fontSize = 13.sp)
        }
    }
}
