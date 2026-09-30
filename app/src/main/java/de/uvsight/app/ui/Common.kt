package de.uvsight.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val CardShape = RoundedCornerShape(18.dp)
val SmallShape = RoundedCornerShape(10.dp)

@Composable
fun UvCard(modifier: Modifier = Modifier, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    val uv = LocalUv.current
    Surface(modifier = modifier.fillMaxWidth(), shape = CardShape, color = uv.surface) {
        Column(Modifier.padding(padding.dp), content = content)
    }
}

@Composable
fun Banner(text: String, info: Boolean = false) {
    val uv = LocalUv.current
    val accent = if (info) uv.blue else uv.red
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(accent.copy(alpha = 0.14f), SmallShape)) {
        Box(Modifier.width(5.dp).height(48.dp).background(accent, RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)))
        Text(text, Modifier.padding(12.dp), color = uv.ink, fontSize = 15.sp)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, gold: Boolean = false, onClick: () -> Unit) {
    val uv = LocalUv.current
    val bg = if (danger) uv.red else if (gold) uv.gold else uv.ink
    val fg = if (danger) Color.White else if (gold) Color(0xFF1B1B1B) else uv.paper
    Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = SmallShape,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg, disabledContainerColor = bg.copy(alpha = 0.5f), disabledContentColor = fg.copy(alpha = 0.7f))) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val uv = LocalUv.current
    val c = if (danger) uv.red else uv.ink
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = SmallShape,
        border = androidx.compose.foundation.BorderStroke(2.dp, if (enabled) (if (danger) uv.red else uv.line) else uv.line.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c, disabledContentColor = c.copy(alpha = 0.5f))) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ReadingRow(label: String, value: String, small: String = "", first: Boolean = false) {
    val uv = LocalUv.current
    if (!first) HorizontalDivider(color = uv.line)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(label, color = uv.muted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontWeight = FontWeight.SemiBold, color = uv.ink)
            if (small.isNotEmpty()) { Spacer(Modifier.width(6.dp)); Text(small, color = uv.muted, fontSize = 13.sp) }
        }
    }
}

@Composable
fun SwitchRow(label: String, desc: String, checked: Boolean, enabled: Boolean = true, first: Boolean = false, onChange: (Boolean) -> Unit) {
    val uv = LocalUv.current
    if (!first) HorizontalDivider(color = uv.line)
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = uv.ink)
            if (desc.isNotEmpty()) Text(desc, color = uv.muted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = uv.ok, checkedThumbColor = Color.White))
    }
}

@Composable
fun SegRow(label: String, desc: String, current: String, modes: List<String>, first: Boolean = false, enabled: Boolean = true, onPick: (String) -> Unit) {
    val uv = LocalUv.current
    if (!first) HorizontalDivider(color = uv.line)
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(label, color = uv.ink)
        if (desc.isNotEmpty()) Text(desc, color = uv.muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Seg(current, modes, enabled, onPick)
    }
}

@Composable
fun Seg(current: String, modes: List<String>, enabled: Boolean = true, onPick: (String) -> Unit) {
    val uv = LocalUv.current
    SingleChoiceSegmentedButtonRow {
        modes.forEachIndexed { i, m ->
            SegmentedButton(selected = m == current, onClick = { onPick(m) }, enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                colors = SegmentedButtonDefaults.colors(activeContainerColor = uv.ink, activeContentColor = uv.paper, inactiveContentColor = uv.muted, activeBorderColor = uv.line, inactiveBorderColor = uv.line)) {
                Text(m.replaceFirstChar { it.uppercase() })
            }
        }
    }
}

@Composable
fun GroupTitle(text: String) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }

@Composable
fun Note(text: String) { Text(text, color = LocalUv.current.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp)) }

@Composable
fun EmptyBox(text: String, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = LocalUv.current.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
fun RowScope.Stat(value: String, label: String) {
    val uv = LocalUv.current
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = uv.ink)
        Text(label, fontSize = 12.sp, color = uv.muted)
    }
}

@Composable
fun Chip(v: String) {
    val uv = LocalUv.current
    val bg = Ring.colorFor(v)
    val border = when (v) { "M" -> uv.line; "4", "3" -> Color(0xFF555555); "2", "1" -> Color(0xFFBFC5BD); else -> Color.Transparent }
    Box(Modifier.padding(3.dp).width(38.dp).height(38.dp).background(bg, androidx.compose.foundation.shape.CircleShape)
        .border(2.dp, border, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
        Text(v, fontWeight = FontWeight.Bold, color = Ring.textFor(v, uv.ink), fontSize = 14.sp)
    }
}
