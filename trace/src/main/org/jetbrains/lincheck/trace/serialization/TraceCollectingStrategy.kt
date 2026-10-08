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
    fun tracePointCreated(parent: ContainerHeaderTracePoint?, created: TracePoint)

    /**
     * Must be called when a created container trace point is opened,
     * i.e., before trace points nested in it are created.
     *
     * Always follows the [tracePointCreated] call with the same trace point.
     *
     * @param container the opened container trace point.
     */
    fun openContainerTracePoint(container: ContainerHeaderTracePoint)

    /**
     * Must be called when the container trace point is ended and popped from the trace tree.
     *
     * Both sides of the container are passed explicitly: the caller owns the container's outcome
     * (a method's result, a loop's iteration count), so it is the caller who closes it.
     *
     * @param thread thread of the completed container trace point.
     * @param header the opening side of the completed container.
     * @param footer the closing side of the completed container, i.e. [header]'s
     *   [footerTracePoint][ContainerHeaderTracePoint.footerTracePoint].
     */
    fun completeContainerTracePoint(
        thread: Thread,
        header: ContainerHeaderTracePoint,
        footer: ContainerFooterTracePoint,
    )

    /**
     * Must be called when the trace is finished
     */
    fun traceEnded()
}