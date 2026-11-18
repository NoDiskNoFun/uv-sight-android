package de.uvsight.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uvsight.core.AppState
import de.uvsight.core.Bow
import de.uvsight.core.SightController
import de.uvsight.core.fmt
import de.uvsight.core.tr

private val bowLabels get() = (0..3).map { Bow.label(it) }
private fun bowIndex(label: String) = (0..3).firstOrNull { Bow.label(it) == label } ?: Bow.COMPOUND

/** A sight met for the first time: its name and the bow it is on; the bow's suggested values are then applied and saved. */
@Composable
fun NewSightDialog(state: AppState, ctl: SightController) {
    val uv = LocalUv.current
    var name by remember { mutableStateOf(state.sight?.name ?: "") }
    var bow by remember { mutableStateOf(state.hello?.bow ?: state.sight?.bow ?: Bow.COMPOUND) }
    WideDialog(onDismiss = { ctl.skipIntroduction() }, title = { Text(tr("A new sight")) }, buttons = {
        TextButton(onClick = { ctl.skipIntroduction() }) { Text(tr("Later")) }
        TextButton(onClick = { ctl.introduceSight(name, bow) }) { Text(tr("Done")) }
    }) {
        Text(tr("Give the sight a name and say which bow it is on. Its cant tolerance, blinking range and signals are then set to the values suggested for that bow and saved; every one of them stays changeable under Settings."), color = uv.muted, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = name, onValueChange = { name = it.take(16) }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr("Name of this sight")) }, placeholder = { Text(tr("e.g. Hoyt")) })
        Spacer(Modifier.height(12.dp))
        Text(tr("Bow"), color = uv.ink)
        Spacer(Modifier.height(6.dp))
        Seg(Bow.label(bow), bowLabels) { bow = bowIndex(it) }
        Text(Bow.describe(bow), color = uv.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The values the app suggests for a bow, each with a tick: only the ticked ones are sent to the sight. */
@Composable
fun BowSuggestDialog(bow: Int, ctl: SightController, onClose: () -> Unit) {
    val uv = LocalUv.current
    val rows = remember(bow) { Bow.suggestions(bow).map { it to ctl.currentOf(it.key) }.filter { (sg, cur) -> cur == null || cur != sg.value } }
    var checked by remember(bow) { mutableStateOf(rows.map { it.first.key }.toSet()) }
    fun show(key: String, v: Double?): String = when {
        v == null -> "?"
        key == "cant_signal" || key == "range_signal" || key == "led_mode" -> if (v == 1.0) tr("on") else tr("off")
        else -> (if (v == Math.floor(v)) v.toLong().toString() else fmt(v, 1)) + "°"
    }
    WideDialog(onDismiss = onClose, title = { Text(tr("Suggested values: {bow}", "bow" to Bow.label(bow))) }, buttons = {
        TextButton(onClick = onClose) { Text(tr("Cancel")) }
        TextButton(onClick = { ctl.applyBowSuggestions(bow, checked); onClose() }, enabled = checked.isNotEmpty()) { Text(tr("Apply")) }
    }) {
        if (rows.isEmpty()) Text(tr("Everything is already set as suggested for this bow."), color = uv.muted, fontSize = 14.sp)
        else {
            Text(tr("Tick what to change. The values apply right away; Save in the bar below keeps them after a restart, Discard takes them back."), color = uv.muted, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            for ((sg, cur) in rows) {
                val on = sg.key in checked
                Row(Modifier.fillMaxWidth().clickable { checked = if (on) checked - sg.key else checked + sg.key }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = on, onCheckedChange = { checked = if (it) checked + sg.key else checked - sg.key })
                    Column {
                        Text(sg.label, color = uv.ink)
                        Text(show(sg.key, cur) + " → " + show(sg.key, sg.value), color = uv.muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
