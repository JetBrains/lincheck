package org.jetbrains.kotlinx.lincheck

/**
 * ThreadEvent stores information about events occuring during managed execution so that we could
 * write the execution by Reporter
 */
sealed class ThreadEvent(val iThread: Int, val iActor: Int)

class SwitchEvent(iThread: Int, actorId: Int, val info: StackTraceElement, val reason: String = "") : ThreadEvent(iThread, actorId)
class SuspendSwitchEvent(iThread: Int, actorId: Int) : ThreadEvent(iThread, actorId)
class FinishEvent(iThread: Int, actorId: Int) : ThreadEvent(iThread, actorId)
class PassCodeLocationEvent(iThread: Int, actorId: Int, val info: StackTraceElement) : ThreadEvent(iThread, actorId)
