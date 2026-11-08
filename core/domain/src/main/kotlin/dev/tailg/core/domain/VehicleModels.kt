package dev.tailg.core.domain

/** Domain models stay independent of Android so an iOS adapter can be added later. */
data class VehicleId(val value: String) {
    init { require(value.isNotBlank()) { "Vehicle ID must not be blank" } }
}

data class VehicleSummary(
    val id: VehicleId,
    val displayName: String,
    val capabilities: Set<VehicleCapability>,
    val boundToCurrentAccount: Boolean,
)

enum class VehicleCapability {
    REMOTE_LOCK, REMOTE_UNLOCK, REMOTE_START, REMOTE_STOP,
    REMOTE_FIND, REMOTE_CUSHION, BLE_CONTROL, LOCATION, NFC, OTA,
}

enum class VehicleCommand(val requiredCapability: VehicleCapability) {
    LOCK(VehicleCapability.REMOTE_LOCK),
    UNLOCK(VehicleCapability.REMOTE_UNLOCK),
    START(VehicleCapability.REMOTE_START),
    STOP(VehicleCapability.REMOTE_STOP),
    FIND(VehicleCapability.REMOTE_FIND),
    OPEN_CUSHION(VehicleCapability.REMOTE_CUSHION),
}

sealed interface ControlDecision {
    data object Allowed : ControlDecision
    data class Rejected(val reason: String) : ControlDecision
}

/** Client-side safety gate; server-side authorization and vehicle acknowledgements are still mandatory. */
class VehicleCommandPolicy {
    fun authorize(vehicle: VehicleSummary, command: VehicleCommand): ControlDecision {
        if (!vehicle.boundToCurrentAccount) {
            return ControlDecision.Rejected("该车辆未绑定当前账号")
        }
        if (command.requiredCapability !in vehicle.capabilities) {
            return ControlDecision.Rejected("当前车型未声明支持该控制功能")
        }
        return ControlDecision.Allowed
    }
}

sealed interface VehicleCommandResult {
    /** Acknowledged by the physical vehicle, not just by the HTTP server. */
    data class VehicleAcknowledged(val transactionId: String) : VehicleCommandResult
    data class Rejected(val reason: String) : VehicleCommandResult
    data object Unconfirmed : VehicleCommandResult
}

interface VehicleCommandGateway {
    suspend fun send(vehicle: VehicleSummary, command: VehicleCommand): VehicleCommandResult
}

class VehicleControlUseCase(
    private val policy: VehicleCommandPolicy,
    private val gateway: VehicleCommandGateway,
) {
    suspend fun execute(vehicle: VehicleSummary, command: VehicleCommand): VehicleCommandResult {
        return when (val decision = policy.authorize(vehicle, command)) {
            ControlDecision.Allowed -> gateway.send(vehicle, command)
            is ControlDecision.Rejected -> VehicleCommandResult.Rejected(decision.reason)
        }
    }
}
