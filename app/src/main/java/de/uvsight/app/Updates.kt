package de.uvsight.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import de.uvsight.core.KeyValueStore
import de.uvsight.core.SightController
import de.uvsight.core.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** What the newest app release says about itself (version.json next to nocked.apk). */
@Serializable
data class AppRelease(val build: Int, val versionName: String = "", val file: String = "nocked.apk", val sha256: String = "", val minProto: Int? = null, val commit: String = "", val built: String = "")

/** What the newest firmware release says about itself (version.json next to anchored.uf2). */
@Serializable
data class FirmwareRelease(val firmware: String, val proto: Int = 0, val file: String = "anchored.uf2", val sha256: String = "", val commit: String = "", val built: String = "")

/** The last check: when, what it found, or why it failed. */
data class UpdateStatus(val checkedAt: Long = 0, val app: AppRelease? = null, val firmware: FirmwareRelease? = null, val error: String? = null)

/** One download in progress: what, and how far (bytes of total; total -1 while unknown). */
data class Downloading(val what: String, val done: Long, val total: Long)

/**
 * Updates of the app and of the sight's firmware, both published as GitHub releases by the project's
 * own CI. The app reads the two version.json files (no token, plain HTTPS), once a day by itself and on
 * demand, compares them with its own build number and the connected sight's firmware, and says so on the
 * settings card and, for a new version, with a notification. Installing the app goes through Android's
 * package installer; the firmware goes over USB, see FirmwareFlasher.
 */
class UpdateManager(private val context: Context, private val controller: SightController, private val store: KeyValueStore, private val scope: CoroutineScope) {
    companion object {
        const val APP_RELEASE = "https://github.com/NoDiskNoFun/uv-sight-android/releases/latest/download/"
        const val FW_RELEASE = "https://github.com/NoDiskNoFun/uv-sight/releases/latest/download/"
        const val AUTO_KEY = "uvsight.updates.auto"          // "off" switches the daily check off
        const val LAST_KEY = "uvsight.updates.last"
        const val NOTIFIED_KEY = "uvsight.updates.notified"  // "<build>/<firmware>" already announced
        const val CHANNEL = "updates"
        const val ID_UPDATE = 20
        const val DAY_MS = 24 * 3600_000L
        val json = Json { ignoreUnknownKeys = true }

        /** "7.10" is newer than "7.9": compare number by number. */
        fun newerVersion(candidate: String, current: String?): Boolean {
            if (current.isNullOrBlank()) return false
            val a = candidate.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val b = current.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
            return false
        }
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { i -> val buf = ByteArray(65536); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private val _status = MutableStateFlow(UpdateStatus(checkedAt = store.get(LAST_KEY)?.toLongOrNull() ?: 0))
    val status: StateFlow<UpdateStatus> = _status
    private val _busy = MutableStateFlow<Downloading?>(null)
    val busy: StateFlow<Downloading?> = _busy
    val auto: Boolean get() = store.get(AUTO_KEY) != "off"
    fun setAuto(on: Boolean) { store.put(AUTO_KEY, if (on) "on" else "off"); if (on) daily() }

    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, tr("Updates"), NotificationManager.IMPORTANCE_DEFAULT).apply { description = tr("A new version of the app or of the sight's firmware") })
        daily()
    }

    /** The daily check, when it is due and wanted; quiet about failures (no network, say). */
    fun daily() {
        if (!auto) return
        val last = store.get(LAST_KEY)?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() - last < DAY_MS) return
        scope.launch { check(quiet = true) }
    }

    /** Fetch both version.json files; returns the status (also kept in [status]). */
    suspend fun check(quiet: Boolean = false): UpdateStatus {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val app = json.decodeFromString(AppRelease.serializer(), fetchText(APP_RELEASE + "version.json"))
                val fw = runCatching { json.decodeFromString(FirmwareRelease.serializer(), fetchText(FW_RELEASE + "version.json")) }.getOrNull()
                UpdateStatus(System.currentTimeMillis(), app, fw)
            }.getOrElse { e -> UpdateStatus(System.currentTimeMillis(), _status.value.app, _status.value.firmware, explain(e)) }
        }
        if (result.error == null) store.put(LAST_KEY, result.checkedAt.toString())
        _status.value = result
        if (result.error == null) announce(result, quiet)
        return result
    }

    val appUpdate: AppRelease? get() = _status.value.app?.takeIf { it.build > BuildConfig.VERSION_CODE }
    /** The firmware update for the connected sight, or null (no sight, or it is up to date). */
    val firmwareUpdate: FirmwareRelease? get() = _status.value.firmware?.takeIf { newerVersion(it.firmware, controller.state.value.hello?.fw) }

    private fun announce(st: UpdateStatus, quiet: Boolean) {
        val app = appUpdate; val fw = firmwareUpdate
        if (app == null && fw == null) return
        val key = "${app?.build ?: 0}/${fw?.firmware ?: ""}"
        if (!quiet || store.get(NOTIFIED_KEY) == key) return
        store.put(NOTIFIED_KEY, key)
        val text = listOfNotNull(app?.let { tr("Nocked {version} is ready to install.", "version" to it.versionName.ifEmpty { "build ${it.build}" }) },
            fw?.let { tr("Firmware {version} is ready for the sight.", "version" to it.firmware) }).joinToString(" ")
        val open = Intent(context, MainActivity::class.java).apply { putExtra("view", "settings"); flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pi = PendingIntent.getActivity(context, ID_UPDATE, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notif).setContentTitle(tr("Update available")).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(pi).setCategory(NotificationCompat.CATEGORY_STATUS).build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ID_UPDATE, n) }
    }

    /** A network error in words an archer can act on; a name lookup that fails while the browser works is the phone blocking this app. */
    private fun explain(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> tr("The phone does not let the app reach the internet (github.com could not be looked up). Restart the phone once after this update; if that does not help, allow the app to use Wi-Fi and mobile data in the phone's settings and switch off the data saver for it.")
        is java.net.SocketTimeoutException -> tr("GitHub did not answer in time. Try again later.")
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun fetchText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Nocked/${BuildConfig.VERSION_NAME}")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Download a release file to the app's cache, checking its SHA-256 when the release names one. */
    suspend fun download(what: String, url: String, name: String, sha256: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, name)
        try {
            _busy.value = Downloading(what, 0, -1)
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Nocked/${BuildConfig.VERSION_NAME}")
            try {
                if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                c.inputStream.use { i -> dest.outputStream().use { o ->
                    val buf = ByteArray(65536); var done = 0L
                    while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; _busy.value = Downloading(what, done, total) }
                } }
            } finally { c.disconnect() }
            if (sha256.isNotEmpty() && !sha256(dest).equals(sha256, ignoreCase = true)) { dest.delete(); throw java.io.IOException(tr("The downloaded file is damaged (checksum). Try again.")) }
            dest
        } finally { _busy.value = null }
    }

    /** Download the newest app and hand it to Android's installer. Returns false when the phone must first allow Nocked to install apps (the settings page was opened). */
    suspend fun installApp(rel: AppRelease): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return false
        }
        val apk = download(tr("app"), APP_RELEASE + rel.file, "nocked.apk", rel.sha256)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }

    /** Download the newest firmware file, checked. */
    suspend fun downloadFirmware(rel: FirmwareRelease): File = download(tr("firmware"), FW_RELEASE + rel.file, "anchored.uf2", rel.sha256)
}
