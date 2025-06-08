/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.tracerecorder

import org.jetbrains.kotlinx.lincheck.util.runInsideIgnoredSection
import sun.nio.ch.lincheck.ThreadDescriptor

object TraceRecorder {
    fun install(className: String, methodName: String, methodDesc: String, traceFileName: String?) {
        val eventTracker = TraceCollectingEventTracker(className, methodName, methodDesc, traceFileName)
        val desc = ThreadDescriptor.getCurrentThreadDescriptor() ?: ThreadDescriptor(Thread.currentThread()).also {
            ThreadDescriptor.setCurrentThreadDescriptor(it)
        }
        desc.eventTracker = eventTracker
    }

    fun enableTrace() = runInsideIgnoredSection {
        val desc = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val eventTracker = desc.eventTracker ?: return
        if (eventTracker is TraceCollectingEventTracker) {
            eventTracker.enableTrace()
            desc.enableAnalysis()
        }
    }

    fun finishTrace() = runInsideIgnoredSection {
        val desc = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val eventTracker = desc.eventTracker ?: return
        if (eventTracker is TraceCollectingEventTracker) {
            desc.disableAnalysis()
            eventTracker.finishTrace()
        }
    }
}