package dev.tailg.core.domain

/**
 * Fences asynchronous authentication results: a response started before logout or a different
 * login must never restore the old session. The action is committed under the same lock.
 */
class SessionMutationGate {
    private var generation = 0L

    @Synchronized
    fun ticket(): Long = generation

    @Synchronized
    fun commitIfCurrent(ticket: Long, action: () -> Unit): Boolean {
        if (ticket != generation) return false
        action()
        generation++
        return true
    }

    @Synchronized
    fun invalidate() { generation++ }
}
