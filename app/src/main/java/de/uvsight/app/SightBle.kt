package de.uvsight.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import de.uvsight.core.DEVICE_NAME_PREFIX
import de.uvsight.core.NUS_RX
import de.uvsight.core.NUS_SERVICE
import de.uvsight.core.NUS_TX
import de.uvsight.core.SightController
import de.uvsight.core.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.UUID

/**
 * Bluetooth LE link to the sight over the Nordic UART Service. Scans for the sight
 * (remembered address first), connects, negotiates the MTU, turns notifications on
 * and hands complete lines to the controller. All callbacks are moved to [scope]'s
 * dispatcher (the main thread).
 */
@SuppressLint("MissingPermission")
class SightBle(private val context: Context, private val scope: CoroutineScope, private val prefs: PrefsStore) : Transport {
    companion object {
        const val ADDRESS_KEY = "uvsight.ble.address"
        val SERVICE: UUID = UUID.fromString(NUS_SERVICE)
        val RX: UUID = UUID.fromString(NUS_RX)
        val TX: UUID = UUID.fromString(NUS_TX)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val MTU = 247

        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        fun hasPermissions(context: Context) = requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    var controller: SightController? = null
    var onProblem: ((String) -> Unit)? = null

    private val adapter: BluetoothAdapter? get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var ready = false
    private var mtu = 23
    private var readyWait: CompletableDeferred<Boolean>? = null
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false
    private val lineBuf = ByteArrayOutputStream()

    override val connected: Boolean get() = ready

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    // ------------------------------------------------------------------ connecting
    override suspend fun ensureConnected(timeoutMs: Long): Boolean {
        if (ready) return true
        if (!hasPermissions(context)) { onProblem?.invoke("Bluetooth permission needed. Allow it and try again."); return false }
        val ad = adapter ?: run { onProblem?.invoke("This phone has no Bluetooth."); return false }
        if (!ad.isEnabled) { onProblem?.invoke("Turn Bluetooth on first."); return false }
        controller?.onLinkConnecting()
        val device = withTimeoutOrNull(timeoutMs) { scanForSight() } ?: run { cleanup(); return false }
        val wait = CompletableDeferred<Boolean>()
        readyWait = wait
        val ok = withTimeoutOrNull(timeoutMs) {
            lineBuf.reset(); writeQueue.clear(); writing = false; mtu = 23
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            wait.await()
        } ?: false
        readyWait = null
        if (!ok) { cleanup(); return false }
        prefs.put(ADDRESS_KEY, device.address)
        return true
    }

    /** Scan until the remembered sight (or any UV-Sight) advertises; cancelled by the caller's timeout. */
    private suspend fun scanForSight(): BluetoothDevice {
        val scanner = adapter?.bluetoothLeScanner ?: throw IllegalStateException("no scanner")
        val remembered = prefs.get(ADDRESS_KEY)
        val found = CompletableDeferred<BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val name = result.scanRecord?.deviceName ?: d.name
                val isSight = (name?.startsWith(DEVICE_NAME_PREFIX) == true) ||
                    (result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE } == true)
                if (!isSight) return
                // The remembered sight or, if another one answers first, that one (the address is updated on success)
                found.complete(d)
            }
            override fun onScanFailed(errorCode: Int) { found.completeExceptionally(IllegalStateException("scan failed $errorCode")) }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build(),
            ScanFilter.Builder().setDeviceName(DEVICE_NAME_PREFIX).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, cb)
        try { return found.await() } finally { runCatching { scanner.stopScan(cb) } }
    }

    private fun cleanup() {
        val g = gatt
        gatt = null; rx = null
        val wasReady = ready
        ready = false
        writeQueue.clear(); writing = false
        runCatching { g?.disconnect(); g?.close() }
        if (wasReady) scope.launch { controller?.onLinkDown() }
    }

    override fun disconnect() { val wasReady = ready; cleanup(); if (!wasReady) scope.launch { controller?.onLinkDown() } }

    // ------------------------------------------------------------------ GATT
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (!g.requestMtu(MTU)) g.discoverServices()
                } else {
                    readyWait?.complete(false)
                    if (gatt === g) cleanup() else runCatching { g.close() }
                }
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            scope.launch { if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu; g.discoverServices() }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            scope.launch {
                val svc = g.getService(SERVICE)
                val tx = svc?.getCharacteristic(TX)
                val r = svc?.getCharacteristic(RX)
                if (status != BluetoothGatt.GATT_SUCCESS || tx == null || r == null) { readyWait?.complete(false); return@launch }
                rx = r
                g.setCharacteristicNotification(tx, true)
                val cccd = tx.getDescriptor(CCCD) ?: run { readyWait?.complete(false); return@launch }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            scope.launch {
                if (descriptor.uuid != CCCD) return@launch
                if (status != BluetoothGatt.GATT_SUCCESS) { readyWait?.complete(false); return@launch }
                ready = true
                readyWait?.complete(true)
                controller?.onLinkUp()
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return   // the new overload is called instead
            @Suppress("DEPRECATION")
            val v = characteristic.value ?: return
            scope.launch { onBytes(v) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            scope.launch { onBytes(value) }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            scope.launch { writing = false; writeNext() }
        }
    }

    private fun onBytes(v: ByteArray) {
        for (b in v) {
            if (b == '\n'.code.toByte()) {
                val line = lineBuf.toString(Charsets.UTF_8.name()).trimEnd('\r')
                lineBuf.reset()
                if (line.isNotEmpty()) controller?.onLine(line)
            } else lineBuf.write(b.toInt())
        }
    }

    // ------------------------------------------------------------------ writing
    override fun send(cmd: String) {
        if (!ready) return
        val data = (cmd + "\n").toByteArray(Charsets.UTF_8)
        val chunk = (mtu - 3).coerceIn(20, 244)
        var i = 0
        while (i < data.size) { writeQueue.add(data.copyOfRange(i, minOf(data.size, i + chunk))); i += chunk }
        writeNext()
    }

    private fun writeNext() {
        if (writing) return
        val g = gatt ?: return
        val r = rx ?: return
        val next = writeQueue.poll() ?: return
        writing = true
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(r, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            r.value = next
            r.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(r)
        }
        if (!ok) { writing = false; writeQueue.clear() }
    }
}
