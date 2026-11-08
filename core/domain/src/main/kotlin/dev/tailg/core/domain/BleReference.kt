package dev.tailg.core.domain

/** Static BLE hints from TailgBleConfig in the supplied 3.6.2 APK; NOT authentication. */
object BleReference {
    val advertisedNames: Set<String> = setOf(
        "TAILG_AAIFDX", "TAILG_BEUOZB", "TAILG_BGPSBEOZB", "TAILG_BGPSJLBXW",
        "TAILG_BGPSXIAIL", "TAILG_BGPSYSACY", "TAILG_JW", "TAILG_JLFBXW",
        "TAILG_HGSAIJ", "TAILG_JIFSAD", "Hi-TAILING", "Q_BASH",
        "QBIKE_2002", "QDemo_2002", "TL_2002", "QDemo_2012",
        "TL_2012", "TL_2022",
    )

    /** Only vendor-like 16-bit UUIDs; generic 2Axx GATT attributes are excluded. */
    val candidateServiceUuids: Set<String> = listOf(
        "2001", "2002", "7000", "7001", "fbb1", "fbb2", "fcc1", "fcc2",
        "fe02", "fe03", "feb1", "feb2", "feb5", "feb6",
    ).map { "0000$it-0000-1000-8000-00805f9b34fb" }.toSet()
}

data class BleReferenceMatch(
    val nameHint: String? = null,
    val uuidHints: Set<String> = emptySet(),
) {
    val isCandidate: Boolean get() = nameHint != null || uuidHints.isNotEmpty()
}

/** An advertisement may be spoofed. Never infer ownership, model or control permission. */
object BleReferencePolicy {
    fun inspect(observation: BleObservation): BleReferenceMatch {
        val name = observation.advertisedName?.trim().orEmpty()
        // Original constants may appear as prefixes with a suffix; disallow arbitrary substring matches.
        val matchedName = BleReference.advertisedNames.firstOrNull { original ->
            name.equals(original, ignoreCase = true) ||
                name.startsWith("${original}_", ignoreCase = true) ||
                name.startsWith("${original}-", ignoreCase = true)
        }
        val matchedUuids = observation.serviceUuids.map(String::lowercase)
            .filterTo(linkedSetOf()) { it in BleReference.candidateServiceUuids }
        return BleReferenceMatch(matchedName, matchedUuids)
    }
}

/** Metadata only, collected via GATT discoverServices(); no characteristic values read. */
data class BleGattCharacteristicInfo(
    val uuid: String,
    val supportsRead: Boolean,
    val supportsWrite: Boolean,
    val supportsNotify: Boolean,
    val supportsIndicate: Boolean,
)

data class BleGattServiceInfo(
    val uuid: String,
    val characteristics: List<BleGattCharacteristicInfo>,
)

sealed interface BleGattState {
    data object Idle : BleGattState
    data object Connecting : BleGattState
    data object Discovering : BleGattState
    data class Discovered(
        val services: List<BleGattServiceInfo>,
        val truncated: Boolean = false, // The Android GATT list exceeded defensive bounds.
    ) : BleGattState
    data class Failed(val message: String) : BleGattState
}

/** Bounds metadata size and normalizes UUIDs without interpreting vendor-specific protocol. */
object BleGattInventoryPolicy {
    const val MAX_SERVICES = 24
    const val MAX_CHARACTERISTICS_PER_SERVICE = 24
    const val MAX_TOTAL_CHARACTERISTICS = 192
    private val uuidFormat = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun normalize(services: List<BleGattServiceInfo>): List<BleGattServiceInfo> {
        var remaining = MAX_TOTAL_CHARACTERISTICS
        val result = ArrayList<BleGattServiceInfo>()
        for (service in services.take(MAX_SERVICES)) {
            val serviceUuid = service.uuid.lowercase()
            if (!uuidFormat.matches(serviceUuid)) continue
            val chars = service.characteristics.asSequence()
                .take(minOf(MAX_CHARACTERISTICS_PER_SERVICE, remaining))
                .map { it.copy(uuid = it.uuid.lowercase()) }
                .filter { uuidFormat.matches(it.uuid) }
                .toList()
            result += BleGattServiceInfo(serviceUuid, chars)
            remaining -= chars.size
        }
        return result
    }
}
