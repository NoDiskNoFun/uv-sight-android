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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import de.uvsight.core.tr
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
        FitText(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val uv = LocalUv.current
    val c = if (danger) uv.red else uv.ink
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = SmallShape,
        border = androidx.compose.foundation.BorderStroke(2.dp, if (enabled) (if (danger) uv.red else uv.line) else uv.line.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c, disabledContentColor = c.copy(alpha = 0.5f))) {
        FitText(text, fontWeight = FontWeight.SemiBold)
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
                FitText(tr(m).replaceFirstChar { it.uppercase() })
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
        FitText(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = uv.ink)
        FitText(label, fontSize = 12.sp, color = uv.muted)
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

/** Badge colours for the sights, by their colour index. */
val sightColors = listOf(Color(0xFF2F6DB5), Color(0xFFD6402F), Color(0xFF58B27E), Color(0xFFF2C230), Color(0xFF8E5BD6), Color(0xFF3FA9B8), Color(0xFFE07B39))
fun sightColor(s: de.uvsight.core.SightInfo?): Color = s?.let { sightColors[Math.floorMod(it.color, sightColors.size)] } ?: Color(0xFF9AA39D)

/** Small marker naming the sight a session came from (grey "?" when more than one sight is known and this one is unknown). */
@Composable
fun SightBadge(sight: de.uvsight.core.SightInfo?, unknown: Boolean = false) {
    if (sight == null && !unknown) return
    val col = sightColor(sight)
    Row(Modifier.background(col.copy(alpha = 0.16f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(7.dp).height(7.dp).background(col, androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(sight?.label ?: "?", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LocalUv.current.ink)
    }
}

/**
 * Text that shrinks to fit the width it gets (down to about 70 % of its size) instead of being
 * cut off or wrapped: for buttons, segments, chips and table heads, so translations of any
 * length work without touching the layout.
 */
@Composable
fun FitText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = 14.sp, fontWeight: FontWeight? = null, maxLines: Int = 1) {
    val col = if (color == Color.Unspecified) LocalContentColor.current else color
    BasicText(text, modifier = modifier, style = TextStyle(color = col, fontSize = fontSize, fontWeight = fontWeight),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = fontSize * 0.7f, maxFontSize = fontSize, stepSize = 0.5.sp))
}

/**
 * A dialog with the same margin on all four sides (the platform dialog leaves wide margins left
 * and right on phones). The body scrolls when it is taller than the screen allows.
 */
@Composable
fun WideDialog(onDismiss: () -> Unit, title: @Composable () -> Unit, buttons: @Composable RowScope.() -> Unit, body: @Composable ColumnScope.() -> Unit) {
    val uv = LocalUv.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(shape = CardShape, color = uv.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp)) {
                    ProvideTextStyle(TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = uv.ink)) { title() }
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = body)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = buttons)
                }
            }
        }
    }
}
