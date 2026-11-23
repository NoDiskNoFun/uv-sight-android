package de.uvsight.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import de.uvsight.core.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.UsbFileOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/** Where the firmware update stands: the step (1..6), what is happening in it, progress 0..1 (or -1), an error, or done. */
data class FlashState(val step: Int = 1, val detail: String = "", val progress: Float = -1f, val error: String? = null, val done: Boolean = false, val hint: String? = null)

/**
 * Writes a UF2 firmware file onto the sight over USB, with the phone as USB host. The sight in update mode
 * (the UF2 bootloader of the XIAO nRF52840) shows up as a small FAT drive with an INFO_UF2.TXT; the
 * bootloader flashes every UF2 block as it is written and restarts when the last one has arrived, so the
 * drive vanishes under the last write: that IOException is the normal end. libaums talks to the drive
 * directly, no file manager, no folder picker.
 */
class FirmwareFlasher(private val context: Context) {
    companion object {
        const val STEPS = 6
        const val ACTION_PERMISSION = "de.uvsight.app.USB_PERMISSION"
        const val VENDOR_SEEED = 0x2886
        const val UF2_INFO = "INFO_UF2.TXT"

        fun stepTitle(n: Int): String = when (n) {
            1 -> tr("Download the firmware")
            2 -> tr("Connect the sight to this phone")
            3 -> tr("Switch the sight to update mode")
            4 -> tr("Allow access to the USB drive")
            5 -> tr("Copy the firmware")
            else -> tr("The sight restarts")
        }
    }

    private val usb = context.getSystemService(UsbManager::class.java)
    private val _state = MutableStateFlow(FlashState())
    val state: StateFlow<FlashState> = _state
    /** The user confirmed a step the app cannot see by itself (the cable is plugged in). */
    private var confirmed = MutableStateFlow(0)
    fun confirm() { confirmed.value++ }

    private fun set(step: Int, detail: String, progress: Float = -1f, hint: String? = null) { _state.value = FlashState(step, detail, progress, hint = hint) }
    private fun fail(step: Int, msg: String) { _state.value = FlashState(step, "", error = msg) }

    /** Any USB device the phone sees right now (the sight in either mode; it is a USB device in both). */
    fun usbDevices(): Collection<UsbDevice> = runCatching { usb.deviceList.values }.getOrDefault(emptyList())
    private fun sightDevice(): UsbDevice? = usbDevices().firstOrNull { it.vendorId == VENDOR_SEEED }

    /** The drive of a sight in update mode, when there is one. */
    private fun uf2Drive(): UsbMassStorageDevice? =
        runCatching { UsbMassStorageDevice.getMassStorageDevices(context).firstOrNull { it.usbDevice.vendorId == VENDOR_SEEED } }.getOrNull()

    /**
     * The whole guided update. [download] fetches the file (step 1); [startDfu] tells the sight over Bluetooth
     * to reboot into update mode (step 3; null when no sight is connected, then the user presses the button);
     * [reconnected] returns the firmware the sight reports once it is back (step 6), null while it is not.
     */
    suspend fun run(version: String, download: suspend () -> File, startDfu: (() -> Unit)?, reconnected: () -> String?) {
        _state.value = FlashState()
        val file = try {
            set(1, tr("Downloading firmware {version}", "version" to version), 0f)
            download()
        } catch (e: Exception) { fail(1, e.message ?: tr("The download failed.")); return }

        // 2: the cable. A sight in normal mode is a USB device too (its serial port), so the phone can often see it.
        val waitedFor = confirmed.value
        set(2, tr("Plug a USB-C cable into the sight and into this phone. The phone powers the sight and works as the computer."), hint = tr("Tap Next when the cable is in."))
        withTimeoutOrNull(600_000) { while (sightDevice() == null && uf2Drive() == null && confirmed.value == waitedFor) delay(500) }
        if (sightDevice() == null && uf2Drive() == null && confirmed.value == waitedFor) { fail(2, tr("No cable was connected.")); return }

        // 3: update mode
        var drive = uf2Drive()
        if (drive == null) {
            if (startDfu != null) {
                set(3, tr("The app tells the sight to restart in update mode. The light goes off, the Bluetooth connection drops; that is expected."))
                startDfu()
            } else {
                set(3, tr("No Bluetooth connection to the sight: press the small reset button on the sight twice quickly. It then restarts in update mode."))
            }
            drive = withTimeoutOrNull(if (startDfu != null) 45_000 else 600_000) { var d = uf2Drive(); while (d == null) { delay(700); d = uf2Drive() }; d }
            if (drive == null) {
                fail(3, if (startDfu != null) tr("The sight did not switch to update mode. Check that the cable carries data (some only charge) and try again. Without the app: press the reset button on the sight twice quickly while the cable is in.")
                    else tr("The sight did not show up as a drive. Check the cable (some only charge) and try again."))
                return
            }
        }

        val dev: UsbMassStorageDevice = drive
        // 4: permission for the drive
        if (!usb.hasPermission(dev.usbDevice)) {
            set(4, tr("Android asks whether Nocked may use the USB drive of the sight. Tap Allow (OK)."))
            val ok = requestPermission(dev.usbDevice)
            if (!ok) { fail(4, tr("Without access to the USB drive the firmware cannot be copied. Start the update again and allow it.")); return }
        }

        // 5: write
        set(5, tr("Copying the firmware to the sight. Do not unplug the cable."), 0f)
        val total = file.length()
        val written = withContext(Dispatchers.IO) {
            var done = 0L
            try {
                dev.init()
                val fs = dev.partitions.firstOrNull()?.fileSystem ?: throw IOException(tr("The drive of the sight has no file system."))
                val root = fs.rootDirectory
                if (root.search(UF2_INFO) == null) throw IOException(tr("This USB drive is not a sight in update mode."))
                root.search("anchored.uf2")?.delete()
                val target = root.createFile("anchored.uf2")
                UsbFileOutputStream(target).use { out ->
                    file.inputStream().use { i ->
                        val buf = ByteArray(32768)  // sector multiples: the bootloader flashes each 512-byte block as it comes
                        while (true) {
                            val n = i.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            _state.value = _state.value.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            } catch (e: Exception) {
                // The bootloader restarts as soon as the last block is in, so the drive is gone before the file is closed: fine.
                if (done < total) throw e
            } finally { runCatching { dev.close() } }
            done
        }
        if (written < total) { fail(5, tr("The copy stopped early.")); return }

        // 6: back with the new firmware
        set(6, tr("The sight restarts with the new firmware and connects again. The cable can come out now."))
        val fw = withTimeoutOrNull(90_000) { var v = reconnected(); while (v == null) { delay(1000); v = reconnected() }; v }
        _state.value = FlashState(6, when {
            fw == null -> tr("The firmware was copied. The sight has not connected again yet; it will when it is in reach (or tap Connect on the Status tab).")
            fw == version -> tr("Done. The sight reports firmware {version}.", "version" to fw)
            else -> tr("The sight is back but reports firmware {version}. The update did not take; try again.", "version" to fw)
        }, done = true)
    }

    private suspend fun requestPermission(dev: UsbDevice): Boolean = suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching { context.unregisterReceiver(this) }
                if (cont.isActive) cont.resume(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        usb.requestPermission(dev, pi)
        cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
    }
}
