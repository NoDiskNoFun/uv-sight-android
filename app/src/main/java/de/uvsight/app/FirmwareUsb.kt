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
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import me.jahnen.libaums.core.driver.scsi.ScsiBlockDevice
import me.jahnen.libaums.core.usb.UsbCommunicationFactory
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/** Where the firmware update stands: the step (1..6), what is happening in it, progress 0..1 (or -1), an error, or done. */
data class FlashState(val step: Int = 1, val detail: String = "", val progress: Float = -1f, val error: String? = null, val done: Boolean = false, val hint: String? = null)

/**
 * Writes a UF2 firmware file onto the sight over USB, with the phone as USB host. The sight in update mode
 * (the UF2 bootloader of the XIAO nRF52840) shows up as a small FAT16 drive. The bootloader does not keep a
 * real file system: it looks at every 512-byte block the host writes, flashes the ones that carry the UF2
 * magic whatever their position on the drive, and restarts once the last block of the file has arrived. So
 * the app does not go through the file system at all (libaums could not mount FAT16 anyway): it talks SCSI
 * to the drive through libaums' block device and writes the UF2 blocks raw, one after the other. No file
 * manager, no folder picker.
 */
class FirmwareFlasher(private val context: Context) {
    companion object {
        const val STEPS = 6
        const val ACTION_PERMISSION = "de.uvsight.app.USB_PERMISSION"
        const val VENDOR_SEEED = 0x2886

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

    /** The sight in update mode: its USB device shows a mass storage interface (SCSI, bulk only). */
    private fun uf2Drive(): UsbDevice? = usbDevices().firstOrNull { it.vendorId == VENDOR_SEEED && storageInterface(it) != null }

    private fun storageInterface(dev: UsbDevice): UsbInterface? = (0 until dev.interfaceCount).map { dev.getInterface(it) }
        .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && it.interfaceSubclass == 6 && it.interfaceProtocol == 80 }

    /**
     * The whole guided update. [download] fetches the file (step 1); [connected] says whether the sight is on
     * the Bluetooth link; [startDfu] tells it over that link to reboot into update mode (step 3), the only way
     * in, no button on the sight; [reconnected] returns the firmware the sight reports once it is back (step 6),
     * null while it is not.
     */
    suspend fun run(version: String, download: suspend () -> File, connected: () -> Boolean, startDfu: () -> Unit, reconnected: () -> String?) {
        _state.value = FlashState()
        try { guided(version, download, connected, startDfu, reconnected) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { fail(_state.value.step, e.message ?: e.javaClass.simpleName) }
    }

    private suspend fun guided(version: String, download: suspend () -> File, connected: () -> Boolean, startDfu: () -> Unit, reconnected: () -> String?) {
        if (!connected()) { fail(1, tr("The sight is not connected over Bluetooth. Connect it on the Status tab, then start the update again.")); return }
        val file = try {
            set(1, tr("Downloading firmware {version}", "version" to version), 0f)
            download()
        } catch (e: Exception) { fail(1, e.message ?: tr("The download failed.")); return }

        // 2: the cable. A sight in normal mode is a USB device too (its serial port), so the phone can often see it.
        val waitedFor = confirmed.value
        set(2, tr("Plug a USB-C cable into the sight and into this phone. The phone powers the sight and works as the computer."), hint = tr("Tap Next when the cable is in."))
        withTimeoutOrNull(600_000) { while (sightDevice() == null && uf2Drive() == null && confirmed.value == waitedFor) delay(500) }
        if (sightDevice() == null && uf2Drive() == null && confirmed.value == waitedFor) { fail(2, tr("No cable was connected.")); return }

        // 3: update mode, always over the Bluetooth link (the sight refuses without USB power, so the cable is checked twice)
        var drive = uf2Drive()
        if (drive == null) {
            if (!connected()) { fail(3, tr("The Bluetooth connection to the sight is gone. Connect it again on the Status tab and start the update once more.")); return }
            set(3, tr("The app tells the sight to restart in update mode. The light goes off, the Bluetooth connection drops; that is expected."))
            startDfu()
            drive = withTimeoutOrNull(45_000) { var d = uf2Drive(); while (d == null) { delay(700); d = uf2Drive() }; d }
            if (drive == null) {
                fail(3, tr("The sight did not switch to update mode. Check that the cable sits firmly in the sight and in the phone and carries data (some cables only charge), then try again."))
                return
            }
        }

        val dev: UsbDevice = drive
        // 4: permission for the drive
        if (!usb.hasPermission(dev)) {
            set(4, tr("Android asks whether Nocked may use the USB drive of the sight. Tap Allow (OK)."))
            val ok = requestPermission(dev)
            if (!ok) { fail(4, tr("Without access to the USB drive the firmware cannot be copied. Start the update again and allow it.")); return }
        }

        // 5: write
        set(5, tr("Copying the firmware to the sight. Do not unplug the cable."), 0f)
        val total = file.length()
        val written = withContext(Dispatchers.IO) { writeRaw(dev, file) { done -> _state.value = _state.value.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f)) } }
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

    /**
     * Write the UF2 file block by block onto the drive, raw. Returns the bytes written; the bootloader restarts
     * after the last block, so a transfer error on the very last chunk still counts as complete.
     */
    private fun writeRaw(dev: UsbDevice, file: File, progress: (Long) -> Unit): Long {
        val iface = storageInterface(dev) ?: throw IOException(tr("This USB drive is not a sight in update mode."))
        var inEp: UsbEndpoint? = null; var outEp: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) { val e = iface.getEndpoint(i); if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) { if (e.direction == UsbConstants.USB_DIR_OUT) outEp = e else inEp = e } }
        if (inEp == null || outEp == null) throw IOException(tr("This USB drive is not a sight in update mode."))
        val bytes = file.readBytes()
        if (bytes.size < 512 || bytes.size % 512 != 0 || !isUf2(bytes, 0)) throw IOException(tr("The downloaded file is damaged (checksum). Try again."))
        val comm = UsbCommunicationFactory.createUsbCommunication(usb, dev, iface, outEp, inEp)
        var done = 0L
        try {
            val disk = ScsiBlockDevice(comm, 0)
            disk.init()
            if (disk.blockSize != 512) throw IOException(tr("This USB drive is not a sight in update mode."))
            // the boot sector of the bootloader's drive: a FAT volume, labelled after the board
            val boot = ByteBuffer.allocate(512); disk.read(0, boot)
            val fs = String(boot.array(), 54, 8, Charsets.US_ASCII).trim()
            if (!fs.startsWith("FAT")) throw IOException(tr("This USB drive is not a sight in update mode."))
            // past the FAT and root directory of the small drive; the bootloader takes the blocks from anywhere
            var lba = 64L
            val chunk = 16 * 512
            while (done < bytes.size) {
                val n = minOf(chunk.toLong(), bytes.size - done).toInt()
                if (lba + n / 512 >= disk.blocks) lba = 64L
                try { disk.write(lba, ByteBuffer.wrap(bytes, done.toInt(), n)) }
                catch (e: Exception) { if (done + n >= bytes.size) { done += n; break } else throw e }
                done += n; lba += n / 512
                progress(done)
            }
        } finally { runCatching { comm.close() } }
        return done
    }

    private fun isUf2(b: ByteArray, off: Int) = b[off] == 'U'.code.toByte() && b[off + 1] == 'F'.code.toByte() && b[off + 2] == '2'.code.toByte() && b[off + 3] == 0x0A.toByte()

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
