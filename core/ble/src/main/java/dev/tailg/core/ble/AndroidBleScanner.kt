package dev.tailg.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.tailg.core.domain.BleObservation
import dev.tailg.core.domain.BleScanStart

/**
 * Passive BLE advertising discovery only. No pairing, GATT writes or remote control.
 * Requires a foreground user action; callers must stop on leaving the page.
 */
class AndroidBleScanner(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private val observedDevices = LinkedHashMap<String, BluetoothDevice>()

    fun missingPermissions(): Array<String> = permissions().filter {
        appContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()

    @SuppressLint("MissingPermission") // Checked by missingPermissions() before all adapter operations.
    @Synchronized
    fun start(onObservation: (BleObservation) -> Unit, onError: (String) -> Unit): BleScanStart {
        if (callback != null) return BleScanStart.Failed("扫描正在进行")
        observedDevices.clear()
        if (missingPermissions().isNotEmpty()) return BleScanStart.PermissionRequired
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return BleScanStart.NotSupported
        val adapter = manager.adapter ?: return BleScanStart.NotSupported
        return try {
            if (!adapter.isEnabled) return BleScanStart.BluetoothDisabled
            val leScanner = adapter.bluetoothLeScanner ?: return BleScanStart.NotSupported
            val nextCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    // Android 12+ may throw if BLUETOOTH_CONNECT was revoked mid-scan.
                    // Do not let a platform callback crash the application.
                    val observed = try {
                        BleObservation(
                            address = result.device.address,
                            advertisedName = result.scanRecord?.deviceName,
                            rssi = result.rssi,
                            serviceUuids = result.scanRecord?.serviceUuids
                                ?.map { it.uuid.toString() }?.toSet().orEmpty(),
                        )
                    } catch (_: SecurityException) {
                        mainHandler.post {
                            if (callback === this) {
                                stop()
                                onError("蓝牙权限已撤销，请重新授权后扫描")
                            }
                        }
                        return
                    } catch (_: IllegalStateException) {
                        // Ignore a stale scan result after the Bluetooth adapter shuts down.
                        return
                    }
                    mainHandler.post {
                        if (callback === this) {
                            if (observed.address.uppercase() in observedDevices || observedDevices.size < 100) {
                                observedDevices[observed.address.uppercase()] = result.device
                                onObservation(observed)
                            }
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    mainHandler.post {
                        if (callback === this) {
                            stop()
                            onError("蓝牙扫描失败（代码 $errorCode）")
                        }
                    }
                }
            }
            scanner = leScanner
            callback = nextCallback
            leScanner.startScan(null, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), nextCallback)
            BleScanStart.Started
        } catch (_: SecurityException) {
            callback = null
            scanner = null
            BleScanStart.PermissionRequired
        } catch (_: IllegalStateException) {
            callback = null
            scanner = null
            BleScanStart.Failed("蓝牙服务暂不可用")
        }
    }

    @SuppressLint("MissingPermission") // Permission can be revoked while scanning; handle gracefully.
    @Synchronized
    fun stop() {
        val active = callback
        callback = null
        if (active != null) {
            try { scanner?.stopScan(active) } catch (_: Exception) { /* Permission revoked or BT stack stopped. */ }
        }
        scanner = null
    }

    /** Only devices observed during this process's most recent user-initiated scan. */
    fun observedDevice(address: String): BluetoothDevice? = observedDevices[address.uppercase()]

    fun clearObservations() {
        stop()
        observedDevices.clear()
    }

    companion object {
        fun permissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
