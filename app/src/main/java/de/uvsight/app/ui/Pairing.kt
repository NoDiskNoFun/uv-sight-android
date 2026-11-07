package de.uvsight.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uvsight.app.SightViewModel
import de.uvsight.core.AppState
import de.uvsight.core.SightController
import de.uvsight.core.tr

/**
 * The pairing dialog (no Bluetooth bonding, just the choice): every Anchored sight in range, read from
 * the beacons. Tapping one connects to it and makes it the preferred sight, so the app keeps to it even
 * with other sights in the room. Shown at the first start, from Connect while no sight is known, and
 * from Settings → Sights → Connect a new sight.
 */
@Composable
fun PairDialog(state: AppState, ctl: SightController, vm: SightViewModel, allowed: Boolean, requestPermissions: () -> Unit, onClose: () -> Unit) {
    val uv = LocalUv.current
    DisposableEffect(allowed) { if (allowed) vm.startBoardScan(); onDispose { if (allowed) vm.stopBoardScan() } }
    val known = state.sights.mapNotNull { it.address }.toSet()
    WideDialog(onDismiss = onClose, title = { Text(tr("Connect a sight")) }, buttons = { TextButton(onClick = onClose) { Text(tr("Cancel")) } }) {
        Text(tr("Every Anchored sight in range. Move the bow so yours sends its beacon, then tap it: the app connects to it and keeps to it from then on."), color = uv.muted, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        if (!allowed) SecondaryButton(tr("Allow Bluetooth"), Modifier.fillMaxWidth()) { requestPermissions() }
        else if (state.board.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = uv.ink, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(tr("Searching…"), color = uv.muted, fontSize = 14.sp)
        }
        for (e in state.board) {
            Row(Modifier.fillMaxWidth().clickable { ctl.pairSight(e.address); onClose() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.label + (if (e.address in known) tr(" (known)") else ""), fontWeight = FontWeight.SemiBold, color = uv.ink)
                    val detail = listOfNotNull(
                        if (e.chip.isNotEmpty()) tr("Id …{chip}", "chip" to e.chip.takeLast(4)) else null,
                        if (e.session) (if (e.ownerName.isNotEmpty()) tr("in use by {name}", "name" to e.ownerName) else tr("session running")) else null,
                        if (e.pw) tr("password") else null)
                    if (detail.isNotEmpty()) Text(detail.joinToString(" · "), color = uv.muted, fontSize = 13.sp)
                }
                if (e.battery >= 0) Text("${e.battery} %", color = if (e.battery <= 15) uv.red else uv.muted, fontSize = 13.sp)
            }
            HorizontalDivider(color = uv.line)
        }
    }
}
