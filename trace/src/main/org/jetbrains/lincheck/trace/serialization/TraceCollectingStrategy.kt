package org.jetbrains.lincheck.trace.serialization

import org.jetbrains.lincheck.trace.*

/**
 * It is a strategy to collect trace: it can be full-track in memory or streaming to a file on-the-fly
 */
interface TraceCollectingStrategy {
    /**
     * Register the current thread in strategy.
     */
    fun registerCurrentThread(threadId: Int)

    /**
     * Makes sure that thread has written all of its recorded data.
     */
    fun completeThread(thread: Thread)

    /**
     * Must be called when a new tracepoint is created.
     *
     * @param parent Current top of the call stack, if exists.
     * @param created New tracepoint
     */
    fun tracePointCreated(parent: TRContainerTracePoint?, created: TRTracePoint)

    /**
     * Must be called when a created container trace point is opened,
     * i.e., before trace points nested in it are created.
     *
     * Always follows the [tracePointCreated] call with the same trace point.
     *
     * @param container the opened container trace point.
     */
    fun openContainerTracePoint(container: TRContainerTracePoint)

    /**
     * Must be called when the container trace point is ended and popped from the trace tree.
     *
     * @param thread thread of the [container] trace point.
     * @param container the completed container trace point.
     */
    fun completeContainerTracePoint(thread: Thread, container: TRContainerTracePoint)

    /**
     * Must be called when the trace is finished
     */
    fun traceEnded()
}