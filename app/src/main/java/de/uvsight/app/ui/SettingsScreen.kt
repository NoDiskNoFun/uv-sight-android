package de.uvsight.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.produceState
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uvsight.app.SightViewModel
import de.uvsight.core.APP_VERSION
import de.uvsight.core.AppState
import de.uvsight.core.CfgItem
import de.uvsight.core.ConnState
import de.uvsight.core.LevelInfo
import de.uvsight.core.SightController
import de.uvsight.core.fmt
import kotlin.math.pow
import kotlin.math.roundToInt

private data class Group(val title: String, val keys: List<String>, val light: Boolean = false, val cal: Boolean = false, val led: Boolean = false)
/** Settings that only matter with the UV LED stage fitted. */
private val LED_KEYS = setOf("tilt_offset", "tilt_full", "blink_min", "blink_max")
private val GROUPS = listOf(
    Group("Light sensor", listOf("dark_on", "dark_off", "confirm"), light = true, led = true),
    Group("UV light", listOf("bright_mode", "bright", "bright_min", "fade", "vf"), led = true),
    Group("Cant and aiming range", listOf("level_tol", "tilt_offset", "tilt_full", "blink_min", "blink_max", "tilt_smooth"), cal = true),
    Group("Shot detection and sessions", listOf("tap_ths", "lockout", "session_end")),
    Group("Battery", listOf("cutoff", "bat_mah")),
    Group("Power and Bluetooth", listOf("ths", "timeout", "report", "tx_power")),
)
private val DESCS = mapOf(
    "dark_on" to "Fixed: the light turns on below this reading. Auto: full brightness from here down.",
    "dark_off" to "Fixed: the light turns off above this reading. Auto: the light starts dimly here. Keep it higher than Dark below.",
    "confirm" to "Readings in a row (one every 2 s) before switching. Higher is calmer, lower reacts faster.",
    "bright" to "Fixed: brightness of the light. Auto: brightness at Dark below and darker. More drains the battery faster.",
    "bright_min" to "Auto: brightness at Bright above, where the light starts. It rises to Brightness as it gets darker.",
    "tilt_offset" to "How much darker the cant blinking is than the normal light. 0 = same brightness.",
    "fade" to "Auto: how long the light takes to follow a change in darkness. 0 = instantly.",
    "tilt_full" to "From this cant on the blinking no longer changes. A smaller angle makes small differences easier to see.",
    "blink_min" to "Blinks per second at the slow end of the range.",
    "blink_max" to "Blinks per second at the fast end of the range. Above about 10 it looks like a steady light.",
    "tilt_smooth" to "Higher is calmer against hand tremor, lower reacts faster.",
    "session_end" to "A session ends automatically after this long without a shot.",
    "vf" to "Only change this if you fit a different LED.",
    "level_tol" to "A cant up to this angle counts as level.",
    "tap_ths" to "Lower it if shots are missed, raise it if carrying the bow counts as a shot. The Status tab shows how strong your last shot was.",
    "lockout" to "After a shot, further impacts are ignored and the cant indicator pauses.",
    "cutoff" to "Below this voltage the light switches off to protect the battery. This is 0 %.",
    "ths" to "Lower wakes the sight with smaller movements.",
    "timeout" to "The sight sleeps after this time without movement.",
    "report" to "How often the sight sends its readings to the app while connected. Off saves a little power.",
    "tx_power" to "Higher reaches farther but uses a little more battery.",
    "bat_mah" to "The capacity printed on the battery. Used for the remaining runtime.",
)
private val LABELS = mapOf(
    "dark_on" to "Dark below", "dark_off" to "Bright above", "confirm" to "Readings before switching",
    "bright" to "Brightness", "bright_min" to "Start brightness", "tilt_offset" to "Blink dimming", "vf" to "LED voltage",
    "fade" to "Fade time", "tilt_full" to "Blinking range up to", "blink_min" to "Slowest blinking", "blink_max" to "Fastest blinking",
    "tilt_smooth" to "Smoothing", "session_end" to "End session after",
    "level_tol" to "Tolerance", "tap_ths" to "Shot threshold", "lockout" to "Pause after a shot",
    "cutoff" to "Cutoff voltage", "ths" to "Wake-up sensitivity", "timeout" to "Sleep after", "report" to "Update the app every",
    "tx_power" to "Bluetooth range", "bat_mah" to "Battery capacity",
)

private fun shownValue(it: CfgItem, v: Double): String {
    if (it.k == "tap_ths") return fmt(v * 0.25, 2) + " g"
    if (it.k == "ths") return "${(v * 125).roundToInt()} mg"
    if (it.zero && v == 0.0) return "off"
    val u = when (it.unit) { "deg" -> "°"; "Hz" -> " per s"; "" -> ""; else -> " " + it.unit }
    return fmt(v, it.dec) + u
}

@Composable
fun SettingsScreen(state: AppState, ctl: SightController, vm: SightViewModel) {
    val uv = LocalUv.current
    val context = LocalContext.current
    val connected = state.conn == ConnState.CONNECTED
    var askDefaults by remember { mutableStateOf(false) }
    var askDfu by remember { mutableStateOf(false) }
    var askClear by remember { mutableStateOf(false) }
    var newSetup by remember { mutableStateOf(false) }
    var setupMenu by remember { mutableStateOf<de.uvsight.core.SetupItem?>(null) }
    var rename by remember { mutableStateOf<de.uvsight.core.SetupItem?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            val cfg = state.cfg
            // Mode (phone-side)
            UvCard {
                Text("Mode", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                SegRow("Use the app", if (state.photoOnly) "Photo scoring only: no Bluetooth, no sight. Score from photos and collect training data, for example on a club mate's phone."
                    else "With the UV sight: connection, sessions, settings and history.", if (state.photoOnly) "Photos only" else "With sight", listOf("With sight", "Photos only"), first = true) { ctl.setPhotoOnly(it == "Photos only") }
            }
            Spacer(Modifier.height(14.dp))
            // The sights this phone knows: name the connected one, pick which to connect to, forget old ones
            if (!state.photoOnly && (connected && state.sight != null || state.sights.isNotEmpty())) {
                UvCard {
                    Text("Sights", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    val cur = state.sight
                    if (connected && cur != null) {
                        var nameText by remember(cur.id, cur.name) { mutableStateOf(cur.name) }
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Name of this sight", color = uv.ink)
                                Text("Shown in the app and behind \"UV-Sight\" in the Bluetooth name. Id ${cur.id}.", color = uv.muted, fontSize = 13.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(value = nameText, onValueChange = { nameText = it.take(16) }, singleLine = true, modifier = Modifier.width(130.dp), placeholder = { Text("e.g. Hoyt") },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { ctl.renameSight(nameText) }))
                        }
                        if (nameText.trim() != cur.name) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { SecondaryButton("Save name") { ctl.renameSight(nameText) } }
                    }
                    if (state.sights.size > 1 || (state.sights.size == 1 && (cur == null || !connected))) {
                        HorizontalDivider(color = uv.line)
                        Text("Known sights", color = uv.ink, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        Text("Sessions, sync and the distance, face and arrow diameter are kept per sight. Pick one to connect only to it.", color = uv.muted, fontSize = 13.sp)
                        for (si in state.sights) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).background(sightColor(si), CircleShape))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(si.label + (if (connected && si.id == cur?.id) " (connected)" else ""), color = uv.ink)
                                    Text((if (si.lastSeen > 0) "last seen " + fmtStamp(si.lastSeen) else "from an import") + (si.distM?.let { ", $it m" } ?: ""), color = uv.muted, fontSize = 12.sp)
                                }
                                FilterChip(selected = state.preferredSightId == si.id, onClick = { ctl.preferSight(if (state.preferredSightId == si.id) null else si.id) }, label = { Text(if (state.preferredSightId == si.id) "Preferred" else "Prefer") })
                                if (!(connected && si.id == cur?.id)) { Spacer(Modifier.width(6.dp)); SecondaryButton("Forget", danger = true) { ctl.forgetSight(si.id) } }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            if (state.photoOnly) { /* nothing of the sight applies */ }
            else if (!connected) EmptyBox("Connect to the sight on the Status tab to change settings.")
            else if (cfg == null) EmptyBox(if (state.cfgGaveUp) "The settings didn't arrive completely. Move closer to the sight and try again." else "Loading settings…") {
                if (state.cfgGaveUp) SecondaryButton("Try again") { ctl.retryCfg() }
            }
            else {
                val ses = state.session
                val lv = state.level ?: LevelInfo("off", "normal", false, "auto", true, true)
                UvCard(padding = 16) {
                    SwitchRow("Shot counter", "Counts shots and records sessions.", ses?.counter == true, first = true) { ctl.shotsSwitch(it) }
                    SegRow("Cant", (when (lv.mode) { "auto" -> "Measured during a session."; "on" -> "Measured whenever the bow is in use. Switches the aiming angle's On off."; else -> "Not measured." }) +
                        " Each end stores how canted you were." + if (lv.cal) "" else " Needs the calibration.", lv.mode, listOf("off", "auto", "on")) { ctl.setCantMode(it) }
                    SegRow("Aiming angle", (when (lv.angle) { "auto" -> "Measured at every shot during a session."; "on" -> "Measured at every shot, also outside a session (for setting up a sight). Switches the cant's On off."; else -> "Not measured." }) +
                        if (lv.cal) "" else " Needs the calibration.", lv.angle, listOf("off", "auto", "on")) { ctl.setAngleMode(it) }
                    if (lv.angle == "on" && state.hasLed) {
                        val rg = state.range
                        val ready = rg?.state == "ready"
                        val d = rg?.dist ?: 0
                        HorizontalDivider(color = uv.line)
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Distance to check", color = uv.ink)
                                Text(when {
                                    !ready -> "Works once the sight has learned your distances."
                                    d == 0 -> "Choose the distance of the target you aim at."
                                    rg?.estimated == true -> "Not learned yet at this distance: no warning here."
                                    else -> "Aim at it. Pulsing: right. Double blink: sight set too low. Triple blink: too high. Calm: almost (2–3 m off)."
                                }, color = uv.muted, fontSize = 13.sp)
                            }
                            RoundButton("−", enabled = ready) { ctl.setCheckDist(d - 10) }
                            Text("$d m", Modifier.padding(horizontal = 8.dp), fontWeight = FontWeight.Bold)
                            RoundButton("+", enabled = ready) { ctl.setCheckDist(if (d == 0) 10 else d + 10) }
                        }
                    }
                    val ledMode = state.status?.mode ?: "auto"
                    if (state.hasLed) SegRow("LED", (when (ledMode) { "auto" -> "Auto: switched by the light sensor."; "on" -> "On: always lit."; else -> "Off: stays dark." }) +
                        " Cant blinking works in every mode. Kept after a restart.", ledMode, listOf("off", "auto", "on")) { ctl.setLedMode(it) }
                }
                Spacer(Modifier.height(14.dp))

                val byKey = cfg.associateBy { it.k }
                val autoMode = byKey["bright_mode"]?.v == 1.0
                val used = HashSet<String>()
                for (g in GROUPS) {
                    if (g.led && !state.hasLed) continue
                    val items = g.keys.filter { state.hasLed || it !in LED_KEYS }.mapNotNull { byKey[it] }
                    g.keys.forEach { used.add(it) }          // hidden light settings must not resurface under Other
                    if (items.isEmpty() && !g.cal) continue
                    UvCard(padding = 16) {
                        Text(g.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        if (g.light) state.status?.let { Note("Current reading: ${it.light} (${if (it.dark) "dark" else "bright"}). Lower means darker.") }
                        if (g.cal) {
                            CalCard(state, ctl)
                            val enabled = lv.cal
                            if (lv.mode != "off") {
                                if (state.hasLed) SwitchRow("Blink when canted", "Off: the cant is still measured and stored, the LED just stays calm.", lv.cantSignal, enabled = enabled) { ctl.setCantSignal(it) }
                                if (state.hasLed) SegRow("Cant blinking mode", if (lv.style == "inverted") "Faster the closer you get to level." else "Faster the more the bow is canted.", lv.style, listOf("normal", "inverted"), enabled = enabled) { ctl.setStyle(it) }
                            }
                            if (lv.angle != "off") {
                                val rg = state.range
                                val ready = rg?.state == "ready"
                                if (state.hasLed) SwitchRow("Warn if the distance doesn't fit", when {
                                    ready -> "While aiming: double blink = aiming too low (arrow short), triple blink = too high (arrow long). Comes before the cant blinking."
                                    rg?.state == "anchor" -> "After a new calibration: shoot one end with the distance set, then this works again."
                                    else -> "Still learning: shoot a few ends at two or more distances with the distance set (${rg?.ends ?: 0} ends so far)."
                                }, lv.rangeSignal && ready, enabled = enabled && ready) { ctl.setRangeSignal(it) }
                            }
                        }
                        items.forEachIndexed { i, it ->
                            if (it.k == "bright_mode") {
                                SegRow("Brightness mode", if (autoMode) "Auto: fades in from Bright above and reaches full brightness at Dark below." else "Fixed: one brightness, switched at Dark below / Bright above.",
                                    if (autoMode) "auto" else "fixed", listOf("fixed", "auto"), first = i == 0 && !g.cal) { ctl.sendSet("bright_mode", if (it == "auto") 1.0 else 0.0) }
                                return@forEachIndexed
                            }
                            if ((it.k == "bright_min" || it.k == "fade") && !autoMode) return@forEachIndexed
                            SettingRow(it, first = i == 0 && !g.cal, enabled = !g.cal || lv.cal) { v -> ctl.sendSet(it.k, v) }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                val rest = cfg.filter { !used.contains(it.k) }
                if (rest.isNotEmpty()) {
                    UvCard { Text("Other", fontWeight = FontWeight.SemiBold, fontSize = 17.sp); rest.forEachIndexed { i, it -> SettingRow(it, first = i == 0) { v -> ctl.sendSet(it.k, v) } } }
                    Spacer(Modifier.height(14.dp))
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SecondaryButton("Restore defaults") { askDefaults = true } }
                Spacer(Modifier.height(14.dp))
            }

            // Setups
            val st = state.setups
            if (connected && st != null) {
                UvCard {
                    Text("Setups", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Note("One per arrow set or bow setting. Each learns its own arrow speed, so switching back needs no new learning.")
                    st.list.filter { !it.deleted }.forEachIndexed { i, it ->
                        val active = it.id == st.active
                        if (i > 0) HorizontalDivider(color = uv.line)
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(it.name + if (active) "  ✓" else "", color = uv.ink)
                                Text((if (it.kmh != null) "about ${it.kmh} km/h" else "learning") + ", ${it.ends} ${if (it.ends == 1) "end" else "ends"}" + if (active) ", in use" else "", color = uv.muted, fontSize = 13.sp)
                                val acc = remember(it.id, state.ends.size) { ctl.matchAccuracy(it.id) }
                                if (state.shotModel?.on != false) Text("Shot matching accuracy " + (acc?.let { a -> "${(a * 100).roundToInt()} %" } ?: "–") +
                                    (if (active && state.shotModel != null) ", ${state.shotModel!!.n} ${if (state.shotModel!!.n == 1) "example" else "examples"} learned" else ""), color = uv.muted, fontSize = 13.sp)
                            }
                            SecondaryButton("…") { setupMenu = it }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("New setup") { newSetup = true }
                    state.shotModel?.let { sm ->
                        SwitchRow("Shot matching", "The sight learns which arrow was which shot and tells the photo scoring. Off also stops the gyro.", sm.on) { ctl.shotMatching(it) }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // Sessions
            if (!state.photoOnly) UvCard {
                Text("Sessions", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                SwitchRow("Sync automatically", if (state.autoCopy) "Keeps the sessions on this phone and on the sight up to date whenever they are connected." else "Off: use Get from sight and Copy to sight in History.", state.autoCopy, first = true) { ctl.setAutoCopy(it) }
                SwitchRow("Show removed sessions", "Sessions you removed from this phone appear greyed out in History, so you can bring them back.", state.showHidden) { ctl.setShowHidden(it) }
                if (connected) {
                    HorizontalDivider(color = uv.line)
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Delete sessions on the sight", color = uv.ink)
                            Text("Deletes all sessions stored on the sight, for example before you give it away. The sessions on this phone are kept.", color = uv.muted, fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        SecondaryButton("Delete", danger = true) { askClear = true }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // Photo scoring and training data (phone-side settings)
            UvCard {
                val ph = state.photo
                val store = remember { de.uvsight.app.TrainingStore(context) }
                var dataVersion by remember { mutableIntStateOf(0) }
                val count = remember(dataVersion) { store.count() }
                val sizeMb = remember(dataVersion) { store.sizeBytes() / 1_048_576.0 }
                val scope = rememberCoroutineScope()
                val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                    uri?.let { u -> scope.launch { withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(u)?.use { store.exportZip(it) } } }.onFailure { vm.toast("Export failed: ${it.message}", true) }.onSuccess { vm.toast("Training data exported.") } } }
                }
                var askDelete by remember { mutableStateOf(false) }
                Text("Photo scoring", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                SegRow("Face", "Preselected on the photo screen; change it there per session.", runCatching { de.uvsight.core.FaceType.valueOf(ph.face) }.getOrDefault(de.uvsight.core.FaceType.WA40).label,
                    de.uvsight.core.FaceType.values().map { it.label }, first = true) { l -> de.uvsight.core.FaceType.values().firstOrNull { it.label == l }?.let { ctl.setPhotoPrefs(ph.copy(face = it.name)) } }
                SegRow("Photo source", "Camera opens the camera app for Score from photo. Gallery lets you pick a picture you took before, for phones whose camera app fails on the request.",
                    if (ph.source == "gallery") "Gallery" else "Camera", listOf("Camera", "Gallery")) { ctl.setPhotoPrefs(ph.copy(source = if (it == "Gallery") "gallery" else "camera")) }
                if (ph.source != "gallery") {
                    // Which camera app gets the request. Android 11+ hands the plain request to the system camera only,
                    // so a third-party camera (e.g. Open Camera) has to be named here.
                    // Android 11+ answers the plain capture query with system cameras only, so every installed
                    // package is asked whether it takes an explicit capture request (needs QUERY_ALL_PACKAGES).
                    val pm = context.packageManager
                    val cameraApps by produceState(initialValue = emptyList<Pair<String, String>>()) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                pm.getInstalledApplications(0).mapNotNull { ai ->
                                    val probe = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).setPackage(ai.packageName)
                                    if (pm.resolveActivity(probe, 0) != null) ai.packageName to pm.getApplicationLabel(ai).toString() else null
                                }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
                            }.getOrDefault(emptyList())
                        }
                    }
                    HorizontalDivider(color = uv.line)
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text("Camera app", color = uv.ink)
                        Text(if (cameraApps.size <= 1) "Only the built-in camera was found." else "System lets Android choose (usually the built-in camera). Pick another app if the built-in one fails.", color = uv.muted, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            FilterChip(selected = ph.cameraApp.isEmpty(), onClick = { ctl.setPhotoPrefs(ph.copy(cameraApp = "")) }, label = { Text("System") }, modifier = Modifier.padding(end = 6.dp))
                            for ((pkg, label) in cameraApps) FilterChip(selected = ph.cameraApp == pkg, onClick = { ctl.setPhotoPrefs(ph.copy(cameraApp = pkg)) }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
                        }
                    }
                }
                HorizontalDivider(color = uv.line)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Arrow diameter", color = uv.ink)
                        Text("Shaft diameter for the line-cutter rule: a shaft touching a line counts the higher ring.", color = uv.muted, fontSize = 13.sp)
                    }
                    var t by remember(ph.arrowMm) { mutableStateOf(fmt(ph.arrowMm, 1)) }
                    OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true, modifier = Modifier.width(96.dp), suffix = { Text("mm") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { t.replace(',', '.').toDoubleOrNull()?.let { v -> ctl.setPhotoPrefs(ph.copy(arrowMm = v.coerceIn(3.0, 12.0))) } }))
                }
                // Detection model
                val modelStore = remember { de.uvsight.app.ModelStore(context) }
                var modelVersion by remember { mutableIntStateOf(0) }
                val modelInfo = remember(modelVersion) { modelStore.info() }
                val modelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let { u -> scope.launch {
                        val name = u.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "model.tflite"
                        val res = withContext(Dispatchers.IO) { runCatching { val bytes = context.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: throw IllegalArgumentException("could not read the file"); modelStore.import(bytes, name) } }
                        res.onSuccess { vm.toast("Model imported: ${it.name}, input ${it.inputSize} px"); modelVersion++; if (ph.collect) ctl.setPhotoPrefs(ph.copy(collect = false)) }.onFailure { vm.toast(it.message ?: "Import failed", true) }
                    } }
                }
                HorizontalDivider(color = uv.line)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Detection model", color = uv.ink)
                        Text(modelInfo?.let { "${it.name}, input ${it.inputSize} px. Score from photo finds the arrows with it; check and correct the marks." } ?: "None. Score from photo works by hand; train a model with the trainer in training/ and import the .tflite file.", color = uv.muted, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton("Import") { modelLauncher.launch(arrayOf("*/*")) }
                    if (modelInfo != null) { Spacer(Modifier.width(8.dp)); SecondaryButton("Remove", danger = true) { modelStore.remove(); modelVersion++ } }
                }
                if (modelInfo != null) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Text("Confidence: ${(ph.modelConf * 100).roundToInt()} %", color = uv.muted, fontSize = 13.sp)
                        Slider(value = ph.modelConf.toFloat(), onValueChange = { ctl.setPhotoPrefs(ph.copy(modelConf = (it * 20).roundToInt() / 20.0)) }, valueRange = 0.1f..0.9f,
                            colors = SliderDefaults.colors(thumbColor = uv.gold, activeTrackColor = uv.gold))
                    }
                }
                SwitchRow("Collect training data", if (modelInfo != null) "Off while a detection model is installed. Remove the model to collect hand-marked photos again." else "Keeps every scored photo with its marks on this phone, so a recognition model can be trained later. Photos never leave the phone unless you export them.", ph.collect && modelInfo == null, enabled = modelInfo == null) { ctl.setPhotoPrefs(ph.copy(collect = it)) }
                if (ph.collect || count > 0) {
                    HorizontalDivider(color = uv.line)
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$count ${if (count == 1) "photo" else "photos"}, ${fmt(sizeMb, 1)} MB", color = uv.muted, modifier = Modifier.weight(1f))
                        SecondaryButton("Export", enabled = count > 0) { exportLauncher.launch("uv-sight-training-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())}.zip") }
                        Spacer(Modifier.width(8.dp))
                        SecondaryButton("Delete", enabled = count > 0, danger = true) { askDelete = true }
                    }
                }
                if (askDelete) AlertDialog(onDismissRequest = { askDelete = false }, title = { Text("Delete the training data?") },
                    text = { Text("All $count photos and their marks on this phone are deleted. Export them first if you want to keep them.") },
                    confirmButton = { TextButton(onClick = { askDelete = false; store.deleteAll(); dataVersion++ }) { Text("Delete", color = uv.red) } },
                    dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } })
            }
            Spacer(Modifier.height(14.dp))

            // Hints after an end (phone-side settings)
            if (!state.photoOnly) UvCard {
                val hp = state.hints
                Text("Hints", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                SwitchRow("Hints after each end", "Under the last end and in the session details: where the group sits, how steady the hold was, cant, release, rhythm. Always compared with your own earlier ends.", hp.enabled, first = true) { ctl.setHintPrefs(hp.copy(enabled = it)) }
                HorizontalDivider(color = uv.line)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sight clicks", color = uv.ink)
                        Text("How far one click of your sight moves the group at 18 m. With it, a shifted group is given in clicks; 0 = unknown.", color = uv.muted, fontSize = 13.sp)
                    }
                    var t by remember(hp.clickMmAt18) { mutableStateOf(if (hp.clickMmAt18 > 0) fmt(hp.clickMmAt18, 1) else "") }
                    OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true, modifier = Modifier.width(110.dp), suffix = { Text("mm") }, placeholder = { Text("0") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { ctl.setHintPrefs(hp.copy(clickMmAt18 = (t.replace(',', '.').toDoubleOrNull() ?: 0.0).coerceIn(0.0, 100.0))) }))
                }
            }
            if (!state.photoOnly) Spacer(Modifier.height(14.dp))

            // Notifications and vibration (phone-side settings)
            if (!state.photoOnly) UvCard {
                val n = state.notif
                Text("Notifications and vibration", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                SwitchRow("Session notification", "Shows the running session (end, arrow, average) and keeps the connection alive while the phone is in your pocket. Off: the connection only lasts while the app is open.", n.session, first = true) { ctl.setNotifPrefs(n.copy(session = it)) }
                SwitchRow("Vibrate while setting up a sight", "Only with the aiming angle set to On: 2 pulses = aiming too low, 3 = too high, one long pulse = the aim fits.", n.rangeVibe) { ctl.setNotifPrefs(n.copy(rangeVibe = it)) }
                if (n.rangeVibe) SegRow("Vibration strength", "", n.vibeStrength, listOf("light", "medium", "strong")) { ctl.setNotifPrefs(n.copy(vibeStrength = it)) }
                SwitchRow("Session ended by the sight", "Tells you when the sight closed a session on its own after a long pause.", n.autoEnd) { ctl.setNotifPrefs(n.copy(autoEnd = it)) }
                SwitchRow("Sight battery low", "Warns below ${SightController.LOW_BAT_PCT} % and when the sight locks the light.", n.lowBat) { ctl.setNotifPrefs(n.copy(lowBat = it)) }
                SwitchRow("Charging finished", "Tells you when the sight is full while it is connected over USB.", n.chargeFull) { ctl.setNotifPrefs(n.copy(chargeFull = it)) }
                Note("If the notification disappears when the screen is off, exclude UV-Sight from your phone's battery optimisation.")
            }
            Spacer(Modifier.height(14.dp))

            // App and firmware
            UvCard {
                Text("App and firmware", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text("Language", color = uv.ink)
                    Text("Phone language when a pack for it exists, else English. Language packs are plain text files in the app's source, new ones arrive with app updates.", color = uv.muted, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(selected = state.language.isEmpty(), onClick = { vm.setLanguage("") }, label = { Text("Phone language") }, modifier = Modifier.padding(end = 6.dp))
                        for ((code, name) in state.languages) FilterChip(selected = state.language == code, onClick = { vm.setLanguage(code) }, label = { Text(name) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                HorizontalDivider(color = uv.line)
                if (!state.photoOnly) SwitchRow("Show console", "A tab with the raw messages of the sight, for troubleshooting.", state.consoleEnabled, first = true) { ctl.setConsoleEnabled(it) }
                if (connected) {
                    HorizontalDivider(color = uv.line)
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Firmware update", color = uv.ink)
                            Text("Prepares the sight for new firmware. Connect it to your computer with the USB cable first.", color = uv.muted, fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        SecondaryButton("Start") { askDfu = true }
                    }
                }
                val li = state.loginfo
                Note("App $APP_VERSION" + (state.hello?.let { ", firmware ${it.fw}" } ?: "") +
                    (li?.let { if (it.ok) ". The sight stores ${it.count} ${if (it.count == 1) "session" else "sessions"} (room for about ${it.capacity / 1000 * 1000})." else ". The sight's session memory is not working." } ?: ""))
            }
            Spacer(Modifier.height(if (state.cfgDirty) 90.dp else 24.dp))
        }

        if (connected && state.cfg != null && state.cfgDirty) {
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), shape = CardShape, color = uv.ink) {
                Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Changes apply right away. Save them to keep them after the sight restarts.", color = uv.paper, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    PrimaryButton("Save", gold = true) { ctl.saveSettings() }
                }
            }
        }
    }

    if (askDefaults) AlertDialog(onDismissRequest = { askDefaults = false }, title = { Text("Restore defaults?") },
        text = { Text("All settings go back to their defaults. Tap Save afterwards to keep them.") },
        confirmButton = { TextButton(onClick = { askDefaults = false; ctl.restoreDefaults() }) { Text("Restore", color = uv.red) } },
        dismissButton = { TextButton(onClick = { askDefaults = false }) { Text("Cancel") } })
    if (askDfu) AlertDialog(onDismissRequest = { askDfu = false }, title = { Text("Prepare a firmware update?") },
        text = { Text("Connect the sight to your computer with the USB cable first. It then shows up there as a drive; copy the new firmware onto it. Until then the sight does nothing else, and the app disconnects.") },
        confirmButton = { TextButton(onClick = { askDfu = false; ctl.dfu() }) { Text("Start", color = uv.red) } },
        dismissButton = { TextButton(onClick = { askDfu = false }) { Text("Cancel") } })
    if (askClear) AlertDialog(onDismissRequest = { askClear = false }, title = { Text("Delete all sessions on the sight?") },
        text = { Text("All ${state.loginfo?.let { "${it.count} " } ?: ""}sessions stored on the sight are deleted. The sessions on this phone are kept.") },
        confirmButton = { TextButton(onClick = { askClear = false; ctl.clearSightLog() }) { Text("Delete", color = uv.red) } },
        dismissButton = { TextButton(onClick = { askClear = false }) { Text("Cancel") } })
    if (newSetup) TextPrompt("New setup", "For example the arrow type. Up to 18 characters.", "", onDone = { newSetup = false; if (it != null) ctl.setupNew(it) })
    rename?.let { it -> TextPrompt("Rename setup", "Up to 18 characters.", it.name, onDone = { n -> rename = null; if (n != null) ctl.setupRename(it.id, n) }) }
    setupMenu?.let { it ->
        val active = it.id == state.setups?.active
        AlertDialog(onDismissRequest = { setupMenu = null }, title = { Text(it.name) },
            text = { Text(if (active) "This setup is in use." else "Use this setup from now on? Deleting keeps its ends in your sessions, under this name. It just can't be chosen any more.") },
            confirmButton = {
                Row {
                    if (!active) TextButton(onClick = { setupMenu = null; ctl.setupUse(it.id) }) { Text("Use") }
                    TextButton(onClick = { setupMenu = null; rename = it }) { Text("Rename") }
                    TextButton(onClick = { setupMenu = null; ctl.shotReset(it.id) }) { Text("Reset learning") }
                    if (!active) TextButton(onClick = { setupMenu = null; ctl.setupDelete(it.id) }) { Text("Delete", color = uv.red) }
                }
            },
            dismissButton = { TextButton(onClick = { setupMenu = null }) { Text("Cancel") } })
    }
}

@Composable
private fun TextPrompt(title: String, text: String, initial: String, onDone: (String?) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = { onDone(null) }, title = { Text(title) },
        text = { Column { Text(text, color = LocalUv.current.muted, fontSize = 14.sp); Spacer(Modifier.height(8.dp)); OutlinedTextField(value = value, onValueChange = { if (it.length <= 18) value = it }, singleLine = true) } },
        confirmButton = { TextButton(onClick = { onDone(value.trim().ifEmpty { null }) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } })
}

@Composable
private fun SettingRow(it: CfgItem, first: Boolean, enabled: Boolean = true, onSet: (Double) -> Unit) {
    val uv = LocalUv.current
    val step = if (it.dec > 0) 10.0.pow(-it.dec) else 1.0
    val off = it.zero && it.v == 0.0
    var value by remember(it.v) { mutableStateOf(it.v) }
    var text by remember(it.v) { mutableStateOf(fmt(if (off) it.min else it.v, it.dec)) }
    if (!first) HorizontalDivider(color = uv.line)
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(LABELS[it.k] ?: it.k, color = uv.ink)
            Text(shownValue(it, value), fontWeight = FontWeight.Bold, color = uv.ink)
        }
        Text("${DESCS[it.k] ?: (it.d + ".")} Default ${shownValue(it, it.def)}, range ${shownValue(it, it.min)} to ${shownValue(it, it.max)}.", color = uv.muted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val steps = ((it.max - it.min) / step).roundToInt() - 1
            Slider(value = (if (off) it.min else value).toFloat(), onValueChange = { v -> val r = (v / step).roundToInt() * step; value = r; text = fmt(r, it.dec) },
                onValueChangeFinished = { onSet(value) }, valueRange = it.min.toFloat()..it.max.toFloat(), steps = if (steps in 1..400) steps else 0,
                enabled = enabled && !off, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = uv.gold, activeTrackColor = uv.gold))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(value = text, onValueChange = { text = it }, enabled = enabled && !off, singleLine = true, modifier = Modifier.width(96.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { text.replace(',', '.').toDoubleOrNull()?.let { v -> val c = v.coerceIn(it.min, it.max); value = c; text = fmt(c, it.dec); onSet(c) } }))
            if (it.zero) {
                Checkbox(checked = off, onCheckedChange = { on -> onSet(if (on) 0.0 else it.def) }, enabled = enabled)
                Text("Off", color = uv.muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun CalCard(state: AppState, ctl: SightController) {
    val uv = LocalUv.current
    val lv = state.level
    if (state.calStep == 0) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Calibration", color = uv.ink)
                Text(if (lv?.cal == true) "Calibrated." else "Not calibrated yet. Everything in this group needs it.", color = uv.muted, fontSize = 13.sp)
            }
            SecondaryButton("Calibrate") { ctl.calStart() }
        }
        return
    }
    val text = when {
        state.calBusy -> "Measuring. Keep the bow still…"
        state.calStep == 1 -> "Step 1 of 2: Put the bow in a stand, level it with the bubble and aim horizontally. Then tap Measure and keep the bow still."
        state.calStep == 2 -> "Step 2 of 2: Keep the bow level, but aim clearly up, arrow tip up, at least 20 degrees. Then tap Measure and keep it still."
        else -> "Calibration saved. Check the cant reading in a session: it should stay near 0° with the bubble centred."
    }
    Surface(Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = SmallShape, color = uv.surface, border = androidx.compose.foundation.BorderStroke(2.dp, uv.gold)) {
        Column(Modifier.padding(12.dp)) {
            Text(text)
            if (state.calError.isNotEmpty()) Text(state.calError, color = uv.red)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.calStep < 3 && !state.calBusy) PrimaryButton("Measure") { ctl.calMeasure() }
                if (!state.calBusy) SecondaryButton(if (state.calStep == 3) "Done" else "Cancel") { ctl.calCancel() }
            }
        }
    }
}
