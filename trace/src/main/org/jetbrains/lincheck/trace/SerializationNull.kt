/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import org.jetbrains.lincheck.util.Logger
import java.util.concurrent.atomic.AtomicLong


class NullTraceCollecting(private val context: TraceContext): TraceCollectingStrategy {
    val points = AtomicLong(0)

    override fun registerCurrentThread(threadId: Int) {
        context.setThreadName(threadId, Thread.currentThread().name)
    }

    override fun completeThread(thread: Thread) {}

    override fun tracePointCreated(
        parent: TRContainerTracePoint?,
        created: TRTracePoint
    ) {
        points.incrementAndGet()
    }

    override fun completeContainerTracePoint(thread: Thread, container: TRContainerTracePoint) {}

    /**
     * Do nothing.
     * Trace collected in memory can be saved by external means, if needed.
     */
    override fun traceEnded() {
        Logger.info { "Collected ${points.get()} points" }
    }
}
