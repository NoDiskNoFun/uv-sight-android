package de.uvsight.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uvsight.core.AppState
import de.uvsight.core.SightController

@Composable
fun ConsoleScreen(state: AppState, ctl: SightController) {
    val uv = LocalUv.current
    val listState = rememberLazyListState()
    var cmd by remember { mutableStateOf("") }
    LaunchedEffect(state.console.size) { if (state.console.isNotEmpty()) listState.animateScrollToItem(state.console.size - 1) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().background(uv.surface, CardShape).padding(12.dp)) {
            items(state.console) { l ->
                Text(l.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
                    color = when (l.kind) { "in" -> uv.blue; "err" -> uv.red; "raw" -> uv.muted; else -> uv.ink })
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = cmd, onValueChange = { cmd = it }, modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Command, e.g. help") })
            Spacer(Modifier.width(8.dp))
            PrimaryButton("Send") { val c = cmd.trim(); if (c.isNotEmpty()) { ctl.sendRaw(c); cmd = "" } }
        }
    }
}
