package dev.tailg.core.protocol

import dev.tailg.core.domain.VehicleCapability
import dev.tailg.core.domain.VehicleId

/** A platform-neutral byte transport. Android BluetoothGatt implementation comes later. */
interface ByteTransport {
    suspend fun connect(): TransportResult
    suspend fun exchange(request: ByteArray): ByteArray
    suspend fun close()
}

sealed interface TransportResult {
    data object Connected : TransportResult
    data class Failed(val message: String) : TransportResult
}

/** One vendor/vehicle-family adapter must negotiate its own authenticated session. */
interface VehicleProtocolAdapter {
    val id: String
    val supportedCapabilities: Set<VehicleCapability>
    fun matches(discovery: DeviceDiscovery): Boolean
    suspend fun authenticate(transport: ByteTransport, credentials: DeviceCredentials): TransportResult
}

data class DeviceDiscovery(val name: String?, val advertisedServiceUuids: Set<String>)

/** Credentials must be acquired via authorized pairing and never checked into git. */
interface DeviceCredentials

class VehicleProtocolRegistry(private val adapters: List<VehicleProtocolAdapter>) {
    init { require(adapters.map { it.id }.distinct().size == adapters.size) { "Duplicate adapter id" } }
    fun candidates(discovery: DeviceDiscovery): List<VehicleProtocolAdapter> =
        adapters.filter { it.matches(discovery) }
}

/** Model-level reference only; no BLE packets are generated until a protocol is validated. */
data class VehicleTransportBinding(val vehicleId: VehicleId, val adapterId: String)
