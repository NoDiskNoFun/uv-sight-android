package de.uvsight.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.uvsight.app.SightBle
import de.uvsight.app.SightViewModel
import de.uvsight.core.AppState
import de.uvsight.core.ConnState
import de.uvsight.core.View

@Composable
fun UvSightApp(vm: SightViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctl = vm.controller
    val uv = LocalUv.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var snackError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.toasts.collect { t -> snackError = t.error; snackbar.showSnackbar(t.text, duration = SnackbarDuration.Short) }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) ctl.connect() else vm.toast("Bluetooth permission is needed to find the sight.", true)
    }
    val connect = {
        if (SightBle.hasPermissions(context)) ctl.connect() else permLauncher.launch(SightBle.requiredPermissions())
    }

    Scaffold(
        containerColor = uv.paper,
        topBar = { Header(state) },
        bottomBar = {
            NavigationBar(containerColor = uv.surface) {
                val tabs = buildList {
                    add(Triple(View.STATUS, "Status", Icons.Outlined.Speed))
                    add(Triple(View.TRAINING, "Training", Icons.Outlined.GpsFixed))
                    add(Triple(View.HISTORY, "History", Icons.Filled.History))
                    add(Triple(View.SETTINGS, "Settings", Icons.Filled.Settings))
                    if (state.consoleEnabled) add(Triple(View.CONSOLE, "Console", Icons.Filled.Terminal))
                }
                for ((v, label, icon) in tabs) {
                    NavigationBarItem(selected = state.view == v, onClick = { ctl.setView(v) },
                        icon = { Icon(icon, contentDescription = label) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = uv.ink, selectedTextColor = uv.ink,
                            unselectedIconColor = uv.muted, unselectedTextColor = uv.muted, indicatorColor = uv.gold.copy(alpha = 0.35f)))
                }
            }
        },
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(snackbarData = data, containerColor = if (snackError) uv.red else uv.ink, contentColor = if (snackError) Color.White else uv.paper)
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state.view) {
                View.STATUS -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) { StatusScreen(state, ctl, connect) }
                View.TRAINING -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) { TrainingScreen(state, ctl, vm) }
                View.HISTORY -> HistoryScreen(state, ctl, vm)
                View.SETTINGS -> SettingsScreen(state, ctl, vm)
                View.CONSOLE -> ConsoleScreen(state, ctl)
            }
        }
    }

    // ---- dialogs driven by the controller ----
    state.confirm?.let { c ->
        val rest = c.counted - c.entered
        AlertDialog(onDismissRequest = { ctl.answerConfirm("no") },
            title = { Text("End ${c.end}: counts differ") },
            text = { Text("The sight counted ${c.counted} ${if (c.counted == 1) "shot" else "shots"}, you entered ${c.entered} ${if (c.entered == 1) "score" else "scores"}. " +
                if (c.canSplit) "Forgot to save the last end? Split saves your ${c.entered} scores as this end and keeps the other $rest ${if (rest == 1) "shot" else "shots"} for the next one."
                else "Save with your ${c.entered} scores?") },
            confirmButton = {
                Row {
                    if (c.canSplit) TextButton(onClick = { ctl.answerConfirm("split") }) { Text("Split") }
                    TextButton(onClick = { ctl.answerConfirm("yes") }) { Text("Save anyway") }
                }
            },
            dismissButton = { TextButton(onClick = { ctl.answerConfirm("no") }) { Text("Edit scores") } })
    }
    state.afterClearCount?.let { n ->
        AlertDialog(onDismissRequest = { ctl.afterClearAnswer(false) },
            title = { Text("$n ${if (n == 1) "session" else "sessions"} deleted on the sight") },
            text = { Text("Copy the sessions from this phone back to the sight? Choose No if you give the sight away. Automatic sync is then switched off.") },
            confirmButton = { TextButton(onClick = { ctl.afterClearAnswer(true) }) { Text("Copy back") } },
            dismissButton = { TextButton(onClick = { ctl.afterClearAnswer(false) }) { Text("No") } })
    }
    state.copyChoice?.let { list ->
        val checked = remember(list) { mutableStateOf(list.map { it.key }.toSet()) }
        AlertDialog(onDismissRequest = { ctl.chooseCopies(emptySet()) },
            title = { Text("Copy to the sight") },
            text = {
                Column {
                    Text("${list.size} ${if (list.size == 1) "session is" else "sessions are"} on this phone but not on the sight. Untick the ones you don't want to copy.", color = uv.muted, fontSize = 14.sp)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("${checked.value.size} of ${list.size} selected", color = uv.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { checked.value = list.map { it.key }.toSet() }) { Text("All") }
                        TextButton(onClick = { checked.value = emptySet() }) { Text("None") }
                    }
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        for (r in list) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked.value.contains(r.key), onCheckedChange = { on -> checked.value = if (on) checked.value + r.key else checked.value - r.key })
                                Column(Modifier.weight(1f)) {
                                    Text(fmtDate(r), fontWeight = FontWeight.SemiBold)
                                    Text("${r.ends} ends, ${r.scored} arrows" + if (r.hidden) ", removed from this phone" else "", color = uv.muted, fontSize = 13.sp)
                                }
                                Text(de.uvsight.core.fmt(r.avg, 2), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { ctl.chooseCopies(checked.value) }, enabled = checked.value.isNotEmpty()) { Text(if (checked.value.isEmpty()) "Copy" else "Copy ${checked.value.size}") } },
            dismissButton = { TextButton(onClick = { ctl.chooseCopies(emptySet()) }) { Text("Cancel") } })
    }
}

@Composable
private fun Header(state: AppState) {
    val uv = LocalUv.current
    val text = when {
        state.conn == ConnState.OFF && state.sightAsleep -> "Sight asleep"
        state.conn == ConnState.OFF && state.autoReconnect -> "Reconnecting…"
        state.conn == ConnState.OFF -> "Not connected"
        state.conn == ConnState.CONNECTING -> "Connecting"
        else -> "Connected"
    }
    val dot = when {
        state.conn == ConnState.CONNECTED -> uv.ok
        state.conn == ConnState.CONNECTING || (state.conn == ConnState.OFF && state.autoReconnect) -> uv.gold
        else -> uv.line
    }
    Row(Modifier.fillMaxWidth().background(uv.paper).statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("UV-Sight", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = uv.ink, modifier = Modifier.weight(1f))
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = uv.muted, fontSize = 14.sp)
    }
}
