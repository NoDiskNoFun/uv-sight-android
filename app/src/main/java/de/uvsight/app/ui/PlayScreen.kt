package de.uvsight.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import de.uvsight.core.AppState
import de.uvsight.core.CoreEvent
import de.uvsight.core.Game
import de.uvsight.core.SightController
import de.uvsight.core.fmt
import de.uvsight.core.tr
import java.util.TimeZone
import kotlin.math.abs
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
    val (done, goal) = remember(sessions, ends, g.dailyGoal) { Game.dailyGoal(sessions, ends, g.dailyGoal, now, tz) }
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
        // today's goal: ends above the archer's own average
        val frac by animateFloatAsState(if (goal == 0) 0f else (done.toFloat() / goal).coerceIn(0f, 1f), tween(600), label = "goal")
        Text(tr("Today: {done} of {goal} ends above your average", "done" to done, "goal" to goal) + if (done >= goal && goal > 0) " ✅" else "", color = uv.ink, fontSize = 14.sp)
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).background(uv.line, CircleShape)) {
            Box(Modifier.fillMaxWidth(frac).height(8.dp).background(uv.gold, CircleShape))
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

/** All badges, earned ones in colour; in the history. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BadgesCard(state: AppState) {
    val uv = LocalUv.current
    val tz = remember { tzOffsetMs() }
    val badges = remember(state.sessions, state.ends) { Game.badges(state.sessions, state.ends, tz) }
    UvCard {
        Text(tr("Badges") + " · ${badges.count { it.earned }}/${badges.size}", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in badges) {
                var open by remember(b.id) { mutableStateOf(false) }
                Column(Modifier.width(96.dp).background(if (b.earned) uv.gold.copy(alpha = 0.18f) else uv.line.copy(alpha = 0.5f), SmallShape).clickable { open = !open }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (b.earned) b.emoji else "🔒", fontSize = 26.sp)
                    Text(b.title, color = if (b.earned) uv.ink else uv.muted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (open) Text(b.desc, color = uv.muted, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
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
