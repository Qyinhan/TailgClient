package dev.tailg.core.domain

/**
 * Compare *public* GATT UUID metadata with the constants in the supplied APK.
 * UUID overlap is a debugging clue, never a vehicle identity, pairing result or permission to control.
 */
data class BleGattEvidenceSummary(
    val matchedServices: Set<String>,
    val matchedCharacteristics: Set<String>,
    val serviceCount: Int,
    val characteristicCount: Int,
) {
    val hasStaticMatches: Boolean get() = matchedServices.isNotEmpty() || matchedCharacteristics.isNotEmpty()
}

object BleGattEvidence {
    private val originalUuids: Set<String> = setOf(
        "2001", "2002", "2a23", "2a28", "2a40", "2a41",
        "7000", "7001", "fbb1", "fbb2", "fcc1", "fcc2",
        "fe02", "fe03", "feb1", "feb2", "feb5", "feb6",
    ).mapTo(linkedSetOf()) { "0000$it-0000-1000-8000-00805f9b34fb" }

    /** Caller-provided raw GATT data is untrusted; normalize and cap it before use. */
    fun inspect(services: List<BleGattServiceInfo>): BleGattEvidenceSummary {
        val normalized = BleGattInventoryPolicy.normalize(services)
        return BleGattEvidenceSummary(
            matchedServices = normalized.map { it.uuid }.filterTo(linkedSetOf()) { it in originalUuids },
            matchedCharacteristics = normalized.flatMap { it.characteristics }
                .map { it.uuid }.filterTo(linkedSetOf()) { it in originalUuids },
            serviceCount = normalized.size,
            characteristicCount = normalized.sumOf { it.characteristics.size },
        )
    }

    /**
     * User-triggered copy only. Excludes Bluetooth MAC, scan names, vehicle IDs,
     * characteristic values, user identifiers, tokens and credentials.
     * Custom UUIDs may be vendor-specific; users choose whether to share this summary.
     */
    fun anonymousReport(services: List<BleGattServiceInfo>, truncated: Boolean = false): String {
        val normalized = BleGattInventoryPolicy.normalize(services)
        val evidence = inspect(normalized)
        return buildString {
            appendLine("TailgClient BLE GATT 元信息（用户主动导出）")
            appendLine("只含 UUID/属性；不含 MAC、设备名称、特征数据、账号或密钥。")
            appendLine("证据来源：STATIC_APK + 本机发现；不代表认证或控车权限。")
            if (truncated) appendLine("注意：服务或特征数量超出安全限额，此清单仅为截断后的摘要。")
            appendLine("GATT 服务：${evidence.serviceCount}，特征：${evidence.characteristicCount}")
            appendLine("静态匹配服务：${evidence.matchedServices.size}，特征：${evidence.matchedCharacteristics.size}")
            for (service in normalized) {
                appendLine("S ${service.uuid}")
                for (item in service.characteristics) {
                    val flags = buildString {
                        if (item.supportsRead) append('R')
                        if (item.supportsWrite) append('W')
                        if (item.supportsNotify) append('N')
                        if (item.supportsIndicate) append('I')
                    }
                    appendLine("  C ${item.uuid} [$flags]")
                }
            }
        }
    }
}
