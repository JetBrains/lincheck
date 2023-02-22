/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package sun.nio.ch.lincheck

import java.util.*

// we need to use some "legal" package for the bootstrap class loader

internal interface SharedEventsTracker {
    companion object {
        @JvmStatic
        var currentTracker: SharedEventsTracker? = null
    }

    fun lock(monitor: Any, codeLocation: Int)
    fun unlock(monitor: Any, codeLocation: Int)

    fun park(codeLocation: Int)
    fun unpark(thread: Thread, codeLocation: Int)

    fun wait(monitor: Any, codeLocation: Int, withTimeout: Boolean)
    fun notify(monitor: Any, codeLocation: Int, notifyAll: Boolean)

    fun beforeReadField(obj: Any, className: String, fieldName: String, codeLocation: Int)
    fun beforeReadFieldStatic(className: String, fieldName: String, codeLocation: Int)
    fun beforeReadArrayElement(array: Array<*>, index: Int, codeLocation: Int)
    fun onReadValue(value: Any?)

    fun beforeWriteField(obj: Any, className: String, fieldName: String, value: Any?, codeLocation: Int)
    fun beforeWriteFieldStatic(className: String, fieldName: String, value: Any?, codeLocation: Int)
    fun beforeWriteArrayElement(array: Array<*>, index: Int, value: Any?, codeLocation: Int)

    fun beforeMethodCall0(owner: Any?, className: String, methodName: String, codeLocation: Int)
    fun beforeMethodCall1(owner: Any?, className: String, methodName: String, codeLocation: Int, param1: Any?)
    fun beforeMethodCall2(owner: Any?, className: String, methodName: String, codeLocation: Int, param1: Any?, param2: Any?)
    fun beforeMethodCall3(owner: Any?, className: String, methodName: String, codeLocation: Int, param1: Any?, param2: Any?, param3: Any?)
    fun beforeMethodCall4(owner: Any?, className: String, methodName: String, codeLocation: Int, param1: Any?, param2: Any?, param3: Any?, param4: Any?)
    fun beforeMethodCall5(owner: Any?, className: String, methodName: String, codeLocation: Int, param1: Any?, param2: Any?, param3: Any?, param4: Any?, param5: Any?)
    fun beforeMethodCall(owner: Any?, className: String, methodName: String, codeLocation: Int, params: Array<Any?>)
    fun onMethodCallFinishedSuccessfully(result: Any?)
    fun onMethodCallThrewException(t: Throwable)

    fun getRandom(currentThreadId: Int): Random
}

/**
When Lincheck runs a test, all threads should be instances of this [TestThread] class.
See its usages to get more insight.

NB: we need to load this class in the bootstrap class loader, as the transformation requires it.
 */
internal class TestThread(
    val iThread: Int,
    r: Runnable
) : Thread(r, "Lincheck-$iThread") {
    @JvmField
    var cont: Any? = null // The suspended continuation, if present.

    // This flag is set to `true` when Lincheck runs user's code.
    //
    // When it is `false`, the analysis is disabled.
    @JvmField
    var inTestingCode = false

    // During user's executions, Lincheck may enter a code block the analysis of which
    // should be disabled. As such code blocks can be nested, we maintain the current
    // depth of them.
    //
    // When it is >=0, the analysis is disabled.
    @JvmField
    var ignoredSectionDepth = 0
}
