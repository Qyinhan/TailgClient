package dev.tailg.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.tailg.core.domain.BleGattCharacteristicInfo
import dev.tailg.core.domain.BleGattInventoryPolicy
import dev.tailg.core.domain.BleGattServiceInfo
import dev.tailg.core.domain.BleGattSessionGate
import dev.tailg.core.domain.BleGattState

/**
 * One-shot metadata inspection: connect -> discoverServices -> close.
 * Explicit user selection required. Never bonds, reads values, subscribes, or writes.
 * All public entrypoints are called on the main thread by the ViewModel.
 */
class AndroidGattInspector(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private val sessionGate = BleGattSessionGate()
    private var timeout: Runnable? = null
    private var listener: ((BleGattState) -> Unit)? = null

    @SuppressLint("MissingPermission") // Runtime permission checked; revocation caught below.
    fun inspect(device: BluetoothDevice, onState: (BleGattState) -> Unit) {
        close()
        listener = onState
        val operation = sessionGate.begin()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            finish(operation, BleGattState.Failed("缺少蓝牙连接权限"))
            return
        }
        val timeoutRunnable = Runnable { finish(operation, BleGattState.Failed("GATT 发现超时，请靠近设备后重试")) }
        timeout = timeoutRunnable
        handler.postDelayed(timeoutRunnable, 12_000L)
        onState(BleGattState.Connecting)
        try {
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(remote: BluetoothGatt, status: Int, newState: Int) {
                    handler.post {
                        if (!sessionGate.isCurrent(operation)) return@post
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            finish(operation, BleGattState.Failed("蓝牙连接失败（GATT $status）"))
                            return@post
                        }
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                if (!sessionGate.acceptConnected(operation)) return@post
                                listener?.invoke(BleGattState.Discovering)
                                try {
                                    if (!remote.discoverServices()) {
                                        finish(operation, BleGattState.Failed("无法发起 GATT 服务发现"))
                                    }
                                } catch (_: SecurityException) {
                                    finish(operation, BleGattState.Failed("蓝牙权限已失效"))
                                } catch (_: IllegalStateException) {
                                    finish(operation, BleGattState.Failed("GATT 服务暂不可用"))
                                }
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                if (sessionGate.finishIfActive(operation)) {
                                    finish(operation, BleGattState.Failed("设备已断开，未完成服务发现"))
                                }
                            }
                        }
                    }
                }

                override fun onServicesDiscovered(remote: BluetoothGatt, status: Int) {
                    handler.post {
                        if (!sessionGate.acceptServiceResult(operation)) return@post
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            finish(operation, BleGattState.Failed("GATT 服务发现失败（代码 $status）"))
                            return@post
                        }
                        try {
                            val fullServices = remote.services.orEmpty()
                            val truncated = fullServices.size > BleGattInventoryPolicy.MAX_SERVICES ||
                                fullServices.any { it.characteristics.orEmpty().size > BleGattInventoryPolicy.MAX_CHARACTERISTICS_PER_SERVICE } ||
                                fullServices.sumOf { it.characteristics.orEmpty().size } > BleGattInventoryPolicy.MAX_TOTAL_CHARACTERISTICS
                            val services = fullServices.take(BleGattInventoryPolicy.MAX_SERVICES)
                                .map { service ->
                                    BleGattServiceInfo(
                                        uuid = service.uuid.toString(),
                                        characteristics = service.characteristics.orEmpty()
                                            .take(BleGattInventoryPolicy.MAX_CHARACTERISTICS_PER_SERVICE)
                                            .map { characteristic ->
                                                val props = characteristic.properties
                                                BleGattCharacteristicInfo(
                                                    uuid = characteristic.uuid.toString(),
                                                    supportsRead = props and BluetoothGattCharacteristic.PROPERTY_READ != 0,
                                                    supportsWrite = props and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                                                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0,
                                                    supportsNotify = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
                                                    supportsIndicate = props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
                                                )
                                            },
                                    )
                                }
                            finish(operation, BleGattState.Discovered(BleGattInventoryPolicy.normalize(services), truncated))
                        } catch (_: SecurityException) {
                            finish(operation, BleGattState.Failed("获取 GATT 元信息的权限不足"))
                        } catch (_: IllegalStateException) {
                            finish(operation, BleGattState.Failed("GATT 服务已不可用"))
                        }
                    }
                }
            }
            val opened = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (sessionGate.isCurrent(operation) && opened != null) {
                gatt = opened
            } else {
                safelyClose(opened)
                if (sessionGate.isCurrent(operation)) finish(operation, BleGattState.Failed("系统未建立蓝牙连接"))
            }
        } catch (_: SecurityException) {
            finish(operation, BleGattState.Failed("缺少蓝牙连接权限"))
        } catch (_: IllegalArgumentException) {
            finish(operation, BleGattState.Failed("无效蓝牙设备"))
        } catch (_: IllegalStateException) {
            finish(operation, BleGattState.Failed("系统蓝牙当前不可用"))
        }
    }

    /** Drop stale callbacks before closing transport; never leave an idle GATT link open. */
    fun close() {
        sessionGate.invalidate()
        timeout?.let(handler::removeCallbacks)
        timeout = null
        listener = null
        safelyClose(gatt)
        gatt = null
    }

    private fun finish(operation: Long, state: BleGattState) {
        if (!sessionGate.isCurrent(operation)) return
        val notify = listener
        close()
        notify?.invoke(state)
    }

    @SuppressLint("MissingPermission") // close() still runs when the user revokes permission.
    private fun safelyClose(current: BluetoothGatt?) {
        if (current == null) return
        try { current.disconnect() } catch (_: Exception) { /* revoked or BT stack gone */ }
        try { current.close() } catch (_: Exception) { /* best-effort cleanup */ }
    }
}
