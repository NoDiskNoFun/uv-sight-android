package de.uvsight.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val APP_VERSION = "1.0"

/** Everything the screens show. Immutable; replaced on every change. */
data class AppState(
    val conn: ConnState = ConnState.OFF,
    val sightAsleep: Boolean = false,
    val autoReconnect: Boolean = false,
    val hello: HelloInfo? = null,
    val protoWarning: String? = null,
    val status: StatusInfo? = null,
    val bat: BatInfo? = null,
    val cfg: List<CfgItem>? = null,
    val cfgDirty: Boolean = false,
    val cfgGaveUp: Boolean = false,
    val session: SessionInfo? = null,
    val level: LevelInfo? = null,
    val range: RangeInfo? = null,
    val setups: SetupsInfo? = null,
    val loginfo: LogInfo? = null,
    // training
    val entries: List<String> = emptyList(),
    val awaitingEnd: Boolean = false,
    val reconnecting: Boolean = false,
    val confirm: ConfirmPrompt? = null,
    val lastEnd: EndMsg? = null,
    val lastAng: String = "",
    val distM: Int = 0,
    val distAuto: Boolean = false,
    // history
    val sessions: List<ArchiveSession> = emptyList(),
    val ends: Map<String, List<EndDetail>> = emptyMap(),
    val showHidden: Boolean = false,
    val autoCopy: Boolean = true,
    val syncLine: String = "",
    val copyProgress: String = "",
    val copyChoice: List<ArchiveSession>? = null,
    val afterClearCount: Int? = null,
    // settings
    val calStep: Int = 0,
    val calBusy: Boolean = false,
    val calError: String = "",
    // console
    val console: List<ConsoleLine> = emptyList(),
    val consoleEnabled: Boolean = false,
    val photoOnly: Boolean = false,      // "Photo scoring only" mode: no sight, no Bluetooth, just photos and training data
    val sight: SightInfo? = null,        // the sight of the current/last link, once it said hello
    val sights: List<SightInfo> = emptyList(),
    val preferredSightId: String? = null, // connect only to this sight (null = whichever is awake)
    val view: View = View.STATUS,
    // notifications / vibration
    val notif: NotifPrefs = NotifPrefs(),
    val rangeLive: RangeLive = RangeLive(),
    // photo scoring
    val photo: PhotoPrefs = PhotoPrefs(),
    val pendingHits: List<Hit>? = null,   // positions behind the current entries, if they came from a photo
    val pendingMatch: ShotMatch.Result? = null,   // which shot each of those arrows was, as matched on the photo
    val pendingMatchSrc: List<String>? = null,    // per arrow: sure / guess / coin / user
    val endShots: EndShots? = null,       // the sight's shots of the open end (answer to "shot list")
    val shotModel: ShotModelInfo? = null, // the sight's shot matching model (active setup)
    val trace: AimTrace? = null,          // the aim trace asked for with "shot trace"
    val hints: HintPrefs = HintPrefs(),
    val language: String = "",            // "" = the phone's language, else a pack code such as "de"
    val languages: List<Pair<String, String>> = emptyList(),   // available packs: code to name
) {
    val hasData get() = status != null
    fun cfgValue(k: String): Double? = cfg?.firstOrNull { it.k == k }?.v
    val currentSessionKey: String? get() = session?.key
    /** The connected sight has a UV LED (false for a sight built without it: light options are hidden). */
    val hasLed: Boolean get() = hello?.led != false
    val distNote: String get() = when {
        distAuto -> "detected"
        range?.estimated == true && distM > 0 -> "not learned yet"
        else -> ""
    }
}

/**
 * The application logic of the UV-Sight app, independent of Android: talks to the sight
 * through [Transport], keeps the archive in [KeyValueStore] and publishes [AppState].
 * All methods must be called on the dispatcher of [scope] (the main thread on Android).
 */
class SightController(
    private val scope: CoroutineScope,
    private val store: KeyValueStore,
    private val transport: Transport,
    private val ui: UiSink,
    private val clock: Clock = SystemClock,
) {
    companion object {
        const val STATUS_POLL_MS = 5000L
        const val CONSOLE_MAX = 400
        const val MAX_ARROWS = 40
        const val RECONNECT_TIMEOUT_MS = 10_000L
        const val AUTO_FAST_MS = 5000L
        const val AUTO_SLOW_MS = 20_000L
        const val AUTO_FAST_FOR = 2 * 60 * 1000L
        const val AUTO_GIVE_UP = 30 * 60 * 1000L
        const val DIST_PRESET_MS = 30 * 60 * 1000L
        const val SYNC_MAX_RETRIES = 2
        const val CFG_MAX_RETRIES = 3
        const val DIST_KEY = "uvsight.dist"
        const val DIST_LAST_KEY = "uvsight.distLast"
        const val DIST_SESSION_KEY = "uvsight.distSession"
        const val TRAIN_KEY = "uvsight.training.v1"
        const val SHOW_HIDDEN_KEY = "uvsight.showHidden"
        const val HIDDEN_FETCHED_KEY = "uvsight.hiddenFetched"
        const val AUTOCOPY_KEY = "uvsight.autocopy"
        const val ONSIGHT_INIT_KEY = "uvsight.onsight.init"
        const val ENDS_BACKFILL_KEY = "uvsight.endsBackfill.v1"
        const val CONSOLE_KEY = "uvsight.console"
        const val MODE_KEY = "uvsight.mode"
        const val PREFER_SIGHT_KEY = "uvsight.sight.prefer"
        const val PREFER_ADDRESS_KEY = "uvsight.ble.prefer"   // read by the Bluetooth layer: only this address is accepted
        const val NOTIF_KEY = "uvsight.notif"
        const val HINTS_KEY = "uvsight.hints"
        const val LANG_KEY = "uvsight.lang"
        const val PHOTO_KEY = "uvsight.photo"
        const val LOW_BAT_PCT = 15
    }

    val archive = Archive(store, clock)
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state
    private val _events = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 16)
    /** Notification-worthy events (session ended by the sight, low battery, charged). */
    val events: SharedFlow<CoreEvent> = _events
    private var lowBatWarned = false
    private val s get() = _state.value
    private inline fun set(f: AppState.() -> AppState) { _state.update { it.f() } }

    // ---- private state mirroring the web app ----
    private var helloWait: CompletableDeferred<Boolean>? = null
    private var pollJob: Job? = null
    private var awaitJob: Job? = null
    private var cfgBuf: MutableList<CfgItem>? = null
    private var cfgN = 0
    private var cfgRetries = 0
    private var cfgRetryJob: Job? = null
    private var expectDefaults = false
    private var lastSentScores: List<String>? = null
    private var lastSentHits: List<Hit>? = null
    private var lastSentMatch: ShotMatch.Result? = null
    private var lastSentMatchSrc: List<String>? = null
    private var distPresetAt = 0L
    private var levelResyncJob: Job? = null
    private var autoReconnectJob: Job? = null
    private var autoStart = 0L
    private var connectJob: Job? = null
    private var pendingUseNew = false
    private var calBusy = false

    // sync
    private var syncEpoch: Long? = null
    private var inLogAnswer = false
    private var syncCount = 0
    private var syncGot = 0
    private var syncSince = 0L
    private var syncRetries = 0
    private var syncResync = false
    private var syncSeen: MutableSet<String>? = null
    private var endsSeen: MutableSet<String>? = null
    private var fullScan = false
    private var hiddenFetch = false
    private var backfill = ArrayList<ArchiveSession>()
    private var liveSlot: LiveSlot? = null
    private var autoCopyState: AutoCopy? = null
    private var copyState: CopyState? = null
    private var endPut: EndPut? = null
    private var pendingDel: PendingDel? = null

    private class LiveSlot(val epoch: Long?, val id: Long?, val stored: Boolean, val error: String?, val manual: Boolean)
    private class AutoCopy(val queue: List<ArchiveSession>) { var i = 0; var added = 0; var timer: Job? = null; var wait: Job? = null }
    private class CopyState { var phase = "keys"; val keys = HashSet<String>(); var queue: List<ArchiveSession> = emptyList(); var done = 0; var added = 0; var exists = 0; var timer: Job? = null }
    private class EndPut(val r: ArchiveSession, val list: List<EndDetail>, val done: () -> Unit) { var i = 0; var timer: Job? = null; var wait: Job? = null }
    private class PendingDel(val r: ArchiveSession, val timer: Job)

    init {
        // 3.3 repair of the web app: fetch the whole log once
        if (store.get("uvsight.logseq.repair") != "3.3") { archive.logSeq = 0; store.put("uvsight.logseq.repair", "3.3") }
        loadTraining()
        val notif = store.get(NOTIF_KEY)?.let { runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(NotifPrefs.serializer(), it) }.getOrNull() } ?: NotifPrefs()
        val photo = store.get(PHOTO_KEY)?.let { runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(PhotoPrefs.serializer(), it) }.getOrNull() } ?: PhotoPrefs()
        val hints = store.get(HINTS_KEY)?.let { runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(HintPrefs.serializer(), it) }.getOrNull() } ?: HintPrefs()
        set { copy(distM = store.get(DIST_KEY)?.toIntOrNull() ?: 0, showHidden = store.get(SHOW_HIDDEN_KEY) == "1",
            autoCopy = store.get(AUTOCOPY_KEY) != "off", consoleEnabled = store.get(CONSOLE_KEY) == "1", notif = notif, photo = photo,
            photoOnly = store.get(MODE_KEY) == "photo", sights = archive.sights(), preferredSightId = store.get(PREFER_SIGHT_KEY)?.takeIf { it.isNotEmpty() }, hints = hints) }
        if (s.photoOnly) set { copy(view = View.TRAINING) }
        refreshHistory()
    }

    // ------------------------------------------------------------------ console / helpers
    private fun logConsole(text: String, kind: String = "") {
        set { copy(console = (console + ConsoleLine(text, kind)).takeLast(CONSOLE_MAX)) }
    }

    fun send(cmd: String) {
        logConsole("> $cmd", "in")
        transport.send(cmd)
    }

    private fun toast(text: String, error: Boolean = false) = ui.toast(text, error)

    private fun tzOffsetMinutes(): Int = TimeZone.getDefault().getOffset(clock.now()) / 60_000

    // ------------------------------------------------------------------ link lifecycle
    /** The transport is connected and notifications are enabled. */
    fun onLinkUp() {
        scope.launch {
            set { copy(conn = ConnState.CONNECTING) }
            val wait = CompletableDeferred<Boolean>()
            helloWait = wait
            send("app on")
            withTimeoutOrNull(4000) { wait.await() }
            if (helloWait === wait) helloWait = null
            if (!transport.connected) return@launch
            setConn(ConnState.CONNECTED)
            send("time ${clock.now() / 1000} ${tzOffsetMinutes()}")
            val st = s
            if (st.distM > 0 && !st.distAuto && st.session?.active == true) send("dist ${st.distM}")
            startPolling()
        }
    }

    fun onLinkDown() {
        cfgBuf = null; cfgRetries = 0; cfgRetryJob?.cancel()
        setAwaiting(false)
        set { copy(confirm = null) }
        stopPolling()
        stopAutoCopy()
        fullScan = false; syncSeen = null; syncEpoch = null; inLogAnswer = false; syncRetries = 0
        lowBatWarned = false
        set { copy(rangeLive = RangeLive()) }
        val wasConnected = s.conn == ConnState.CONNECTED
        setConn(ConnState.OFF)
        if (wasConnected && !s.sightAsleep) startAutoReconnect()
    }

    fun onLinkConnecting() { setConn(ConnState.CONNECTING) }

    private fun setConn(c: ConnState) {
        if (c == ConnState.CONNECTED) { stopAutoReconnect(); set { copy(sightAsleep = false) } }
        set { copy(conn = c) }
    }

    /** "Connect" tapped: run the chooser / connect to the remembered device. */
    fun connect() { scope.launch { ensureConnected() } }
    fun reconnect() { stopAutoReconnect(); scope.launch { ensureConnected() } }

    private suspend fun ensureConnected(): Boolean {
        if (s.conn == ConnState.CONNECTED) return true
        connectJob?.let { return s.conn == ConnState.CONNECTED }
        val job = scope.launch {
            val ok = try { transport.ensureConnected(RECONNECT_TIMEOUT_MS) } catch (e: Exception) { false }
            if (!ok && s.conn != ConnState.CONNECTED) {
                setConn(ConnState.OFF)
                toast(tr("Sight not reachable. Move the bow to wake it, then try again."), true)
            }
        }
        connectJob = job
        job.join()
        connectJob = null
        return s.conn == ConnState.CONNECTED
    }

    private suspend fun withConnection(action: () -> Unit): Boolean {
        if (s.conn != ConnState.CONNECTED) {
            set { copy(reconnecting = true) }
            val ok = ensureConnected()
            set { copy(reconnecting = false) }
            if (!ok) { toast(tr("Sight not reachable. Your scores are kept, tap again when you are in range."), true); return false }
        }
        action()
        return true
    }

    // ---- automatic reconnect after an unexpected loss (not when the sight sleeps) ----
    private fun startAutoReconnect() {
        set { copy(autoReconnect = true) }
        autoStart = clock.now()
        scheduleAutoReconnect(AUTO_FAST_MS)
    }
    private fun stopAutoReconnect() { autoReconnectJob?.cancel(); autoReconnectJob = null; set { copy(autoReconnect = false) } }
    private fun scheduleAutoReconnect(ms: Long) {
        autoReconnectJob?.cancel()
        autoReconnectJob = scope.launch { delay(ms); autoReconnectTry() }
    }
    private var appVisible = true
    fun onAppVisible(visible: Boolean) { appVisible = visible; if (visible && s.autoReconnect) scheduleAutoReconnect(0) }
    private suspend fun autoReconnectTry() {
        if (!s.autoReconnect || s.conn == ConnState.CONNECTED) return
        val elapsed = clock.now() - autoStart
        if (elapsed > AUTO_GIVE_UP) { stopAutoReconnect(); toast(tr("Could not reach the sight for 30 min. Tap Reconnect when it is near.")); return }
        if (appVisible && !s.reconnecting) {
            val ok = try { transport.ensureConnected(RECONNECT_TIMEOUT_MS) } catch (e: Exception) { false }
            if (ok || s.conn == ConnState.CONNECTED) return
            setConn(ConnState.OFF)
        }
        if (s.autoReconnect) scheduleAutoReconnect(if (elapsed < AUTO_FAST_FOR) AUTO_FAST_MS else AUTO_SLOW_MS)
    }

    // ------------------------------------------------------------------ incoming lines
    fun onLine(line: String) {
        if (!line.startsWith("{")) { logConsole(line, "raw"); return }
        val m = Msg.parse(line) ?: run { logConsole(line, "err"); return }
        logConsole(line, if (m.t == "err") "err" else "")
        when (m.t) {
            "hello" -> {
                val h = m.toHello()
                set { copy(hello = h, protoWarning = when {
                    h.proto == PROTO_EXPECTED -> null
                    h.proto < PROTO_EXPECTED -> "The sight's firmware is older than this app. Please update the firmware on the sight."
                    else -> "This app is older than the sight's firmware. Please update the app."
                }) }
                helloWait?.complete(true); helloWait = null
                h.id?.let { onSightIdentified(it, h.sightName) }
                scope.launch { delay(1500); autoSync() }
            }
            "status" -> {
                val st = m.toStatus()
                set { copy(status = st) }
                if (!lowBatWarned && st.pct < LOW_BAT_PCT && st.chg == "no USB" && s.conn == ConnState.CONNECTED) { lowBatWarned = true; _events.tryEmit(CoreEvent.LowBattery(st.pct)) }
            }
            "bat" -> set { copy(bat = m.toBat()) }
            "cfgStart" -> { cfgBuf = ArrayList(); cfgN = m.int("n") ?: 0 }
            "cfgItem" -> cfgBuf?.add(m.toCfgItem())
            "cfgEnd" -> {
                val buf = cfgBuf
                if (buf != null && buf.size == cfgN) {
                    set { copy(cfg = buf.toList(), cfgGaveUp = false) }
                    cfgRetries = 0; cfgRetryJob?.cancel()
                    if (expectDefaults) { set { copy(cfgDirty = true) }; expectDefaults = false }
                } else if (s.cfg == null) requestCfg()
                cfgBuf = null
            }
            "session" -> {
                val ses = m.toSession()
                set { copy(session = ses) }
                archive.rememberStart(ses)
                if (!checkNewSession(ses)) adoptSightDistance(ses)
                saveTraining()
            }
            "level" -> set { copy(level = m.toLevel()) }
            "shot" -> {
                val ang = m.dbl("ang"); val cant = m.dbl("cant")
                val txt = listOfNotNull(ang?.let { "angle ${fmt(it, 2)}°" }, cant?.let { "cant ${fmt(it, 1)}°" }).joinToString(", ")
                set {
                    val st = status?.let { if (m.dbl("g") != null) it.copy(lastShotG = m.dbl("g"), lastShotClip = m.bool("clip") ?: false) else it }
                    val se = session?.copy(active = true, end = m.int("end") ?: session.end, endShots = m.int("endShots") ?: session.endShots, shots = m.int("total") ?: session.shots)
                    copy(lastAng = txt, status = st, session = se, endShots = null)
                }
                saveTraining()
            }
            "end" -> {
                val e = m.toEnd()
                set { copy(lastEnd = e, endShots = null) }
                val teach = if (e.valid && lastSentHits != null && lastSentMatch != null && lastSentMatchSrc != null) ShotMatch.teachLine(e.n, lastSentHits!!, lastSentMatch!!.shotOf, lastSentMatchSrc!!) else null
                recordEnd(e)
                if (teach != null) send(teach)
                if (s.awaitingEnd) { set { copy(entries = emptyList(), pendingHits = null, pendingMatch = null, pendingMatchSrc = null) }; setAwaiting(false) }
                if (e.valid) toast(tr("End {n} saved: {sum} points", "n" to e.n, "sum" to e.sum)) else toast(tr("End {n} marked invalid", "n" to e.n))
                saveTraining()
            }
            "confirm" -> { setAwaiting(false); val c = m.toConfirm(); set { copy(confirm = ConfirmPrompt(c.end, c.counted, c.entered, c.split || c.entered < c.counted)) } }
            "discarded" -> { setAwaiting(false); toast(tr("Not saved. Correct the scores and save again.")) }
            "sessionEnd" -> {
                set { copy(entries = emptyList(), lastEnd = null, pendingHits = null) }
                setAwaiting(false)
                if (!m.has("epoch")) { liveSlot = null; toast(tr("Empty session, nothing saved.")) }
                else liveSlot = LiveSlot(m.long("epoch"), m.long("id"), m.bool("stored") ?: false, m.str("error"), m.bool("manual") ?: true)
                send("shots")
                saveTraining()
            }
            "logStart" -> {
                syncEpoch = m.long("epoch"); inLogAnswer = true
                syncEpoch?.let { e -> s.sight?.let { si -> if (e !in si.epochs) updateSight { it.copy(epochs = it.epochs + e) }; archive.tagSight(e, si.id) } }
                syncCount = 0; syncGot = 0
                syncSince = m.long("since") ?: archive.logSeq
                syncSeen = HashSet(); endsSeen = HashSet()
                syncResync = (m.long("last") ?: Long.MAX_VALUE) < archive.logSeq
            }
            "slot" -> {
                val sl = m.toSlot()
                val ls = liveSlot
                if (!inLogAnswer && ls != null && sl.epoch == ls.epoch && sl.id == ls.id) { handleLiveSlot(sl); return finish() }
                copyState?.let { if (it.phase == "keys") it.keys.add("${sl.epoch}-${sl.id}") }
                if (!inLogAnswer) { archive.archiveSlot(sl.epoch, sl, false, s.sight?.id); refreshHistory(); return finish() }
                syncGot++
                syncSeen?.add("${sl.epoch ?: syncEpoch}-${sl.id}")
                if (archive.archiveSlot(sl.epoch ?: syncEpoch, sl, hiddenFetch, s.sight?.id)) syncCount++
                refreshHistory()
            }
            "endrec" -> { val e = m.toEndRec(); if (inLogAnswer) syncGot++; endsSeen?.add("${e.epoch}-${e.id}"); archive.mergeEndRec(e); refreshHistory() }
            "logEnd" -> onLogEnd(m)
            "loginfo" -> set { copy(loginfo = m.toLogInfo()) }
            "range" -> set { copy(range = m.toRange()) }
            "shots" -> set { copy(endShots = m.toShots()) }
            "shotModel" -> set { copy(shotModel = m.toShotModel()) }
            "trace" -> set { copy(trace = m.toTrace()) }
            "setupsStart" -> setupBuf = SetupsInfo(m.int("active") ?: 0, emptyList())
            "setupItem" -> setupBuf?.let { setupBuf = it.copy(list = it.list + m.toSetupItem()) }
            "setupsEnd" -> setupBuf?.let { b ->
                set { copy(setups = b) }
                val names = archive.setupNames().toMutableMap()
                for (it in b.list) names[it.id.toString()] = it.name
                archive.saveSetupNames(names)
                setupBuf = null
            }
            "ack" -> onAck(m)
            "cal" -> onCal(m.toCal())
            "event" -> when (m.str("e")) {
                "idle" -> { set { copy(sightAsleep = true) }; toast(tr("Bow at rest. The sight went to sleep.")) }
                "lowbat" -> { toast(tr("Battery empty. LED is off until you charge."), true); lowBatWarned = true; _events.tryEmit(CoreEvent.LowBattery(s.status?.pct ?: 0)) }
                "charge" -> { val st = m.str("state") ?: ""; toast(chargeLabel(st)); if (st == "full") _events.tryEmit(CoreEvent.ChargeFull) }
                "range" -> set { copy(rangeLive = RangeLive(m.str("warn") ?: "", m.bool("ok") ?: false)) }
            }
            "err" -> {
                if (copyState?.phase == "put") copyOnAck("error", null, null)
                if (s.awaitingEnd) setAwaiting(false)
                toast(m.str("text") ?: tr("Error"), true)
            }
        }
    }
    private fun finish() {}
    private var setupBuf: SetupsInfo? = null

    fun chargeLabel(c: String) = when (c) { "charging" -> "Charging"; "full" -> "Charged, cable connected"; "no USB" -> "On battery"; else -> c }

    private fun onAck(m: Msg) {
        when (m.str("cmd")) {
            "set" -> { val k = m.str("k"); val v = m.dbl("v"); set { copy(cfg = cfg?.map { if (it.k == k && v != null) it.copy(v = v) else it }, cfgDirty = true) } }
            "save" -> { set { copy(cfgDirty = false) }; toast(tr("Settings saved on the sight")) }
            "mode" -> set { copy(status = status?.copy(mode = m.str("mode") ?: "auto")) }
            "setup" -> if (m.bool("ok") == true && m.has("id") && pendingUseNew) { pendingUseNew = false; send("setup use ${m.int("id")}") }
            "put" -> if (!autoCopyOnAck(m)) copyOnAck(m.str("result"), m.long("epoch"), m.long("id"))
            "putend" -> endPutOnAck()
            "del" -> delOnAck(m)
            "clear" -> { send("log info"); if (m.bool("ok") != true) toast(tr("Could not delete the sessions on the sight."), true) else afterClear(m.int("count") ?: 0) }
            "dfu" -> toast(tr("The sight now shows up as a drive on your computer. Copy the new firmware onto it."))
            "name" -> { val n = m.str("name") ?: ""; if (s.sight != null) { updateSight { it.copy(name = n) }; toast(if (n.isEmpty()) tr("Name removed from the sight") else tr("Sight named {n}", "n" to n)) } }
        }
    }

    // ------------------------------------------------------------------ status polling
    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            while (true) {
                delay(STATUS_POLL_MS)
                val st = s
                if (st.conn == ConnState.CONNECTED && (st.view == View.STATUS || st.view == View.SETTINGS) && st.confirm == null && appVisible) send("status")
            }
        }
    }
    private fun stopPolling() { pollJob?.cancel(); pollJob = null }

    /** A tab was opened: same requests as the web app makes on a tab switch. */
    fun setView(v: View) {
        set { copy(view = v) }
        val st = s
        val idle = st.conn == ConnState.CONNECTED && st.confirm == null && !st.awaitingEnd
        when (v) {
            View.STATUS -> if (st.conn == ConnState.CONNECTED && st.confirm == null) send("status")
            View.TRAINING -> if (idle) send("shots")
            View.HISTORY -> if (idle) { requestLog(); fetchHiddenOnce() }
            View.SETTINGS -> if (idle) { send("level"); send("log info"); if (st.cfg != null) send("get") else { cfgRetries = 0; requestCfg() } }
            View.CONSOLE -> {}
        }
    }

    // ------------------------------------------------------------------ training
    private fun saveTraining() {
        val st = s
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val ses = st.session?.takeIf { it.active }
        store.put(TRAIN_KEY, json.encodeToString(TrainingSave.serializer(), TrainingSave(st.entries, ses)))
    }
    private fun loadTraining() {
        val t = store.get(TRAIN_KEY)?.let { runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(TrainingSave.serializer(), it) }.getOrNull() } ?: return
        set { copy(entries = t.entries, session = t.session?.takeIf { it.active }) }
    }
    @kotlinx.serialization.Serializable
    private class TrainingSave(val entries: List<String> = emptyList(), val session: SessionInfo? = null)

    fun pushEntry(v: String) {
        if (s.entries.size >= MAX_ARROWS || s.awaitingEnd || s.reconnecting) return
        set { copy(entries = entries + v, pendingHits = null, pendingMatch = null, pendingMatchSrc = null) }     // edited by hand: positions no longer match
        saveTraining()
    }
    fun undoEntry() { set { copy(entries = entries.dropLast(1), pendingHits = null, pendingMatch = null, pendingMatchSrc = null) }; saveTraining() }

    /** Scores from a photo: rings as entries plus the positions behind them. */
    fun applyPhotoScores(rings: List<Int>, hits: List<Hit>?, match: ShotMatch.Result? = null, matchSrc: List<String>? = null) {
        if (s.awaitingEnd || s.reconnecting) return
        val ok = hits != null && hits.size <= MAX_ARROWS && match != null && matchSrc != null && match.shotOf.size == hits.size
        set { copy(entries = rings.take(MAX_ARROWS).map { Scoring.entry(it) }, pendingHits = hits?.take(MAX_ARROWS), pendingMatch = if (ok) match else null, pendingMatchSrc = if (ok) matchSrc else null) }
        saveTraining()
    }
    fun setPhotoPrefs(p: PhotoPrefs) {
        store.put(PHOTO_KEY, kotlinx.serialization.json.Json.encodeToString(PhotoPrefs.serializer(), p))
        set { copy(photo = p) }
        if (s.conn == ConnState.CONNECTED) updateSight { it.copy(face = p.face, arrowMm = p.arrowMm) }
    }

    // ------------------------------------------------------------------ sights
    /** Bluetooth address of the current link, set by the transport before the sight says hello. */
    var linkAddress: String? = null

    /** The sight introduced itself: register it, and switch to its own preferences. */
    private fun onSightIdentified(id: String, name: String?) {
        val list = archive.sights().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val now = clock.now()
        val si = if (i >= 0) list[i].copy(name = name ?: list[i].name, address = linkAddress ?: list[i].address, lastSeen = now)
                 else SightInfo(id, name ?: "", color = list.size, address = linkAddress, lastSeen = now)
        if (i >= 0) list[i] = si else list.add(si)
        archive.saveSights(list)
        set { copy(sight = si, sights = list) }
        si.distM?.let { d -> if (d != s.distM) { set { copy(distM = d, distAuto = false) }; store.put(DIST_KEY, d.toString()) } }
        val ph = s.photo
        if ((si.face != null && si.face != ph.face) || (si.arrowMm != null && si.arrowMm != ph.arrowMm))
            setPhotoPrefs(ph.copy(face = si.face ?: ph.face, arrowMm = si.arrowMm ?: ph.arrowMm))
    }
    private fun updateSight(f: (SightInfo) -> SightInfo) {
        val cur = s.sight ?: return
        val list = archive.sights().map { if (it.id == cur.id) f(it) else it }
        archive.saveSights(list)
        set { copy(sight = list.firstOrNull { it.id == cur.id }, sights = list) }
    }
    /** Does this session belong to the connected sight, or at least to no other known sight? Only these are synced. */
    private fun ownedByCurrent(r: ArchiveSession): Boolean {
        val cur = s.sight ?: return true
        if (r.sightId != null) return r.sightId == cur.id
        val e = r.epoch ?: return true
        return s.sights.none { it.id != cur.id && e in it.epochs }
    }
    fun sightNameOf(id: String?): String = id?.let { x -> s.sights.firstOrNull { it.id == x }?.label } ?: ""
    /** Give the connected sight a name (empty removes it); the sight stores and announces it. */
    fun renameSight(name: String) {
        if (s.conn != ConnState.CONNECTED || s.sight == null) { toast(tr("Connect to the sight first."), true); return }
        send("name " + name.trim().ifEmpty { "-" })
    }
    fun forgetSight(id: String) {
        val list = archive.sights().filter { it.id != id }
        archive.saveSights(list)
        set { copy(sights = list, sight = if (sight?.id == id) null else sight) }
        if (s.preferredSightId == id) preferSight(null)
    }
    /** Connect only to this sight from now on (null: whichever sight is awake). */
    fun preferSight(id: String?) {
        val si = id?.let { x -> s.sights.firstOrNull { it.id == x } }
        store.put(PREFER_SIGHT_KEY, si?.id ?: "")
        store.put(PREFER_ADDRESS_KEY, si?.address ?: "")
        set { copy(preferredSightId = si?.id) }
    }

    private fun setAwaiting(on: Boolean) {
        set { copy(awaitingEnd = on) }
        awaitJob?.cancel()
        if (on) awaitJob = scope.launch { delay(6000); set { copy(awaitingEnd = false) }; toast(tr("No answer from the sight. Scores kept, try again."), true) }
    }

    fun sendEnd() {
        val st = s
        if (st.entries.isEmpty() || st.awaitingEnd || st.reconnecting) return
        scope.launch {
            withConnection {
                val tokens = s.entries.map { when (it) { "X" -> "x"; "M" -> "0"; else -> it } }
                lastSentScores = s.entries.toList()
                lastSentHits = s.pendingHits
                lastSentMatch = s.pendingMatch; lastSentMatchSrc = s.pendingMatchSrc
                setAwaiting(true)
                send("score " + tokens.joinToString(" ") + distSuffix())
            }
        }
    }
    private fun distSuffix() = if (s.distM > 0 && !s.distAuto) " @${s.distM}" else ""

    /** Answer to the mismatch question: "yes", "no" or "split". */
    fun answerConfirm(choice: String) {
        set { copy(confirm = null) }
        if (choice != "no") setAwaiting(true)
        send(choice)
    }

    /** Skip the open end (after the UI asked). */
    fun skipEnd() { scope.launch { withConnection { set { copy(entries = emptyList()) }; saveTraining(); send("score skip" + distSuffix()) } } }
    fun endSession() { scope.launch { withConnection { send("shots stop") } } }
    fun counterOn() = send("shots on")
    fun counterOff() = send("shots off")
    fun startSession() = send("shots start")

    private fun recordEnd(m: EndMsg) {
        val key = s.currentSessionKey ?: run { lastSentScores = null; lastSentHits = null; lastSentMatch = null; lastSentMatchSrc = null; return }
        archive.recordEnd(key, m, lastSentScores, lastSentHits, lastSentMatch, lastSentMatchSrc)
        lastSentScores = null; lastSentHits = null; lastSentMatch = null; lastSentMatchSrc = null
        refreshHistory()
    }

    // ---- shot matching ----
    /** Ask the sight for the shots of the open end (with its predictions for the given distance). */
    fun requestShots() { if (s.conn == ConnState.CONNECTED && s.session?.active == true) send("shot list" + (if (s.distM > 0) " ${s.distM}" else "")) }
    fun shotMatching(on: Boolean) { if (s.conn != ConnState.CONNECTED) { toast(tr("Connect to the sight first."), true); return }; send(if (on) "shot on" else "shot off") }
    fun shotReset(setupId: Int) { if (s.conn != ConnState.CONNECTED) { toast(tr("Connect to the sight first."), true); return }; send("shot reset $setupId") }
    /** The aim trace of one shot of the open end (last = of the last closed end). */
    fun requestTrace(i: Int, last: Boolean = false) { if (s.conn == ConnState.CONNECTED) send("shot trace " + (if (last) "last " else "") + i) }
    fun clearTrace() { set { copy(trace = null) } }
    /** The language setting; the app layer loads the pack and sets Lang before calling this. */
    fun setLanguage(code: String) { store.put(LANG_KEY, code); set { copy(language = code) } }
    fun storedLanguage(): String = store.get(LANG_KEY) ?: ""
    fun setLanguages(list: List<Pair<String, String>>) { set { copy(languages = list) } }
    fun setHintPrefs(p: HintPrefs) { store.put(HINTS_KEY, kotlinx.serialization.json.Json.encodeToString(HintPrefs.serializer(), p)); set { copy(hints = p) } }
    /** Hints for one end against this archer's history (same setup); the cant tolerance comes from the sight's settings. */
    fun endHints(e: EndDetail): List<String> {
        if (!s.hints.enabled) return emptyList()
        val all = archive.ends().values.flatten()
        val tol = s.cfg?.firstOrNull { it.k == "level_tol" }?.v ?: 2.0
        return Insights.endHints(e, Insights.baseline(all, e.setup, exclude = all.firstOrNull { it === e } ?: e), tol, s.hints.clickMmAt18.takeIf { it > 0 })
    }
    fun sessionHints(key: String): List<String> = if (!s.hints.enabled) emptyList() else Insights.sessionHints(archive.ends()[key] ?: emptyList())
    /** Mean confidence of the matches over the last ends of a setup, or null without data. */
    fun matchAccuracy(setupId: Int?): Double? = ShotMatch.accuracy(archive.ends().values.flatten(), setupId)

    // ---- distance ----
    fun setDist(d0: Int, fromSight: Boolean = false) {
        val d = max(0, min(150, d0))
        set { copy(distM = d, distAuto = fromSight) }
        if (!fromSight) {
            if (s.conn == ConnState.CONNECTED && s.session?.active == true) send("dist $d") else distPresetAt = clock.now()
        }
        store.put(DIST_KEY, d.toString())
        if (d > 0) store.put(DIST_LAST_KEY, d.toString())
        if (!fromSight) updateSight { it.copy(distM = d) }
    }
    fun distMinus() = setDist(s.distM - 10)
    fun distPlus() = setDist(if (s.distM == 0) (store.get(DIST_LAST_KEY)?.toIntOrNull() ?: 10) else s.distM + 10)

    private fun checkNewSession(m: SessionInfo): Boolean {
        val key = m.key ?: return false
        if (store.get(DIST_SESSION_KEY) == key) return false
        store.put(DIST_SESSION_KEY, key)
        if ((m.dist ?: 0) > 0) { distPresetAt = 0; return false }
        if (distPresetAt != 0L && clock.now() - distPresetAt < DIST_PRESET_MS && s.distM > 0) { distPresetAt = 0; send("dist ${s.distM}"); return true }
        distPresetAt = 0
        setDist(0, true)
        set { copy(distAuto = false) }
        return true
    }
    private fun adoptSightDistance(m: SessionInfo) {
        if (!m.active || m.dist == null) return
        val auto = m.distSrc == "auto"
        if (m.dist != s.distM && (auto || m.dist > 0)) setDist(m.dist, true)
        if (m.dist == s.distM) set { copy(distAuto = auto) }
    }

    // ------------------------------------------------------------------ history / sync
    fun refreshHistory() {
        archive.resolveDates()
        set { copy(sessions = archive.sessions(), ends = archive.ends()) }
    }

    private fun scoringBusy() = s.awaitingEnd || s.confirm != null || s.reconnecting || calBusy || s.session?.pending == true || s.conn != ConnState.CONNECTED
    private fun syncBlocked() = scoringBusy() || copyState != null || pendingDel != null

    private fun requestLog() = send("log since ${archive.logSeq}")

    private fun autoSync() {
        if (!s.autoCopy || s.conn != ConnState.CONNECTED || copyState != null) return
        if (syncBlocked()) { scope.launch { delay(2000); autoSync() }; return }
        if (store.get(ONSIGHT_INIT_KEY) != "1" || store.get(ENDS_BACKFILL_KEY) != "1") { fullScan = true; send("log since 0") } else requestLog()
    }

    private fun handleLiveSlot(sl: SlotMsg) {
        val ls = liveSlot
        val stored = ls == null || ls.stored
        archive.archiveSlot(sl.epoch, sl, false, s.sight?.id)
        archive.markOnSight(setOf("${sl.epoch}-${sl.id}"), stored)
        if (stored) toast(tr("Session saved: {scored} arrows, average {avg}, {x} X", "scored" to sl.scored, "avg" to (fmt(sl.avg, 2)), "x" to sl.x))
        else toast(tr("The sight could not store this session") + (ls?.error?.let { " ($it)" } ?: "") + tr(". It is kept on this phone."), true)
        if (stored && sl.seq != null && sl.seq == archive.logSeq + 1) archive.logSeq = sl.seq
        if (ls != null && !ls.manual) _events.tryEmit(CoreEvent.SessionAutoEnded(sl.ends, sl.scored, sl.avg, sl.x, sl.min))
        liveSlot = null
        refreshHistory()
    }

    private fun onLogEnd(m: Msg) {
        val n = m.int("n")
        val complete = n == null || n == syncGot
        if (!complete && syncRetries < SYNC_MAX_RETRIES && s.conn == ConnState.CONNECTED) {
            syncRetries++
            logConsole("log answer incomplete ($syncGot of $n records), asking again", "err")
            syncEpoch = null; inLogAnswer = false; syncSeen = null; endsSeen = null
            send("log since $syncSince")
            return
        }
        if (!complete) toast(tr("Some lines from the sight were lost. Move closer and sync again."), true)
        syncRetries = 0
        syncEpoch = null; inLogAnswer = false
        syncSeen?.let { archive.markOnSight(it, true) }
        if (fullScan && store.get(ENDS_BACKFILL_KEY) != "1") {
            val store2 = archive.ends()
            val seen = syncSeen ?: emptySet()
            backfill = ArrayList(archive.sessions().filter { r -> seen.contains(r.key) && !store2[r.key].isNullOrEmpty() && endsSeen?.contains(r.key) != true })
            store.put(ENDS_BACKFILL_KEY, "1")
        }
        endsSeen = null
        if (fullScan && complete) {
            val all = archive.sessions().filter { ownedByCurrent(it) }.map { it.key }.toMutableSet()
            syncSeen?.let { all.removeAll(it) }
            archive.markOnSight(all, false)
            store.put(ONSIGHT_INIT_KEY, "1")
        }
        fullScan = false
        syncSeen = null
        if (hiddenFetch) { hiddenFetch = false; store.put(HIDDEN_FETCHED_KEY, "1") }
        copyState?.let { if (it.phase == "keys") { copyOnLogEnd(); refreshHistory(); return } }
        if (syncResync) { syncResync = false; archive.logSeq = 0; fullScan = true; send("log since 0"); return }
        val last = m.long("last")
        if (complete && last != null && last >= archive.logSeq) archive.logSeq = last
        if (syncCount > 0) toast(tr("{syncCount} new {syncCount2} from the sight", "syncCount" to syncCount, "syncCount2" to (if (syncCount == 1) tr("session") else tr("sessions"))))
        else if (!s.autoCopy && complete) toast(tr("Everything is already on this phone"))
        refreshHistory()
        startAutoCopy()
        if (autoCopyState == null) runBackfill()
    }

    fun requestLogNow() { if (s.conn != ConnState.CONNECTED) { toast(tr("Connect to the sight first."), true); return }; requestLog() }

    private fun setSyncLine(t: String) = set { copy(syncLine = t) }

    private fun runBackfill() {
        if (backfill.isEmpty() || autoCopyState != null || copyState != null || endPut != null) return
        if (syncBlocked()) { scope.launch { delay(3000); runBackfill() }; return }
        val r = backfill.removeAt(0)
        setSyncLine("Adding end details to the sight: ${backfill.size + 1} left")
        putEndsThen(r) { if (backfill.isNotEmpty()) runBackfill() else { setSyncLine(""); send("log info") } }
    }

    // ---- copy missing sessions back to the sight, automatically ----
    private fun startAutoCopy() {
        if (!s.autoCopy || autoCopyState != null || copyState != null) return
        val queue = archive.sessions().filter { it.hasSightKey && it.onSight != true && ownedByCurrent(it) }
        if (queue.isEmpty()) return
        autoCopyState = AutoCopy(queue)
        autoCopyNext()
    }
    private fun autoCopyNext() {
        val c = autoCopyState ?: return
        c.wait?.cancel()
        if (s.conn != ConnState.CONNECTED) { stopAutoCopy(); return }
        if (syncBlocked()) { c.wait = scope.launch { delay(3000); autoCopyNext() }; return }
        if (c.i >= c.queue.size) {
            if (c.added > 0) toast(tr("{added} {added2} copied to the sight", "added" to c.added, "added2" to (if (c.added == 1) tr("session") else tr("sessions"))))
            stopAutoCopy(); send("log info"); runBackfill(); return
        }
        val r = c.queue[c.i]
        setSyncLine("Copying to the sight: ${c.i + 1} of ${c.queue.size}")
        send(putLine(r))
        c.timer?.cancel()
        c.timer = scope.launch { delay(8000); stopAutoCopy() }
    }
    private fun putLine(r: ArchiveSession): String {
        val start = r.startedAt?.let { (it / 1000.0).roundToInt() } ?: 0
        val flags = (if (r.mismatch) 2 else 0) or (if (r.manual) 4 else 0)
        return "log put ${r.epoch} ${r.id} $start ${r.ends} ${r.shots} ${r.scored} ${(r.avg * 100).roundToInt()} ${r.x} ${r.min} ${r.invalidEnds} ${r.invalidArrows} $flags"
    }
    private fun autoCopyOnAck(m: Msg): Boolean {
        val c = autoCopyState ?: return false
        val r = c.queue.getOrNull(c.i) ?: return false
        if (m.long("epoch") != r.epoch || m.long("id") != r.id) return false
        c.timer?.cancel()
        val res = m.str("result")
        if (res == "added" || res == "exists") {
            archive.markOnSight(setOf(r.key), true)
            val next = { if (autoCopyState === c) { c.i++; autoCopyNext() } }
            if (res == "added") { c.added++; putEndsThen(r, next) } else next()
        } else stopAutoCopy()
        return true
    }
    private fun stopAutoCopy() { autoCopyState?.let { it.timer?.cancel(); it.wait?.cancel() }; autoCopyState = null; setSyncLine("") }

    // ---- end details to the sight ----
    private fun putEndsThen(r: ArchiveSession, done: () -> Unit) {
        val list = archive.ends()[r.key] ?: emptyList()
        if (list.isEmpty()) { done(); return }
        endPut = EndPut(r, list, done)
        putEndNext()
    }
    private fun putEndNext() {
        val p = endPut ?: return
        p.wait?.cancel()
        if (p.i >= p.list.size) { endPut = null; p.done(); return }
        if (s.conn != ConnState.CONNECTED) { endPut = null; return }
        if (scoringBusy()) { p.wait = scope.launch { delay(3000); putEndNext() }; return }
        val e = p.list[p.i]
        val flags = (if (e.valid) 1 else 0) or (if (e.reason == "skipped") 2 else 0) or (if ((e.dist ?: 0) > 0) (if (e.distAuto) 8 else 4) else 0)
        val hex = (e.scores ?: emptyList()).joinToString("") { when (it) { "X" -> "B"; "10" -> "A"; "M" -> "0"; else -> it } }.ifEmpty { "-" }
        val angC = e.ang?.let { (it * 100).roundToInt() } ?: -32768
        val sdC = e.angSd?.let { (it * 100).roundToInt() } ?: 0
        val cant = if (e.cant != null) "${(e.cant * 100).roundToInt()} ${((e.cantMax ?: 0.0) * 100).roundToInt()} ${e.cantN ?: 0} ${e.canted ?: 0}" else "0 0 0 0"
        send("log putend ${p.r.epoch} ${p.r.id} ${e.n} ${e.arrows} $flags ${e.sum ?: 0} ${e.x ?: 0} ${e.dist ?: 0} $angC $sdC ${e.angN ?: 0} $cant ${e.setup ?: 0} $hex")
        p.timer?.cancel()
        p.timer = scope.launch { delay(8000); val q = endPut; endPut = null; q?.done?.invoke() }
    }
    private fun endPutOnAck() { val p = endPut ?: return; p.timer?.cancel(); p.i++; putEndNext() }

    // ---- copy by hand ("Copy to sight" with a choice) ----
    fun copyToSight() {
        if (copyState != null) return
        scope.launch {
            if (!withConnection {}) return@launch
            copyState = CopyState()
            set { copy(copyProgress = "Checking which sessions the sight has…") }
            send("awake 300")
            send("log since 0")
        }
    }
    private fun copyOnLogEnd() {
        val c = copyState ?: return
        if (c.phase != "keys") return
        c.queue = archive.sessions().filter { it.hasSightKey && !c.keys.contains("${it.epoch}-${it.id}") && ownedByCurrent(it) }
        c.phase = "choose"
        if (c.queue.isEmpty()) { finishCopy("The sight already has every session from this phone.", false); return }
        set { copy(copyProgress = "", copyChoice = c.queue.sortedByDescending { it.sortTime }) }
    }
    /** The user picked which sessions to copy (empty = cancel). */
    fun chooseCopies(keys: Set<String>) {
        val c = copyState
        set { copy(copyChoice = null) }
        if (c == null) return
        if (keys.isEmpty()) { finishCopy("Nothing copied.", false); return }
        c.queue = c.queue.filter { keys.contains(it.key) }
        c.phase = "put"
        copyNext()
    }
    private fun copyNext() {
        val c = copyState ?: return
        if (c.done >= c.queue.size) {
            finishCopy("Copied ${c.added} ${if (c.added == 1) "session" else "sessions"} to the sight" + (if (c.exists > 0) ", ${c.exists} were already there." else "."), false)
            return
        }
        val r = c.queue[c.done]
        set { copy(copyProgress = "Copying to sight: ${c.done + 1} of ${c.queue.size}") }
        send(putLine(r))
        c.timer?.cancel()
        c.timer = scope.launch { delay(8000); finishCopy("The sight stopped answering. Tap Copy to sight to continue.", true) }
    }
    private fun copyOnAck(result: String?, epoch: Long?, id: Long?) {
        val c = copyState ?: return
        if (c.phase != "put") return
        c.timer?.cancel()
        if (result == "added") c.added++ else if (result == "exists") c.exists++
        if (result == "added" || result == "exists") {
            val r = c.queue.getOrNull(c.done)
            if (r != null) archive.markOnSight(setOf(r.key), true)
            val next = { if (copyState === c) { c.done++; copyNext() } }
            if (result == "added" && r != null) putEndsThen(r, next) else next()
            return
        }
        finishCopy("The sight could not store a session. Copying stopped.", true)
    }
    private fun finishCopy(text: String, err: Boolean) {
        copyState?.timer?.cancel()
        if (copyState != null && s.conn == ConnState.CONNECTED) send("awake 0")
        copyState = null
        set { copy(copyProgress = "", copyChoice = null) }
        toast(text, err)
        if (s.conn == ConnState.CONNECTED) send("log info")
        refreshHistory()
    }

    // ---- removing sessions ----
    fun removeFromPhone(r: ArchiveSession) {
        archive.markDeleted(r.key)
        archive.update(r.key) { it.copy(hidden = true) }
        refreshHistory()
        toast(tr("Removed from this phone. The sight keeps its copy."))
    }
    fun removeCompletely(r: ArchiveSession) {
        scope.launch {
            if (!withConnection {}) return@launch
            pendingDel = PendingDel(r, scope.launch { delay(8000); pendingDel = null; toast(tr("The sight did not answer. Nothing was removed."), true) })
            send("log del ${r.epoch} ${r.id}")
        }
    }
    private fun delOnAck(m: Msg) {
        val p = pendingDel ?: return
        if (m.long("epoch") != p.r.epoch || m.long("id") != p.r.id) return
        p.timer.cancel(); pendingDel = null
        val res = m.str("result")
        if (res == "deleted" || res == "notfound") {
            archive.markDeleted(p.r.key); archive.removeSession(p.r.key); refreshHistory()
            toast(if (res == "deleted") tr("Removed from this phone and the sight.") else tr("Removed from this phone. The sight did not have it anymore."))
        } else toast(tr("The sight could not delete the session. Nothing was removed."), true)
    }
    fun unhideSession(r: ArchiveSession) {
        archive.unmarkDeleted(r.key)
        archive.update(r.key) { it.copy(hidden = false) }
        refreshHistory()
        toast(tr("Shown in History again."))
    }
    fun setShowHidden(on: Boolean) {
        store.put(SHOW_HIDDEN_KEY, if (on) "1" else "0")
        set { copy(showHidden = on) }
        if (on) { if (s.conn == ConnState.CONNECTED) fetchHiddenOnce(); toast(tr("Removed sessions are now shown greyed out in History.")) }
    }
    private fun fetchHiddenOnce() {
        if (!s.showHidden || store.get(HIDDEN_FETCHED_KEY) == "1") return
        if (s.conn != ConnState.CONNECTED || archive.deleted().isEmpty()) return
        hiddenFetch = true
        send("log since 0")
    }
    fun setAutoCopy(on: Boolean) {
        store.put(AUTOCOPY_KEY, if (on) "on" else "off")
        set { copy(autoCopy = on) }
        if (on) autoSync() else stopAutoCopy()
    }

    // ---- clear the log on the sight ----
    fun clearSightLog() { scope.launch { if (withConnection {}) send("log clear confirm") } }
    private fun afterClear(count: Int) {
        archive.markOnSight(archive.sessions().filter { ownedByCurrent(it) }.map { it.key }.toSet(), false)
        set { copy(afterClearCount = count) }
    }
    /** Answer to "copy the sessions back?" after clearing. */
    fun afterClearAnswer(copyBack: Boolean) {
        set { copy(afterClearCount = null) }
        store.put(AUTOCOPY_KEY, if (copyBack) "on" else "off")
        set { copy(autoCopy = copyBack) }
        if (copyBack) startAutoCopy() else toast(tr("Automatic sync is off. You can turn it on again in Settings."))
    }

    // ---- export / import ----
    fun exportCsv(): String = Csv.export(archive.sessions(), archive.ends(), { sightNameOf(it) }) { archive.setupNameOf(it) }
    fun exportBackup(): String = BackupFormat.encode(Backup(app = APP_VERSION, exported = java.time.Instant.ofEpochMilli(clock.now()).toString(),
        sessions = archive.sessions(), ends = archive.ends(), deleted = archive.deleted().toList(), sights = archive.sights()))
    /** Sights named in an import that this phone has not met yet get an entry, so their badge shows a name. */
    private fun registerSights(found: Map<String, String>) {
        if (found.isEmpty()) return
        val list = archive.sights().toMutableList()
        var changed = false
        for ((id, name) in found) {
            val i = list.indexOfFirst { it.id == id }
            if (i < 0) { list.add(SightInfo(id, name, color = list.size)); changed = true }
            else if (list[i].name.isEmpty() && name.isNotEmpty()) { list[i] = list[i].copy(name = name); changed = true }
        }
        if (changed) { archive.saveSights(list); set { copy(sights = list) } }
    }
    fun importText(name: String, text: String): ImportResult? {
        val result = try {
            if (name.lowercase().endsWith(".json") || text.trim().startsWith("{")) {
                val b = BackupFormat.decode(text) ?: throw IllegalArgumentException("not a UV-Sight backup")
                registerSights(b.sights.associate { it.id to it.name })
                archive.importSessions(b.sessions, b.ends, "backup")
            } else {
                val rows = Csv.parse(text)
                if (rows.isNotEmpty() && rows[0].containsKey("session") && rows[0].containsKey("end")) {
                    val (ss, ee) = Csv.importEndsCsv(rows, archive.setupNames(), clock.now())
                    registerSights(Csv.sightsIn(rows))
                    archive.importSessions(ss, ee, "CSV")
                } else archive.importSessions(Csv.importSessionsCsv(rows, clock.now()), null, "CSV")
            }
        } catch (e: Exception) { toast(tr("Import failed: {message}", "message" to e.message), true); return null }
        refreshHistory()
        val parts = ArrayList<String>(); parts.add("${result.added} new")
        if (result.filled > 0) parts.add("${result.filled} dates added")
        if (result.skipped > 0) parts.add("${result.skipped} skipped (removed before)")
        toast(tr("Imported from {what}: {parts}", "what" to result.what, "parts" to (parts.joinToString(", "))) + if (result.added > 0 && s.autoCopy) tr(". They are copied to the sight when it is connected.") else "")
        if (result.added > 0) startAutoCopy()
        return result
    }

    // ------------------------------------------------------------------ settings
    private fun requestCfg() {
        cfgRetryJob?.cancel()
        if (cfgRetries >= CFG_MAX_RETRIES) { set { copy(cfgGaveUp = true) }; return }
        if (s.conn != ConnState.CONNECTED || s.confirm != null || s.awaitingEnd) return
        cfgRetries++
        send("get")
        cfgRetryJob = scope.launch { delay(3000); if (s.cfg == null) requestCfg() }
    }
    fun retryCfg() { cfgRetries = 0; set { copy(cfgGaveUp = false) }; requestCfg() }

    fun sendSet(k: String, v: Double) {
        if (s.confirm != null || s.awaitingEnd) { toast(tr("Finish the open end first."), true); return }
        send("set $k ${fmtSetting(k, v)}")
    }
    private fun fmtSetting(k: String, v: Double): String {
        val dec = s.cfg?.firstOrNull { it.k == k }?.dec ?: 0
        return if (dec == 0) v.roundToInt().toString() else fmt(v, dec)
    }
    fun saveSettings() = send("save")
    fun restoreDefaults() { expectDefaults = true; send("defaults") }

    /** Change a cant / aiming-range setting: show it at once, then ask the sight again. */
    fun setLevel(cmd: String, patch: (LevelInfo) -> LevelInfo) {
        set {
            var lv = patch(level ?: LevelInfo("off", "normal", false, "auto", true, true))
            if (lv.mode == "on" && lv.angle == "on" && cmd.startsWith("level ")) lv = lv.copy(angle = "off")
            if (lv.angle == "on" && lv.mode == "on" && cmd.startsWith("angle ")) lv = lv.copy(mode = "off")
            copy(level = lv)
        }
        send(cmd)
        levelResyncJob?.cancel()
        levelResyncJob = scope.launch {
            delay(800)
            while (s.confirm != null || s.awaitingEnd || s.session?.pending == true) delay(3000)
            if (s.conn == ConnState.CONNECTED) send("level")
        }
    }
    fun setCantMode(m: String) = setLevel("level $m") { it.copy(mode = m) }
    fun setAngleMode(m: String) = setLevel("angle $m") { it.copy(angle = m) }
    fun setCantSignal(on: Boolean) = setLevel(if (on) "level signal on" else "level signal off") { it.copy(cantSignal = on) }
    fun setStyle(st: String) = setLevel("level style $st") { it.copy(style = st) }
    fun setRangeSignal(on: Boolean) = setLevel(if (on) "range signal on" else "range signal off") { it.copy(rangeSignal = on) }
    fun setLedMode(m: String) { set { copy(status = status?.copy(mode = m)) }; send("mode $m") }
    fun setCheckDist(v0: Int) { val v = max(0, min(150, v0)); set { copy(range = range?.copy(dist = v)) }; send("dist $v") }
    fun shotsSwitch(on: Boolean) {
        if (!on && s.session?.active == true) { toast(tr("End the running session first."), true); return }
        send(if (on) "shots on" else "shots off")
    }

    // ---- calibration wizard ----
    fun calStart() = set { copy(calStep = 1, calError = "") }
    fun calCancel() = set { copy(calStep = 0, calError = "", calBusy = false) }.also { calBusy = false }
    fun calMeasure() {
        if (s.confirm != null || s.awaitingEnd) { toast(tr("Finish the open end first."), true); return }
        calBusy = true
        set { copy(calBusy = true, calError = "") }
        send(if (s.calStep == 1) "level cal" else "level cal2")
    }
    private fun onCal(m: CalMsg) {
        if (m.state == "countdown") { calBusy = true; set { copy(calBusy = true) }; return }
        calBusy = false
        if (m.state == "error") { set { copy(calBusy = false, calError = m.text) }; return }
        set { copy(calBusy = false, calError = "", calStep = when (m.step) { 1 -> 2; 2 -> 3; else -> calStep }) }
    }

    // ---- setups ----
    fun setupUse(id: Int) = send("setup use $id")
    fun setupNew(name: String) { send("setup new $name"); pendingUseNew = true }
    fun setupRename(id: Int, name: String) = send("setup name $id $name")
    fun setupDelete(id: Int) = send("setup del $id")

    fun dfu() = send("dfu")

    fun setNotifPrefs(p: NotifPrefs) {
        store.put(NOTIF_KEY, kotlinx.serialization.json.Json.encodeToString(NotifPrefs.serializer(), p))
        set { copy(notif = p) }
    }
    /** Photo scoring only: the app never touches Bluetooth; Status, History and Console are hidden. */
    fun setPhotoOnly(on: Boolean) {
        store.put(MODE_KEY, if (on) "photo" else "sight")
        if (on) {
            stopAutoReconnect()
            if (s.conn != ConnState.OFF) transport.disconnect()
            set { copy(photoOnly = true, view = if (view == View.TRAINING || view == View.SETTINGS) view else View.TRAINING) }
        } else set { copy(photoOnly = false) }
    }
    /** Photo-only mode: the scores were looked at, clear the entry row. */
    fun clearEntries() { set { copy(entries = emptyList(), pendingHits = null, pendingMatch = null, pendingMatchSrc = null) }; saveTraining() }
    fun setConsoleEnabled(on: Boolean) { store.put(CONSOLE_KEY, if (on) "1" else "0"); set { copy(consoleEnabled = on, view = if (!on && view == View.CONSOLE) View.STATUS else view) } }
    fun sendRaw(cmd: String) { if (s.conn != ConnState.CONNECTED) { toast(tr("Not connected."), true); return }; send(cmd) }
}

fun fmt(v: Double, dec: Int): String = String.format(java.util.Locale.ROOT, "%.${dec}f", v)

/** "about 3 h", "about 5 days" ... from hours. */
fun fmtDuration(hours: Double): String = when {
    !hours.isFinite() || hours <= 0 -> "–"
    hours < 1 -> "about ${max(1, (hours * 60).roundToInt())} min"
    hours < 48 -> "about ${hours.roundToInt()} h"
    hours < 24 * 21 -> "about ${(hours / 24).roundToInt()} days"
    else -> "about ${(hours / 24 / 7).roundToInt()} weeks"
}

fun points(v: String): Int = when (v) { "X" -> 10; "M" -> 0; else -> v.toIntOrNull() ?: 0 }
