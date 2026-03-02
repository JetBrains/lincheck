/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.jmx

/**
 * Live debugger JMX controller.
 *
 * Extends the functionality of the tracing JMX controller by providing
 * operations specific to the live debugger, such as managing breakpoints.
 */
interface LiveDebuggerJmxController : TracingJmxController {
    /**
     * Adds breakpoints to Live Debugger.
     */
    fun addBreakpoints(breakpoints: List<String>)

    /**
     * Removes breakpoints from Live Debugger.
     */
    fun removeBreakpoints(breakpoints: List<String>)

    companion object {
        /**
         * JMX notification type sent when a breakpoint reaches its hit limit.
         *
         * The notification's `userData` is a string `"className:fileName:lineNumber"` identifying
         * the breakpoint. The IDE plugin uses this to find and disable the corresponding breakpoint.
         */
        const val BREAKPOINT_HIT_LIMIT_NOTIFICATION_TYPE = "breakpoint.hitLimitReached"
    }
}