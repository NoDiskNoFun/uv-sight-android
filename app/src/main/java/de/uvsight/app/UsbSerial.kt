package de.uvsight.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import de.uvsight.core.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * A tiny USB-serial (CDC ACM) link to the sight, enough to send one console command and read the reply.
 *
 * The sight running its firmware shows up over USB as a CDC ACM serial port. The firmware's console accepts
 * commands over it exactly as over a terminal, and some commands (removing the lock with "group clear", a
 * factory reset) are allowed over USB even when the sight is locked to another phone. This lets the app offer
 * those straight from the phone over a USB-C cable, instead of sending the archer to a computer.
 *
 * CDC ACM is handled by hand (open the data interface, raise DTR, set 115200 8N1, write, read): the one device
 * is the XIAO nRF52840, so no multi-chip serial library is needed.
 */
class UsbSerialLink(private val context: Context) {
    companion object {
        const val ACTION_PERMISSION = "de.uvsight.app.USB_SERIAL_PERMISSION"
        private const val CDC_DATA = 0x0A          // USB class: CDC data (the bulk endpoints)
        private const val CDC_COMM = 0x02          // USB class: CDC communication (the control interface)
        private const val SET_LINE_CODING = 0x20
        private const val SET_CONTROL_LINE_STATE = 0x22
        private const val DTR_RTS = 0x03
    }

    private val usb = context.getSystemService(UsbManager::class.java)

    private fun devices(): Collection<UsbDevice> = runCatching { usb.deviceList.values }.getOrDefault(emptyList())
    private fun dataInterface(dev: UsbDevice): UsbInterface? = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { it.interfaceClass == CDC_DATA }
    private fun commInterface(dev: UsbDevice): UsbInterface? = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { it.interfaceClass == CDC_COMM }

    /** A sight plugged in and running its firmware (a CDC ACM serial device), or null. */
    fun sightSerial(): UsbDevice? = devices().firstOrNull { dataInterface(it) != null && commInterface(it) != null }

    /** Whether any USB device is plugged in at all (to tell "no cable" from "cable but no serial sight"). */
    fun anyUsbDevice(): Boolean = devices().isNotEmpty()

    /**
     * Send one console line to the sight over USB and return what it answers (up to ~1.5 s of output), or throw.
     * Needs the USB permission; asks for it when missing.
     */
    suspend fun command(dev: UsbDevice, line: String): String {
        if (!usb.hasPermission(dev)) {
            if (!requestPermission(dev)) throw java.io.IOException(tr("Access to the USB device was not allowed."))
        }
        return withContext(Dispatchers.IO) {
            val data = dataInterface(dev) ?: throw java.io.IOException(tr("This USB device is not a sight."))
            val comm = commInterface(dev)
            var inEp: UsbEndpoint? = null; var outEp: UsbEndpoint? = null
            for (i in 0 until data.endpointCount) { val e = data.getEndpoint(i); if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) { if (e.direction == UsbConstants.USB_DIR_OUT) outEp = e else inEp = e } }
            if (inEp == null || outEp == null) throw java.io.IOException(tr("This USB device is not a sight."))
            val conn = usb.openDevice(dev) ?: throw java.io.IOException(tr("The USB device could not be opened."))
            try {
                comm?.let { conn.claimInterface(it, true) }
                conn.claimInterface(data, true)
                // 115200 8N1 and DTR/RTS up, so the firmware sees the port as open
                val coding = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08)   // 115200 = 0x0001C200, 1 stop, no parity, 8 data
                val ifaceNum = (comm ?: data).id
                conn.controlTransfer(0x21, SET_LINE_CODING, 0, ifaceNum, coding, coding.size, 500)
                conn.controlTransfer(0x21, SET_CONTROL_LINE_STATE, DTR_RTS, ifaceNum, null, 0, 500)
                val bytes = (line + "\r\n").toByteArray(Charsets.US_ASCII)
                val wrote = conn.bulkTransfer(outEp, bytes, bytes.size, 2000)
                if (wrote < 0) throw java.io.IOException(tr("The command could not be sent to the sight."))
                // collect the reply for about 1.5 s
                val sb = StringBuilder(); val buf = ByteArray(256); val until = System.currentTimeMillis() + 1500
                while (System.currentTimeMillis() < until) {
                    val n = conn.bulkTransfer(inEp, buf, buf.size, 300)
                    if (n > 0) sb.append(String(buf, 0, n, Charsets.US_ASCII))
                }
                sb.toString().trim()
            } finally { runCatching { conn.close() } }
        }
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
