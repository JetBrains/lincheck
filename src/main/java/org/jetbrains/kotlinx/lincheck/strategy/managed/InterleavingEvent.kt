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
 * Stores information about events occurring during managed execution
 */
sealed class InterleavingEvent(val threadId: Int, val actorId: Int)

internal class SwitchEvent(threadId: Int, actorId: Int, val reason: SwitchReason, val callStackTrace: CallStackTrace) : InterleavingEvent(threadId, actorId)
internal class FinishEvent(threadId: Int) : InterleavingEvent(threadId, Int.MAX_VALUE)
internal class PassCodeLocationEvent(
        threadId: Int,
        actorId: Int,
        val codeLocation: CodeLocation,
        val callStackTrace: CallStackTrace
) : InterleavingEvent(threadId, actorId)
internal class StateRepresentationEvent(threadId: Int, actorId: Int, val stateRepresentation: String) : InterleavingEvent(threadId, actorId)

internal enum class SwitchReason(private val reason: String) {
    MONITOR_WAIT("wait on monitor"),
    LOCK_WAIT("lock is already acquired"),
    ACTIVE_LOCK("active lock detected"),
    SUSPENDED("coroutine is suspended"),
    STRATEGY_SWITCH(""),
    CRASH("crash"); // TODO: it is not a switch

    override fun toString() = reason
}

/**
 * Info about a [methodName] method call.
 * [identifier] helps to distinguish two different calls of the same method.
 */
internal class CallStackTraceElement(val codeLocation: MethodCallCodeLocation, val identifier: Int)