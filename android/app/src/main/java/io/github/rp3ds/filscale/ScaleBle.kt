package io.github.rp3ds.filscale

import android.annotation.SuppressLint
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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.UUID

/**
 * BLE link to the scale, in parallel with Wi-Fi. Same JSON as the WebSocket arrives as
 * notifications on STATE; commands go out as JSON on CMD. Reconnects by itself.
 * The caller must hold BLUETOOTH_SCAN / BLUETOOTH_CONNECT (API 31+) before start().
 */
@SuppressLint("MissingPermission")
class ScaleBle(
    context: Context,
    private val linked: (Boolean, String) -> Unit,
    private val frame: (JSONObject) -> Unit,
    private val foundCb: (List<FoundScale>) -> Unit,
) {
    companion object {
        val SERVICE: UUID = UUID.fromString("6e5f0001-b5a3-f393-e0a9-e50e24dcca9e")
        val STATE: UUID = UUID.fromString("6e5f0002-b5a3-f393-e0a9-e50e24dcca9e")
        val CMD: UUID = UUID.fromString("6e5f0003-b5a3-f393-e0a9-e50e24dcca9e")
        /** Needs a paired (encrypted) link: Wi-Fi and account passwords go through here. */
        val SECURE: UUID = UUID.fromString("6e5f0004-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MTU = 185   // must match NimBLEDevice::setMTU on the firmware
    }

    private val ctx = context.applicationContext
    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var secChar: BluetoothGattCharacteristic? = null
    private var pendingSecure: String? = null
    private var scanning = false
    private var wanted = false

    /** Address of the scale the user chose. Without one, nothing is connected automatically. */
    private var target: String? = null
    private var discovering = false
    private val seen = LinkedHashMap<String, FoundScale>()

    fun setTarget(address: String?) { target = address }

    private val discoveryCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!discovering) return
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            seen[result.device.address] = FoundScale(result.device.address, name, result.rssi)
            foundCb(seen.values.sortedByDescending { it.rssi })
        }
    }

    /** Lists nearby scales (does not connect). Call stopDiscovery() when the picker closes. */
    fun startDiscovery() {
        if (adapter?.isEnabled != true || discovering) return
        val scanner = adapter.bluetoothLeScanner ?: return
        stopScan()   // the connect-scan, if any, resumes afterwards
        seen.clear()
        foundCb(emptyList())
        discovering = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, discoveryCb) }.onFailure { discovering = false }
    }

    fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(discoveryCb) }
        if (wanted) start()
    }

    /** Switches to another scale: drops the current link and connects to `address`. */
    fun choose(address: String) {
        stopDiscovery()
        gatt?.let { it.disconnect(); it.close() }
        gatt = null; cmdChar = null; secChar = null; ready = false
        linked(false, "")
        target = address
        wanted = true
        start()
    }

    /** Forgets the chosen scale and disconnects. */
    fun forget() {
        target = null
        stop()
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return   // results can still arrive after stopScan(); one connection only
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            retryLater()
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(MTU)
            } else {
                g.close()
                if (g === gatt) {
                    gatt = null
                    cmdChar = null
                    secChar = null
                    linked(false, "")
                    retryLater()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE)
            val state = svc?.getCharacteristic(STATE)
            cmdChar = svc?.getCharacteristic(CMD)
            secChar = svc?.getCharacteristic(SECURE)
            if (state == null) {
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(state, true)
            val cccd = state.getDescriptor(CCCD) ?: return
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, enable)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = enable
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                ready = true
                linked(true, g.device.name ?: "")
            }
        }

        // API 33+: value delivered directly.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            parse(value)
        }

        // API < 33: value sits on the characteristic. On 33+ the framework also calls this
        // for compatibility, so ignore it there to avoid handling every frame twice.
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                c.value?.let(::parse)
            }
        }
    }

    private fun parse(bytes: ByteArray) {
        runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.onSuccess(frame)
    }

    fun start() {
        wanted = true
        val addr = target ?: return   // nothing chosen yet: wait for the picker
        if (adapter?.isEnabled != true || gatt != null || scanning || discovering) return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).setDeviceAddress(addr).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val scanner = adapter.bluetoothLeScanner ?: return
        scanning = true
        scanner.startScan(listOf(filter), settings, scanCb)
    }

    fun stop() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        cmdChar = null
        secChar = null
        linked(false, "")
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    private var ready = false

    private fun connect(dev: BluetoothDevice) {
        ready = false
        val g = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        // A connection that never completes (scale rebooting, stale bond, radio busy) must not
        // leave us stuck holding a dead GATT object: give up and scan again.
        main.postDelayed({
            if (wanted && gatt === g && !ready) {
                g.disconnect()
                g.close()
                gatt = null
                cmdChar = null
                secChar = null
                retryLater()
            }
        }, 15_000)
    }

    private fun retryLater() {
        if (!wanted) return
        main.postDelayed({ start() }, 2_000)
    }

    val isLinked: Boolean get() = gatt != null && cmdChar != null

    /** Sends {"cmd":...} to the scale; false when the link is not ready. */
    fun send(json: String): Boolean = write(cmdChar, json)

    /**
     * Same, on the encrypted characteristic. The first use bonds with the scale ("Just Works"
     * pairing, Android shows its own prompt); the message is held and sent once bonded.
     */
    fun sendSecure(json: String): Boolean {
        val g = gatt ?: return false
        if (secChar == null) return false
        if (g.device.bondState != BluetoothDevice.BOND_BONDED) {
            pendingSecure = json
            if (g.device.createBond()) return true
        }
        return write(secChar, json)
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            if (state == BluetoothDevice.BOND_BONDED) {
                pendingSecure?.let { pendingSecure = null; write(secChar, it) }
            } else if (state == BluetoothDevice.BOND_NONE) {
                pendingSecure = null
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            ctx, bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun write(c: BluetoothGattCharacteristic?, json: String): Boolean {
        val g = gatt ?: return false
        c ?: return false
        val bytes = json.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = bytes
                g.writeCharacteristic(c)
            }
        }
    }
}

/** A scale seen while picking one: its advertised name (filscale-XXXX), address and signal. */
data class FoundScale(val address: String, val name: String, val rssi: Int)
