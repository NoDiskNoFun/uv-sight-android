package de.uvsight.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uvsight.app.FirmwareFlasher
import de.uvsight.app.FirmwareRelease
import de.uvsight.app.UpdateManager
import de.uvsight.core.ConnState
import de.uvsight.core.SightController
import de.uvsight.core.tr
import kotlinx.coroutines.launch

/**
 * The guided firmware update: one window, six numbered steps, the current one explained in full. Nothing to
 * pick: the app downloads the file, waits for the cable, puts the sight into update mode, asks Android for
 * the USB drive, copies the file and waits for the sight to come back.
 */
@Composable
fun FirmwareUpdateDialog(rel: FirmwareRelease, updates: UpdateManager, ctl: SightController, onClose: () -> Unit) {
    val uv = LocalUv.current
    val context = LocalContext.current
    val flasher = remember { FirmwareFlasher(context) }
    val st by flasher.state.collectAsState()
    val busy by updates.busy.collectAsState()
    val appState by ctl.state.collectAsState()

    LaunchedEffect(rel.firmware) {
        val connected = appState.conn == ConnState.CONNECTED
        flasher.run(rel.firmware,
            download = { updates.downloadFirmware(rel) },
            startDfu = if (connected) ({ ctl.dfu() }) else null,
            reconnected = { ctl.state.value.let { s -> if (s.conn == ConnState.CONNECTED) s.hello?.fw else null } })
    }

    val finished = st.done || st.error != null
    AlertDialog(onDismissRequest = { if (finished) onClose() },
        title = { Text(tr("Firmware update to {version}", "version" to rel.firmware)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (n in 1..FirmwareFlasher.STEPS) {
                    val current = n == st.step
                    val passed = n < st.step || (current && st.done)
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        if (current && !finished) CircularProgressIndicator(Modifier.size(18.dp).padding(top = 2.dp), strokeWidth = 2.dp, color = uv.gold)
                        else Text(if (passed) "✓" else "$n.", color = if (passed) uv.ok else if (current) uv.ink else uv.muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(FirmwareFlasher.stepTitle(n), color = if (current) uv.ink else uv.muted, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, fontSize = 15.sp)
                            if (current) {
                                val text = st.error ?: st.detail
                                if (text.isNotEmpty()) Text(text, color = if (st.error != null) uv.red else uv.ink, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                                st.hint?.let { Text(it, color = uv.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)) }
                                val p = if (n == 1) busy?.let { b -> if (b.total > 0) b.done.toFloat() / b.total else -1f } ?: -1f else st.progress
                                if (!finished && (n == 1 || n == 5)) {
                                    Spacer(Modifier.height(6.dp))
                                    if (p >= 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = uv.gold)
                                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = uv.gold)
                                }
                            }
                        }
                    }
                }
                if (st.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(tr("Without the app: download anchored.uf2 from the firmware release on GitHub, plug the sight into a computer, press its reset button twice quickly and copy the file onto the drive that appears."), color = uv.muted, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            if (finished) TextButton(onClick = onClose) { Text(tr("Close")) }
            else if (st.step == 2) TextButton(onClick = { flasher.confirm() }) { Text(tr("Next")) }
        },
        dismissButton = { if (!finished && st.step <= 2) TextButton(onClick = onClose) { Text(tr("Cancel")) } })
}

/** The update rows of the App and firmware card: last check, daily check, and the two update buttons. */
@Composable
fun UpdateRows(updates: UpdateManager, ctl: SightController, connected: Boolean, toast: (String, Boolean) -> Unit) {
    val uv = LocalUv.current
    val status by updates.status.collectAsState()
    val busy by updates.busy.collectAsState()
    val appState by ctl.state.collectAsState()
    var checking by remember { androidx.compose.runtime.mutableStateOf(false) }
    var fwDialog by remember { androidx.compose.runtime.mutableStateOf<FirmwareRelease?>(null) }
    var auto by remember { androidx.compose.runtime.mutableStateOf(updates.auto) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val appNew = status.app?.takeIf { it.build > de.uvsight.app.BuildConfig.VERSION_CODE }
    val fw = status.firmware
    val sightFw = appState.hello?.fw
    val fwNew = fw != null && sightFw != null && UpdateManager.newerVersion(fw.firmware, sightFw)

    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Updates"), color = uv.ink)
            val line = when {
                checking -> tr("Looking for new versions…")
                status.error != null -> tr("The last check failed: {error}", "error" to status.error)
                status.checkedAt == 0L -> tr("Not checked yet. New builds of the app and of the sight's firmware are published on GitHub by the project.")
                appNew == null && (fw == null || sightFw == null || !fwNew) -> tr("Checked {time}: everything is up to date.", "time" to ago(status.checkedAt))
                else -> tr("Checked {time}.", "time" to ago(status.checkedAt))
            }
            Text(line, color = uv.muted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        SecondaryButton(tr("Check now"), enabled = !checking) {
            checking = true
            scope.launch { val r = updates.check(); checking = false; if (r.error != null) toast(r.error, true) }
        }
    }
    SwitchRow(tr("Check daily"), tr("Once a day, while the app is open, it looks on GitHub for a newer app build and newer firmware and tells you with a notification. Nothing is installed by itself."), auto) { auto = it; updates.setAuto(it) }
    if (appNew != null) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Nocked {version} is available", "version" to appNew.versionName.ifEmpty { "build ${appNew.build}" }), color = uv.ink)
                Text(if (busy?.what == tr("app")) tr("Downloading… {pct} %", "pct" to pct(busy)) else tr("You have {version}. The download opens Android's installer; the app keeps its data. The first time, Android asks you to allow installs from Nocked: switch it on and tap Update again.", "version" to de.uvsight.app.BuildConfig.VERSION_NAME), color = uv.muted, fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            PrimaryButton(tr("Update"), gold = true, enabled = busy == null) {
                scope.launch {
                    val ok = runCatching { updates.installApp(appNew) }
                    ok.exceptionOrNull()?.let { toast(it.message ?: tr("The download failed."), true) }
                    if (ok.getOrNull() == false) toast(tr("Allow Nocked to install apps, then tap Update again."), false)
                }
            }
        }
    }
    if (fw != null) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (fwNew) tr("Firmware {version} is available for the sight", "version" to fw.firmware) else tr("Firmware {version}", "version" to fw.firmware), color = uv.ink)
                Text(when {
                    sightFw == null -> tr("Connect the sight to see whether it has this version. The update itself goes over a USB-C cable between the sight and this phone; the app guides you step by step.")
                    fwNew -> tr("The sight has {version}. The update goes over a USB-C cable between the sight and this phone; the app guides you step by step.", "version" to sightFw)
                    else -> tr("The sight already has this version. You can install it again if something is wrong with it.")
                }, color = uv.muted, fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            if (fwNew) PrimaryButton(tr("Update"), gold = true, enabled = busy == null) { fwDialog = fw }
            else SecondaryButton(tr("Install"), enabled = busy == null) { fwDialog = fw }
        }
    } else if (status.checkedAt != 0L && status.error == null && connected) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text(tr("No firmware release was found on GitHub."), color = uv.muted, fontSize = 13.sp) }
    }
    fwDialog?.let { FirmwareUpdateDialog(it, updates, ctl, onClose = { fwDialog = null }) }
}

private fun pct(b: de.uvsight.app.Downloading?): Int = if (b == null || b.total <= 0) 0 else (b.done * 100 / b.total).toInt()

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> tr("just now")
        m < 60 -> tr("{n} min ago", "n" to m)
        m < 48 * 60 -> tr("{n} h ago", "n" to m / 60)
        else -> tr("{n} days ago", "n" to m / 1440)
    }
}
