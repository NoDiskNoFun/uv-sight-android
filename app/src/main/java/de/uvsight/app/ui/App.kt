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
import de.uvsight.core.tr

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
        // Notifications are optional; Bluetooth is not
        val bleOk = SightBle.requiredPermissions().all { granted[it] == true }
        if (bleOk) ctl.connect() else vm.toast(tr("Bluetooth permission is needed to find the sight."), true)
    }
    var photoFile by remember { mutableStateOf<java.io.File?>(null) }
    val connect = {
        val wanted = SightBle.requiredPermissions().toMutableList()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            wanted.add(android.Manifest.permission.POST_NOTIFICATIONS)
        if (SightBle.hasPermissions(context) && wanted.size == SightBle.requiredPermissions().size) ctl.connect() else permLauncher.launch(wanted.toTypedArray())
    }

    Scaffold(
        containerColor = uv.paper,
        topBar = { Header(state) },
        bottomBar = {
            NavigationBar(containerColor = uv.surface) {
                val tabs = buildList {
                    if (!state.photoOnly) add(Triple(View.STATUS, tr("Status"), Icons.Outlined.Speed))
                    add(Triple(View.TRAINING, if (state.photoOnly) tr("Scoring") else tr("Training"), Icons.Outlined.GpsFixed))
                    if (!state.photoOnly) add(Triple(View.HISTORY, tr("History"), Icons.Filled.History))
                    add(Triple(View.SETTINGS, tr("Settings"), Icons.Filled.Settings))
                    if (state.consoleEnabled && !state.photoOnly) add(Triple(View.CONSOLE, tr("Console"), Icons.Filled.Terminal))
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
            val view = if (state.photoOnly && state.view != View.SETTINGS) View.TRAINING else state.view
            when (view) {
                View.STATUS -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) { StatusScreen(state, ctl, connect) }
                View.TRAINING -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) { TrainingScreen(state, ctl, vm, onPhoto = { photoFile = it }) }
                View.HISTORY -> HistoryScreen(state, ctl, vm)
                View.SETTINGS -> SettingsScreen(state, ctl, vm)
                View.CONSOLE -> ConsoleScreen(state, ctl)
            }
        }
    }

    photoFile?.let { f -> PhotoScoringScreen(vm, f, onClose = { photoFile = null }) }

    // ---- dialogs driven by the controller ----
    state.confirm?.let { c ->
        val rest = c.counted - c.entered
        AlertDialog(onDismissRequest = { ctl.answerConfirm("no") },
            title = { Text(tr("End {end}: counts differ", "end" to c.end)) },
            text = { Text(tr("The sight counted {counted} {counted2}, you entered {entered} {entered2}. ", "counted" to c.counted, "counted2" to (if (c.counted == 1) tr("shot") else tr("shots")), "entered" to c.entered, "entered2" to (if (c.entered == 1) tr("score") else tr("scores"))) +
                if (c.canSplit) tr("Forgot to save the last end? Split saves your {entered} scores as this end and keeps the other {rest} {rest2} for the next one.", "entered" to c.entered, "rest" to rest, "rest2" to (if (rest == 1) tr("shot") else tr("shots")))
                else tr("Save with your {entered} scores?", "entered" to c.entered)) },
            confirmButton = {
                Row {
                    if (c.canSplit) TextButton(onClick = { ctl.answerConfirm("split") }) { Text(tr("Split")) }
                    TextButton(onClick = { ctl.answerConfirm("yes") }) { Text(tr("Save anyway")) }
                }
            },
            dismissButton = { TextButton(onClick = { ctl.answerConfirm("no") }) { Text(tr("Edit scores")) } })
    }
    state.afterClearCount?.let { n ->
        AlertDialog(onDismissRequest = { ctl.afterClearAnswer(false) },
            title = { Text(tr("{n} {n2} deleted on the sight", "n" to n, "n2" to (if (n == 1) tr("session") else tr("sessions")))) },
            text = { Text(tr("Copy the sessions from this phone back to the sight? Choose No if you give the sight away. Automatic sync is then switched off.")) },
            confirmButton = { TextButton(onClick = { ctl.afterClearAnswer(true) }) { Text(tr("Copy back")) } },
            dismissButton = { TextButton(onClick = { ctl.afterClearAnswer(false) }) { Text("No") } })
    }
    state.copyChoice?.let { list ->
        val checked = remember(list) { mutableStateOf(list.map { it.key }.toSet()) }
        WideDialog(onDismiss = { ctl.chooseCopies(emptySet()) },
            title = { Text(tr("Copy to the sight")) },
            buttons = {
                TextButton(onClick = { ctl.chooseCopies(emptySet()) }) { Text(tr("Cancel")) }
                TextButton(onClick = { ctl.chooseCopies(checked.value) }, enabled = checked.value.isNotEmpty()) { Text(if (checked.value.isEmpty()) tr("Copy") else tr("Copy {checked}", "checked" to checked.value.size)) }
            }) {
                Column {
                    Text(tr("{list} {list2} on this phone but not on the sight. Untick the ones you don't want to copy.", "list" to list.size, "list2" to (if (list.size == 1) tr("session is") else tr("sessions are"))), color = uv.muted, fontSize = 14.sp)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text(tr("{checked} of {list} selected", "checked" to checked.value.size, "list" to list.size), color = uv.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { checked.value = list.map { it.key }.toSet() }) { Text(tr("All")) }
                        TextButton(onClick = { checked.value = emptySet() }) { Text(tr("None")) }
                    }
                    Column {
                        for (r in list) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked.value.contains(r.key), onCheckedChange = { on -> checked.value = if (on) checked.value + r.key else checked.value - r.key })
                                Column(Modifier.weight(1f)) {
                                    Text(fmtDate(r), fontWeight = FontWeight.SemiBold)
                                    Text(tr("{ends} ends, {scored} arrows", "ends" to r.ends, "scored" to r.scored) + if (r.hidden) tr(", removed from this phone") else "", color = uv.muted, fontSize = 13.sp)
                                }
                                Text(de.uvsight.core.fmt(r.avg, 2), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
    }
}

@Composable
private fun Header(state: AppState) {
    val uv = LocalUv.current
    val text = when {
        state.photoOnly -> tr("Photo scoring")
        state.conn == ConnState.OFF && state.sightAsleep -> tr("Sight asleep")
        state.conn == ConnState.OFF && state.autoReconnect -> tr("Reconnecting…")
        state.conn == ConnState.OFF -> tr("Not connected")
        state.conn == ConnState.CONNECTING -> tr("Connecting")
        else -> tr("Connected") + (state.sight?.let { " · ${it.label}" } ?: "")
    }
    val dot = when {
        state.conn == ConnState.CONNECTED -> uv.ok
        state.conn == ConnState.CONNECTING || (state.conn == ConnState.OFF && state.autoReconnect) -> uv.gold
        else -> uv.line
    }
    Row(Modifier.fillMaxWidth().background(uv.paper).statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("UV-Sight"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = uv.ink, modifier = Modifier.weight(1f))
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = uv.muted, fontSize = 14.sp)
    }
}
