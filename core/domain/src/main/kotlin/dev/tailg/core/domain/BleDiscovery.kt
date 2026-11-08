package dev.tailg.core.domain

/** A short-lived advertising observation, not a pairing record or proof of vehicle ownership. */
data class BleObservation(
    val address: String,
    val advertisedName: String?,
    val rssi: Int,
    val serviceUuids: Set<String> = emptySet(),
)

sealed interface BleScanStart {
    data object Started : BleScanStart
    data object PermissionRequired : BleScanStart
    data object BluetoothDisabled : BleScanStart
    data object NotSupported : BleScanStart
    data class Failed(val reason: String) : BleScanStart
}

/** UI collection bounded to avoid untrusted broadcasts exhausting memory. */
object BleDiscoveryPolicy {
    private const val MAX_DEVICES = 100
    private val addressFormat = Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

    fun add(previous: List<BleObservation>, next: BleObservation): List<BleObservation> {
        if (!addressFormat.matches(next.address)) return previous
        val normalized = next.copy(
            address = next.address.uppercase(),
            advertisedName = next.advertisedName?.trim()?.take(80)?.takeIf(String::isNotBlank),
            rssi = next.rssi.coerceIn(-127, 20),
            serviceUuids = next.serviceUuids.take(16).map(String::lowercase).toSet(),
        )
        val collection = previous.associateByTo(LinkedHashMap()) { it.address }
        if (normalized.address !in collection && collection.size >= MAX_DEVICES) return previous
        collection[normalized.address] = normalized
        return collection.values.sortedWith(compareByDescending<BleObservation> { it.rssi }.thenBy { it.address })
    }
}
