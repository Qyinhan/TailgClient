#!/usr/bin/env bash
# Compile the real Android ViewModel against local API-shaped stubs.
# This checks Kotlin type/syntax only; it does not validate Android runtime or ABI.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'kotlinc required' >&2; exit 1; }
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
KOTLIN_LIB="$(dirname "$(command -v kotlinc)")/../lib"
CORO="$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar"
if [ ! -f "$CORO" ]; then
    echo 'Local kotlin coroutines jar missing; cannot check ViewModel' >&2
    exit 1
fi
cat > "$TMP/FlowUpdate.kt" <<'KT'
package kotlinx.coroutines.flow
// This local compiler ships an older coroutines jar without the update extension.
fun <T> MutableStateFlow<T>.update(transform: (T) -> T) { value = transform(value) }
KT
cat > "$TMP/Android.kt" <<'KT'
package android.app
open class Application
KT
cat > "$TMP/Clock.kt" <<'KT'
package android.os
object SystemClock { fun elapsedRealtime(): Long = 1_000_000L }
KT
cat > "$TMP/Bluetooth.kt" <<'KT'
package android.bluetooth
open class BluetoothDevice
KT
cat > "$TMP/Lifecycle.kt" <<'KT'
package androidx.lifecycle
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
open class AndroidViewModel(app: Application) {
    protected open fun onCleared() {}
}
val AndroidViewModel.viewModelScope: CoroutineScope
    get() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
KT
cat > "$TMP/Cloud.kt" <<'KT'
package dev.tailg.core.cloud
import android.app.Application
import dev.tailg.core.domain.*
class SessionVault(app: Application)
class CloudFailure(val displayMessage: String, val unauthorized: Boolean = false) : Exception(displayMessage)
sealed interface CloudLoginResult {
    data object SessionReady : CloudLoginResult
    data class PendingVerification(val message: String) : CloudLoginResult
}
class CloudGateway(vault: SessionVault) {
    fun hasSession(): Boolean = false
    fun sessionGeneration(): Long = 0L
    fun logout() {}
    fun verifyChallengeAndSendSms(phone: String, receipt: HumanChallengeReceipt) {}
    fun login(input: SmsLoginInput, loginGeneration: Long): CloudLoginResult = CloudLoginResult.PendingVerification("")
    fun loadVehiclePage(page: Int): VehiclePage = VehiclePage(1, 10, emptyList())
}
KT
cat > "$TMP/Ble.kt" <<'KT'
package dev.tailg.core.ble
import android.app.Application
import android.bluetooth.BluetoothDevice
import dev.tailg.core.domain.*
class AndroidBleScanner(app: Application) {
    fun start(onObservation: (BleObservation) -> Unit, onError: (String) -> Unit): BleScanStart = BleScanStart.Started
    fun stop() {}
    fun observedDevice(address: String): BluetoothDevice? = null
    fun clearObservations() {}
    fun missingPermissions(): Array<String> = emptyArray()
}
class AndroidGattInspector(app: Application) {
    fun inspect(device: BluetoothDevice, onState: (BleGattState) -> Unit) {}
    fun close() {}
}
KT
mapfile -d '' sources < <(find core/domain/src/main/kotlin -name '*.kt' -print0)
kotlinc -cp "$CORO" "${sources[@]}" "$TMP"/*.kt \
  app/src/main/java/dev/tailg/client/ClientViewModel.kt \
  -nowarn -d "$TMP/viewmodel-syntax.jar"
echo 'viewmodel-syntax: OK (local stubs only; NOT an Android build)'
