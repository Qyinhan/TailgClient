#!/usr/bin/env bash
# Offline Kotlin syntax check using minimal API stubs. Not an Android compile/device test.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'kotlinc not found' >&2; exit 1; }
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/Manifest.kt" <<'KT'
package android
object Manifest { object permission {
    const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
} }
KT
cat > "$TMP/Annotation.kt" <<'KT'
package android.annotation
annotation class SuppressLint(vararg val value: String)
KT
cat > "$TMP/Context.kt" <<'KT'
package android.content
open class Context {
    open val applicationContext: Context get() = this
    fun checkSelfPermission(permission: String): Int = 0
    fun getSystemService(name: String): Any? = null
    companion object { const val BLUETOOTH_SERVICE = "bluetooth" }
}
KT
cat > "$TMP/PackageManager.kt" <<'KT'
package android.content.pm
object PackageManager { const val PERMISSION_GRANTED = 0 }
KT
cat > "$TMP/Build.kt" <<'KT'
package android.os
object Build {
    object VERSION { const val SDK_INT = 35 }
    object VERSION_CODES { const val S = 31 }
}
class Looper { companion object { fun getMainLooper(): Looper = Looper() } }
class Handler(looper: Looper) {
    fun post(action: Runnable): Boolean { action.run(); return true }
    fun postDelayed(action: Runnable, delayMillis: Long): Boolean = true
    fun removeCallbacks(action: Runnable) {}
}
class ParcelUuid(val uuid: java.util.UUID)
KT
cat > "$TMP/Bluetooth.kt" <<'KT'
package android.bluetooth
class BluetoothManager(val adapter: BluetoothAdapter?)
class BluetoothAdapter(val isEnabled: Boolean, val bluetoothLeScanner: android.bluetooth.le.BluetoothLeScanner?)
class BluetoothDevice(val address: String) {
    fun connectGatt(context: android.content.Context, autoConnect: Boolean,
        callback: BluetoothGattCallback, transport: Int): BluetoothGatt? {
        val opened = BluetoothGatt()
        lastGatt = opened
        lastCallback = callback
        return opened
    }
    companion object {
        const val TRANSPORT_LE = 2
        var lastCallback: BluetoothGattCallback? = null
        var lastGatt: BluetoothGatt? = null
    }
}
open class BluetoothGattCallback {
    open fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {}
    open fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {}
}
class BluetoothGatt {
    var services: List<BluetoothGattService>? = null
    var discoveredCalls = 0
    var closed = false
    fun discoverServices(): Boolean { discoveredCalls++; return true }
    fun disconnect() {}
    fun close() { closed = true }
    companion object { const val GATT_SUCCESS = 0 }
}
class BluetoothGattService {
    val uuid: java.util.UUID = java.util.UUID.randomUUID()
    val characteristics: List<BluetoothGattCharacteristic>? = null
}
class BluetoothGattCharacteristic {
    val uuid: java.util.UUID = java.util.UUID.randomUUID()
    val properties: Int = 0
    companion object {
        const val PROPERTY_READ = 2
        const val PROPERTY_WRITE_NO_RESPONSE = 4
        const val PROPERTY_WRITE = 8
        const val PROPERTY_NOTIFY = 16
        const val PROPERTY_INDICATE = 32
    }
}
object BluetoothProfile { const val STATE_CONNECTED = 2; const val STATE_DISCONNECTED = 0 }
KT
cat > "$TMP/Ble.kt" <<'KT'
package android.bluetooth.le
open class ScanCallback {
    open fun onScanResult(callbackType: Int, result: ScanResult) {}
    open fun onScanFailed(errorCode: Int) {}
}
class ScanResult(val device: android.bluetooth.BluetoothDevice, val scanRecord: ScanRecord?, val rssi: Int)
class ScanRecord(val deviceName: String?, val serviceUuids: List<android.os.ParcelUuid>?)
class ScanSettings {
    class Builder {
        fun setScanMode(mode: Int): Builder = this
        fun build(): ScanSettings = ScanSettings()
    }
    companion object { const val SCAN_MODE_LOW_POWER = 0 }
}
class BluetoothLeScanner {
    fun startScan(filters: Any?, settings: ScanSettings, callback: ScanCallback) {}
    fun stopScan(callback: ScanCallback) {}
}
KT
cat > "$TMP/BleInspectorBehavior.kt" <<'KT'
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import android.content.Context
import dev.tailg.core.ble.AndroidGattInspector
import dev.tailg.core.domain.BleGattState

fun main() {
    val inspector = AndroidGattInspector(Context())
    val states = mutableListOf<BleGattState>()
    inspector.inspect(BluetoothDevice("AA:BB:CC:DD:EE:FF")) { states += it }
    val priorCallback = requireNotNull(BluetoothDevice.lastCallback)
    val priorGatt = requireNotNull(BluetoothDevice.lastGatt)
    // Out-of-order SERVICES_DISCOVERED must not be accepted.
    priorCallback.onServicesDiscovered(priorGatt, BluetoothGatt.GATT_SUCCESS)
    check(states.size == 1 && states[0] == BleGattState.Connecting)
    priorCallback.onConnectionStateChange(priorGatt, 0, BluetoothProfile.STATE_CONNECTED)
    priorCallback.onConnectionStateChange(priorGatt, 0, BluetoothProfile.STATE_CONNECTED)
    check(priorGatt.discoveredCalls == 1) { "Duplicate CONNECTED triggered discovery twice" }
    check(states.size == 2 && states[1] == BleGattState.Discovering)
    // Cancel before completion, start another device, then deliver old callback.
    inspector.close()
    check(priorGatt.closed)
    inspector.inspect(BluetoothDevice("11:22:33:44:55:66")) { states += it }
    val currentCallback = requireNotNull(BluetoothDevice.lastCallback)
    val currentGatt = requireNotNull(BluetoothDevice.lastGatt)
    priorCallback.onServicesDiscovered(priorGatt, 0)
    priorCallback.onConnectionStateChange(priorGatt, 0, BluetoothProfile.STATE_DISCONNECTED)
    check(!currentGatt.closed)
    currentCallback.onConnectionStateChange(currentGatt, 0, BluetoothProfile.STATE_CONNECTED)
    currentCallback.onServicesDiscovered(currentGatt, 0)
    check(states.last() is BleGattState.Discovered)
    check(currentGatt.closed)
    check(currentGatt.discoveredCalls == 1)
    currentCallback.onServicesDiscovered(currentGatt, 0)
    check(states.count { it is BleGattState.Discovered } == 1)
    println("ble-behavior: OK (simulated GATT callback scheduling)")
}
KT
kotlinc "$TMP"/*.kt core/domain/src/main/kotlin/dev/tailg/core/domain/BleDiscovery.kt \
  core/ble/src/main/java/dev/tailg/core/ble/AndroidBleScanner.kt \
  core/domain/src/main/kotlin/dev/tailg/core/domain/BleReference.kt \
  core/domain/src/main/kotlin/dev/tailg/core/domain/BleGattEvidence.kt \
  core/domain/src/main/kotlin/dev/tailg/core/domain/BleGattSessionGate.kt \
  core/ble/src/main/java/dev/tailg/core/ble/AndroidGattInspector.kt \
  -nowarn -include-runtime -d "$TMP/ble-syntax.jar"
java -cp "$TMP/ble-syntax.jar" BleInspectorBehaviorKt
echo 'ble-syntax: OK (Android API stubs only, not an Android build)'
