package de.uvsight.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uvsight.app.SightViewModel
import de.uvsight.core.AppState
import de.uvsight.core.BoardEntry
import de.uvsight.core.ConnState
import de.uvsight.core.SightController
import de.uvsight.core.fmt
import de.uvsight.core.tr

/**
 * The trainer's board: every sight in range as a tile, read from the sights' advertisements
 * without connecting, so the archers' own links are never touched. Tapping a tile connects for a
 * short look at the session; "Set up all" and "Collect all" visit the sights one after the other.
 */
@Composable
fun TrainerScreen(state: AppState, ctl: SightController, vm: SightViewModel) {
    val uv = LocalUv.current
    DisposableEffect(Unit) { vm.startBoardScan(); onDispose { vm.stopBoardScan() } }
    var open by remember { mutableStateOf<BoardEntry?>(null) }

    UvCard {
        Text(tr("Trainer's board"), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = uv.ink)
        Spacer(Modifier.height(6.dp))
        Text(tr("Every sight in range, read from its radio beacon without connecting. Tap a sight to look at its session."), color = uv.muted, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(tr("Set up all"), Modifier.weight(1f), enabled = state.board.isNotEmpty() && state.visiting.isEmpty()) { ctl.setupAllSights(state.board) }
            SecondaryButton(tr("Collect all"), Modifier.weight(1f), enabled = state.board.isNotEmpty() && state.visiting.isEmpty()) { ctl.collectAllSights(state.board) }
        }
        Text(tr("Set up all gives every new sight the group's password and makes you its trainer; Collect all fetches the sessions of every sight."), color = uv.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        if (state.visiting.isNotEmpty()) Text(state.visiting, color = uv.ink, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
    }
    Spacer(Modifier.height(12.dp))
    if (state.board.isEmpty()) EmptyBox(tr("No sight in range. A sight sends its beacon while the bow moves."))
    else UvCard(padding = 0) {
        state.board.forEachIndexed { i, e ->
            if (i > 0) androidx.compose.material3.HorizontalDivider(color = uv.line)
            BoardTile(e) { open = e; ctl.connectBoardEntry(e.address) }
        }
    }
    Spacer(Modifier.height(24.dp))

    open?.let { e -> SightDialog(e, state, ctl, onClose = { open = null; ctl.leaveSight() }) }
}

@Composable
private fun BoardTile(e: BoardEntry, onClick: () -> Unit) {
    val uv = LocalUv.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(if (e.session) uv.gold else uv.line, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.label, fontWeight = FontWeight.SemiBold, color = uv.ink)
                if (e.pw) Text("  🔒", fontSize = 12.sp)
                if (e.linked) Text("  " + tr("linked"), color = uv.muted, fontSize = 12.sp)
            }
            Text(when {
                e.session && e.ownerName.isNotEmpty() -> e.ownerName
                e.session -> tr("session without an archer's app")
                else -> tr("no session")
            }, color = if (e.session) uv.ink else uv.muted, fontSize = 14.sp)
            if (e.session) Text(tr("End {end} · {shots} shots · {points} points · {x} X", "end" to e.end, "shots" to e.endShots, "points" to e.points, "x" to e.x), color = uv.muted, fontSize = 13.sp)
        }
        Text("${e.battery} %", color = if (e.battery <= 15) uv.red else uv.muted, fontSize = 13.sp)
    }
}

/** A short visit of one sight: its session, End session, Collect, and the password for a new sight. */
@Composable
private fun SightDialog(e: BoardEntry, state: AppState, ctl: SightController, onClose: () -> Unit) {
    val uv = LocalUv.current
    val connected = state.conn == ConnState.CONNECTED
    val ses = state.session
    WideDialog(onDismiss = onClose, title = { Text(e.label) }, buttons = {
        TextButton(onClick = onClose) { Text(tr("Close")) }
    }) {
        Text(when {
            !connected && state.conn == ConnState.CONNECTING -> tr("Connecting…")
            !connected -> tr("Not connected (the sight may have gone to sleep).")
            state.role == "trainer" -> tr("Connected as trainer.")
            state.role == "locked" -> tr("Locked: the password does not fit this sight.")
            state.rolePending -> tr("Connected; trainer access requested, not approved yet.")
            else -> tr("Connected as {role}.", "role" to state.role)
        }, color = uv.muted, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        if (connected && ses?.active == true) {
            Text(ses.ownerName?.takeIf { it.isNotEmpty() } ?: tr("Session without an archer's app"), fontWeight = FontWeight.SemiBold, color = uv.ink)
            Text(tr("End {end}, {endShots} shots in it · {ends} ends, {scored} arrows, average {avg}, {x} X, {min} min",
                "end" to ses.end, "endShots" to ses.endShots, "ends" to ses.ends, "scored" to ses.scored, "avg" to fmt(ses.avg, 2), "x" to ses.x, "min" to ses.min), color = uv.muted, fontSize = 14.sp)
        } else if (connected) Text(tr("No session running."), color = uv.muted, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(tr("End session"), Modifier.weight(1f), enabled = connected && ses?.active == true, danger = true) { ctl.endSessionOnSight() }
            SecondaryButton(tr("Collect"), Modifier.weight(1f), enabled = connected && state.role != "locked") { ctl.requestLogNow() }
        }
        if (connected && state.hello?.pw == false) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton(tr("Set the group's password"), Modifier.fillMaxWidth(), enabled = state.group.password.length >= 4) { ctl.groupSetPassword(state.group.password) }
            Text(tr("This sight has no password yet. Setting it makes you its trainer."), color = uv.muted, fontSize = 12.sp)
        }
        if (connected && state.hello?.pw == true && state.role == "user" && !state.rolePending) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton(tr("Request trainer access"), Modifier.fillMaxWidth()) { ctl.groupRequest() }
        }
    }
}
