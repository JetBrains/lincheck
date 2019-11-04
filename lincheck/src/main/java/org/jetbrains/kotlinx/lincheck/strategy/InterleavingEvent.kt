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
package org.jetbrains.kotlinx.lincheck.strategy

/**
 * InterleavingEvent stores information about events occuring during managed execution so that we could
 * write the execution by Reporter
 */
sealed class InterleavingEvent(val threadId: Int, val actorId: Int)

class SwitchEvent(threadId: Int, actorId: Int, val info: StackTraceElement, val reason: SwitchReason) : InterleavingEvent(threadId, actorId)
class SuspendSwitchEvent(threadId: Int, actorId: Int) : InterleavingEvent(threadId, actorId) {
    val reason = SwitchReason.SUSPENDED
}
class FinishEvent(threadId: Int, actorId: Int) : InterleavingEvent(threadId, actorId)
class PassCodeLocationEvent(threadId: Int, actorId: Int, val codeLocation: StackTraceElement) : InterleavingEvent(threadId, actorId)

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