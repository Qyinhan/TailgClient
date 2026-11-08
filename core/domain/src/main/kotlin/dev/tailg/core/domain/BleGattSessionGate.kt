package dev.tailg.core.domain

/**
 * Pure-Kotlin ordering guard for Android's asynchronous GATT callbacks.
 *
 * Android may deliver duplicate CONNECTED, a delayed service result after close,
 * or callbacks from the previous device while a new inspection is active.
 * A generation is never a login or ownership credential.
 */
class BleGattSessionGate {
    enum class Phase { IDLE, CONNECTING, DISCOVERING, COMPLETED }

    private var sequence = 0L
    var phase: Phase = Phase.IDLE
        private set

    fun begin(): Long {
        sequence += 1
        phase = Phase.CONNECTING
        return sequence
    }

    /** True only for the first CONNECTED callback in the current inspection. */
    fun acceptConnected(operation: Long): Boolean {
        if (operation != sequence || phase != Phase.CONNECTING) return false
        phase = Phase.DISCOVERING
        return true
    }

    /** A service result before CONNECTED or after cancellation is stale. */
    fun acceptServiceResult(operation: Long): Boolean {
        if (operation != sequence || phase != Phase.DISCOVERING) return false
        phase = Phase.COMPLETED
        return true
    }

    /** Errors/disconnect may terminate any pending inspection exactly once. */
    fun finishIfActive(operation: Long): Boolean {
        if (operation != sequence || phase !in setOf(Phase.CONNECTING, Phase.DISCOVERING)) return false
        phase = Phase.COMPLETED
        return true
    }

    fun isCurrent(operation: Long): Boolean =
        operation == sequence && phase != Phase.IDLE

    /** Invalidate all queued callbacks before closing the BluetoothGatt. */
    fun invalidate() {
        sequence += 1
        phase = Phase.IDLE
    }
}
