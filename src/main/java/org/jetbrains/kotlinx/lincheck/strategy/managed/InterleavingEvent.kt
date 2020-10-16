/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

internal typealias CallStackTrace = List<CallStackTraceElement>

/**
 * Method call info.
 *
 * All methods calls are enumerated to make it possible to distinguish different calls of the same method.
 * Suspended method calls have the same [identifier] before and after suspension, but different [call] points.
 */
internal class CallStackTraceElement(val call: MethodCallInterleavingPoint, val identifier: Int)

/**
 * Stores information about events occurred in managed execution.
 */
internal sealed class InterleavingEvent(val iThread: Int, val actorId: Int, val callStackTrace: CallStackTrace)

internal class SwitchEvent(
    iThread: Int, actorId: Int,
    val reason: SwitchReason,
    callStackTrace: CallStackTrace
) : InterleavingEvent(iThread, actorId, callStackTrace)

internal class PassCodeLocationEvent(
    iThread: Int, actorId: Int,
    val interleavingPoint: InterleavingPoint,
    callStackTrace: CallStackTrace
) : InterleavingEvent(iThread, actorId, callStackTrace)

internal class StateRepresentationEvent(
    iThread: Int, actorId: Int,
    val stateRepresentation: String,
    callStackTrace: CallStackTrace
) : InterleavingEvent(iThread, actorId, callStackTrace)

internal class FinishEvent(iThread: Int) : InterleavingEvent(iThread, Int.MAX_VALUE, emptyList())

internal enum class SwitchReason(private val reason: String) {
    MONITOR_WAIT("wait on monitor"),
    LOCK_WAIT("lock is already acquired"),
    ACTIVE_LOCK("active lock detected"),
    SUSPENDED("coroutine is suspended"),
    STRATEGY_SWITCH("");

    override fun toString() = reason
}