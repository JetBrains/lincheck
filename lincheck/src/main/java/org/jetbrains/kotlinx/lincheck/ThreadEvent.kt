package org.jetbrains.kotlinx.lincheck

/**
 * ThreadEvent stores information about events occuring during managed execution so that we could
 * write the execution by Reporter
 */
sealed class ThreadEvent(val iThread: Int, val iActor: Int)

class SwitchEvent(iThread: Int, actorId: Int, val info: StackTraceElement, val reason: SwitchReason) : ThreadEvent(iThread, actorId)
class SuspendSwitchEvent(iThread: Int, actorId: Int) : ThreadEvent(iThread, actorId) {
    val reason = SwitchReason.SUSPENDED
}
class FinishEvent(iThread: Int, actorId: Int) : ThreadEvent(iThread, actorId)
class PassCodeLocationEvent(iThread: Int, actorId: Int, val info: StackTraceElement) : ThreadEvent(iThread, actorId)

enum class SwitchReason(private val reason: String) {
    MONITOR_WAIT("wait on monitor"),
    LOCK_WAIT("lock is already acquired"),
    ACTIVE_LOCK("active lock detected"),
    SUSPENDED("coroutine is suspended"),
    STRATEGY_SWITCH("");


    override fun toString(): String {
        return reason;
    }
}