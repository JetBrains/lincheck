/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.controller

/**
 * Live Debugger controller interface.
 *
 * Extends the functionality of the [TracingController] by providing
 * operations specific to the live debugger, such as managing breakpoints.
 */
interface LiveDebuggerController : TracingController {

    /**
     * Adds breakpoints to Live Debugger.
     */
    fun addBreakpoints(breakpoints: List<String>)

    /**
     * Removes breakpoints from Live Debugger.
     */
    fun removeBreakpoints(breakpoints: List<String>)
}