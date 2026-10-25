package de.uvsight.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import de.uvsight.core.tr

/** Emoji for avatars, badges and goals: glyphs of the phone's own font, grouped; no keyboard involved. */
private val EMOJI_GROUPS: List<Pair<String, List<String>>> = listOf(
    "Archery" to listOf("🏹", "🎯", "🏅", "🥇", "🥈", "🥉", "🏆", "⭐", "🌟", "✨", "💫", "🔥", "💪", "👏", "🙌", "👍", "✅", "🎉", "🎊", "🧹", "🖐️", "👁️", "🧭", "📐", "📏", "⏱️", "📅", "📈", "🔒", "🔑"),
    "Animals" to listOf("🦊", "🐺", "🦁", "🐯", "🐻", "🐼", "🐨", "🐸", "🐵", "🦉", "🦅", "🦆", "🐧", "🐢", "🐍", "🦋", "🐝", "🐞", "🐬", "🐳", "🦈", "🐙", "🦄", "🐴", "🐱", "🐶", "🐰", "🐹", "🦔", "🦎"),
    "Faces" to listOf("😀", "😁", "😎", "🤩", "😇", "🥳", "😏", "🤓", "🧐", "😤", "🥶", "🤠", "🤖", "👻", "👽", "💀", "🎃", "🤡", "👑", "🧢"),
    "Nature" to listOf("🌲", "🌳", "🍀", "🌻", "🌈", "☀️", "🌙", "⚡", "❄️", "🌊", "🍎", "🍓", "🍋", "🥕", "🌶️", "🍄", "🪨", "🏔️", "🌋", "🌍"),
    "Things" to listOf("🚀", "✈️", "🚲", "⚓", "🛡️", "⚔️", "🗡️", "🧲", "🔭", "🔬", "💡", "🎸", "🎲", "🧩", "🎈", "🎁", "💎", "🔔", "📣", "🧸"),
)

/**
 * A field that shows the emoji and opens the picker on a tap; the small text box in the picker takes
 * any other emoji pasted in, but no keyboard pops up by itself.
 */
@Composable
fun EmojiField(value: String, onChange: (String) -> Unit, label: String = "") {
    val uv = LocalUv.current
    var open by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).background(uv.surface, SmallShape).border(1.dp, uv.line, SmallShape).clickable { open = true }, contentAlignment = Alignment.Center) {
            Text(value.ifEmpty { "＋" }, fontSize = 26.sp, color = if (value.isEmpty()) uv.muted else uv.ink)
        }
        if (label.isNotEmpty()) Text(label, color = uv.muted, fontSize = 11.sp)
    }
    if (open) EmojiPicker(value, onPick = { onChange(it); open = false }, onClose = { open = false })
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun EmojiPicker(current: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val uv = LocalUv.current
    var other by remember { mutableStateOf("") }
    WideDialog(onDismiss = onClose, title = { Text(tr("Pick an emoji")) }, buttons = {
        TextButton(onClick = { onPick("") }) { Text(tr("None")) }
        TextButton(onClick = onClose) { Text(tr("Cancel")) }
    }) {
        for ((group, list) in EMOJI_GROUPS) {
            Text(when (group) { "Archery" -> tr("Archery"); "Animals" -> tr("Animals"); "Faces" -> tr("Faces"); "Nature" -> tr("Nature"); else -> tr("Things") }, color = uv.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (e in list) Box(Modifier.size(40.dp).background(if (e == current) uv.gold.copy(alpha = 0.4f) else uv.surface, SmallShape).clickable { onPick(e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 22.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = other, onValueChange = { other = it.take(4) }, singleLine = true, modifier = Modifier.width(90.dp), label = { Text(tr("Other")) })
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { if (other.isNotBlank()) onPick(other.trim()) }, enabled = other.isNotBlank()) { Text(tr("Use")) }
        }
    }
}
