/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import org.jetbrains.lincheck.descriptors.AccessPath
import org.jetbrains.lincheck.trace.network.TracingCallbacks
import org.jetbrains.lincheck.trace.network.TracingServer
import org.jetbrains.lincheck.util.Logger
import java.io.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Network-based trace collecting strategy that streams trace data to a single subscriber.
 *
 * This class maintains a single subscriber that receives trace data.
 * Recorded trace points are first written into a bounded queue
 * and then polled and served to the subscriber by a background thread.
 * As a simple backpressure defense mechanism, when the queue is full, the oldest trace points are dropped.
 *
 * Limitations:
 * - supports only [TRSnapshotLineBreakpointTracePoint] trace points;
 * - trace points are streamed as a flat list (no tree structure).
 *
 * @param context the trace context containing descriptors and metadata
 * @param queueCapacity the maximum number of trace points to the buffer.
 */
class NetworkStreamingTraceCollecting(
    val context: TraceContext,
    val tracingServer: TracingServer,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) : TraceCollectingStrategy, Closeable {
    private val tracePointQueue = ArrayBlockingQueue<TRSnapshotLineBreakpointTracePoint>(queueCapacity)

    private val recordedPoints = AtomicLong(0)
    private val droppedPoints = AtomicLong(0)

    private val lock = ReentrantLock()

    // Internal buffer and writer for serializing trace points before sending.
    // The buffers are stable; only the writer and context state are recreated
    // when we detect that the client reference has changed, because the new
    // client reader has no prior context (descriptors, strings, etc.).
    private val byteStream = ByteArrayOutputStream(MESSAGE_BUFFER_SIZE)
    private val outputStream = DataOutputStream(byteStream)
    private var contextState = SubscriberContextState()
    private var writer = NetworkTraceWriter(context, contextState, outputStream, outputStream)
    private var headerSent = false

    // Tracks the last client reference we wrote to, so we can detect reconnects.
    private var lastClient: TracingCallbacks? = null

    @Volatile
    private var running = true

    private val writerThread: Thread = thread(name = "NetworkTraceWriter", isDaemon = true) {
        writerThreadLoop()
    }

    fun clearBuffers() {
        val cleared = tracePointQueue.size
        tracePointQueue.clear()
        if (cleared > 0) {
            Logger.info { "Cleared $cleared trace points from the queue" }
        }
    }

    override fun registerCurrentThread(threadId: Int) = lock.withLock {
        context.setThreadName(threadId, Thread.currentThread().name)
    }

    override fun completeThread(thread: Thread) {
        // No-op: all threads write directly to the shared queue, no per-thread buffers to flush
    }

    override fun tracePointCreated(parent: TRContainerTracePoint?, created: TRTracePoint) {
        check(created is TRSnapshotLineBreakpointTracePoint) {
            "Only snapshot line breakpoints are supported by WebSocket trace collection strategy"
        }

        recordedPoints.incrementAndGet()

        // Try to add to the queue; if full, drop the oldest element and try again (backpressure defense).
        var dropped = 0
        var timestamp = System.currentTimeMillis()
        while (!tracePointQueue.offer(created)) {
            if (tracePointQueue.poll() != null) {
                droppedPoints.incrementAndGet()

                // Do not do logging on each iteration to avoid throttling.
                // Instead, log on each N seconds passed.
                // Re-check time only on each K drop to reduce overhead.
                dropped++
                if (dropped % 100 == 0) {
                    val now = System.currentTimeMillis()
                    val elapsed = (now - timestamp)
                    if (elapsed > 30_000 /* 30 sec */) {
                        timestamp = now
                        Logger.warn {
                            val rate = dropped / (elapsed.toDouble() / 1_000)
                            "Trace point queue full, dropped $dropped trace points in this batch, dropping rate: $rate points/sec"
                        }
                        dropped = 0
                    }
                }
            }
        }
    }

    override fun completeContainerTracePoint(thread: Thread, container: TRContainerTracePoint) {
        error("Container trace points are not supported by WebSocket trace collection strategy")
    }

    override fun traceEnded() {
        // Signal the writer thread to stop
        running = false

        // Wait for the writer thread to finish processing remaining items
        try {
            writerThread.join(5_000) // Wait up to 5 seconds
        } catch (_: InterruptedException) {
            Logger.warn { "Interrupted while waiting for writer thread to finish" }
        }
        if (writerThread.isAlive) {
            Logger.warn { "WebSocket trace writer thread did not finish in time" }
        }

        // Close the writer
        lock.withLock {
            writer.close()
        }

        Logger.info {
            val recorded = recordedPoints.get()
            val dropped = droppedPoints.get()
            "WebSocket trace streaming completed: recorded $recorded trace points, dropped $dropped trace points"
        }
    }

    override fun close() {
        traceEnded()
    }

    private fun writerThreadLoop() {
        while (/* volatile read */ running || tracePointQueue.isNotEmpty()) {
            try {
                val tracePoint = tracePointQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                writeTracePointToSubscriber(tracePoint)
            } catch (_: InterruptedException) {
                Logger.info { "Trace writer thread interrupted" }
                break
            } catch (t: Throwable) {
                Logger.error(t) { "Error in trace writer thread" }
            }
        }

        // Drain remaining items
        while (tracePointQueue.isNotEmpty()) {
            try {
                val tracePoint = tracePointQueue.poll() ?: break
                writeTracePointToSubscriber(tracePoint)
            } catch (t: Throwable) {
                Logger.error(t) { "Error in trace writer thread" }
            }
        }
    }

    /**
     * Resets the writer and context state (but not the shared buffers).
     * Called when a client change is detected, because the new client's reader
     * does not have any of the previously sent context (descriptors, strings, etc.).
     */
    private fun resetWriter() {
        contextState = SubscriberContextState()
        writer = NetworkTraceWriter(context, contextState, outputStream, outputStream)
        headerSent = false
        Logger.info { "Network trace writer reset for new client" }
    }

    private fun writeTracePointToSubscriber(tracePoint: TRSnapshotLineBreakpointTracePoint) {
        try {
            // Detect client change and reset writer if needed
            val currentClient = tracingServer.connection
            if (currentClient !== lastClient) {
                resetWriter()
                lastClient = currentClient
            }
            // Send header on first write
            if (!headerSent) {
                byteStream.reset()
                outputStream.writeLong(TRACE_MAGIC)
                outputStream.writeLong(TRACE_VERSION)
                outputStream.flush()
                tracingServer.connection.binaryTraceData(byteStream.toByteArray())
                headerSent = true
            }

            // TODO: for simplicity, we wrap each trace point into a separate block;
            //       in the future, as an optimization, we can group several consecutive trace points
            //       from the same thread into a single block.

            // Reset the byte buffer for this message
            byteStream.reset()

            // Write block start
            outputStream.writeKind(ObjectKind.BLOCK_START)
            outputStream.writeInt(tracePoint.threadId)

            outputStream.writeKind(ObjectKind.THREAD_NAME)
            outputStream.writeInt(tracePoint.threadId)
            outputStream.writeUTF(tracePoint.threadName)

            // Write trace point
            tracePoint.save(writer)

            // Write block end
            outputStream.writeKind(ObjectKind.BLOCK_END)
            outputStream.flush()

            // Send the accumulated bytes via the notifier
            tracingServer.connection.binaryTraceData(byteStream.toByteArray())
        } catch (e: Exception) {
            Logger.error(e) { "Error writing trace point to client" }
        }
    }

    companion object {
        private const val DEFAULT_QUEUE_CAPACITY = 1024
    }
}

/**
 * Per-subscriber trace context state.
 * Each subscriber maintains its own state since we don't deduplicate across subscribers.
 */
private class SubscriberContextState : ContextSavingState {
    // TODO: for simplicity, we do not attempt deduplication or string interning;
    //       just store all data in-place whenever requested.
    //       See JBRes-7900 for details.

    private val stringId = AtomicInteger(1)
    private val accessPathId = AtomicInteger(1)

    override fun isClassDescriptorSaved(id: Int): Boolean = false
    override fun markClassDescriptorSaved(id: Int): Unit = Unit
    override fun isMethodDescriptorSaved(id: Int): Boolean = false
    override fun markMethodDescriptorSaved(id: Int): Unit = Unit
    override fun isFieldDescriptorSaved(id: Int): Boolean = false
    override fun markFieldDescriptorSaved(id: Int): Unit = Unit
    override fun isVariableDescriptorSaved(id: Int): Boolean = false
    override fun markVariableDescriptorSaved(id: Int): Unit = Unit
    override fun isCodeLocationSaved(id: Int): Boolean = false
    override fun markCodeLocationSaved(id: Int): Unit = Unit

    override fun isStringSaved(value: String): Int = -(stringId.getAndIncrement())
    override fun markStringSaved(value: String): Unit = Unit

    override fun isAccessPathSaved(value: AccessPath): Int = -(accessPathId.getAndIncrement())
    override fun markAccessPathSaved(value: AccessPath): Unit = Unit
}

/**
 * Trace writer for trace WebSocket streaming.
 * Performs simple direct writes to a [DataOutputStream] without buffering.
 */
internal class NetworkTraceWriter(
    context: TraceContext,
    contextState: ContextSavingState,
    dataOutput: DataOutput,
    dataStream: OutputStream,
) : TraceWriterBase(
    context = context,
    contextState = contextState,
    dataStream = dataStream,
    dataOutput = dataOutput
) {
    override val currentDataPosition: Long = -1 // TODO: unsupported

    override val writerId: Int = -1 // TODO: unsupported

    override fun writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long) {
        // No index for WebSocket streaming
    }
}

internal typealias SnapshotLineBreakpointListener = (TRSnapshotLineBreakpointTracePoint) -> Unit

/**
 * Incremental trace reader that processes binary trace data.
 *
 * This is a transport-agnostic data processor. It does not own a connection —
 * binary data is pushed into it via [processMessage], and disconnection is
 * signalled via [handleDisconnect].
 *
 * Limitations:
 * - supports only [TRSnapshotLineBreakpointTracePoint] trace points;
 * - trace points are streamed as a flat list (no tree structure).
 */
class NetworkTraceReader : Closeable {
    
    val context: TraceContext =
        TraceContext()

    private val codeLocationsContext: CodeLocationsContext =
        CodeLocationsContext()

    private val threadTracePoints = mutableMapOf<Int, MutableList<TRSnapshotLineBreakpointTracePoint>>()

    private val tracePointListeners = mutableListOf<SnapshotLineBreakpointListener>()

    sealed class State {
        data object Running : State()
        data object Paused : State()
        data object Stopped : State()
        data object Eof : State()
    }

    private var _state = AtomicReference<State>(State.Paused)
    val state: State get() = _state.get()

    private val isTerminated: Boolean
        get() = state.let { it is State.Stopped || it is State.Eof }

    private var headerValidated = false

    /**
     * Signal that the data source has disconnected / reached EOF.
     * Transitions the reader to [State.Eof].
     */
    internal fun handleDisconnect() {
        _state.set(State.Eof)
    }


    fun getThreadTracePoints(threadId: Int): List<TRSnapshotLineBreakpointTracePoint> {
        synchronized(threadTracePoints) {
            return threadTracePoints[threadId]?.toList() ?: emptyList()
        }
    }

    fun getAllTracePoints(): List<List<TRSnapshotLineBreakpointTracePoint>> {
        synchronized(threadTracePoints) {
            return threadTracePoints.entries
                .sortedBy { it.key }
                .map { (_, tracePoints) -> tracePoints.toList() }
        }
    }

    fun getTotalTracePointCount(): Int {
        synchronized(threadTracePoints) {
            return threadTracePoints.values.sumOf { it.size }
        }
    }

    fun addTracePointListener(listener: (TRSnapshotLineBreakpointTracePoint) -> Unit) {
        synchronized(tracePointListeners) {
            tracePointListeners.add(listener)
        }
    }

    private fun notifyListeners(tracePoint: TRSnapshotLineBreakpointTracePoint) {
        synchronized(tracePointListeners) {
            tracePointListeners.forEach { it(tracePoint) }
        }
    }

    fun start() {
        _state.getAndUpdate { currentState ->
            if (currentState is State.Paused) State.Running else currentState
        }
    }

    fun stop() {
        _state.getAndUpdate { currentState ->
            if (currentState is State.Running || currentState is State.Paused) State.Stopped else currentState
        }
    }

    fun pause() {
        _state.getAndUpdate { currentState ->
            if (currentState is State.Running) State.Paused else currentState
        }
    }

    fun resume() {
        start()
    }

    internal fun processMessage(data: ByteArray) {
        val dataInput = DataInputStream(ByteArrayInputStream(data))

        // First message should be the header
        if (!headerValidated) {
            val magic = dataInput.readLong()
            check(magic == TRACE_MAGIC) {
                "Wrong WebSocket trace magic 0x${magic.toString(16)}, expected 0x${TRACE_MAGIC.toString(16)}"
            }

            val version = dataInput.readLong()
            check(version == TRACE_VERSION) {
                "Wrong WebSocket trace version $version, expected $TRACE_VERSION"
            }

            headerValidated = true
            Logger.info { "WebSocket trace header validated successfully" }
            return
        }

        // Skip messages when paused or stopped
        if (state !is State.Running) return

        // Process the binary message containing trace point data
        try {
            while (dataInput.available() > 0) {
                val kind = readObjectKind(dataInput)

                when (kind) {
                    ObjectKind.EOF -> {
                        _state.set(State.Eof)
                        Logger.debug { "WebSocket trace EOF reached. Total trace points read: ${threadTracePoints.values.sumOf { it.size }}" }
                        return
                    }

                    ObjectKind.BLOCK_START -> {
                        val threadId = dataInput.readInt()
                        // Block start marker, continue reading trace points for this thread
                    }

                    ObjectKind.BLOCK_END -> {
                        // Block end marker, continue to next block
                    }

                    ObjectKind.THREAD_NAME -> {
                        loadThreadName(dataInput, context, restore = true)
                    }

                    ObjectKind.CLASS_DESCRIPTOR -> {
                        loadClassDescriptor(dataInput, context, restore = true)
                    }

                    ObjectKind.METHOD_DESCRIPTOR -> {
                        loadMethodDescriptor(dataInput, context, restore = true)
                    }

                    ObjectKind.FIELD_DESCRIPTOR -> {
                        loadFieldDescriptor(dataInput, context, restore = true)
                    }

                    ObjectKind.VARIABLE_DESCRIPTOR -> {
                        loadVariableDescriptor(dataInput, context, restore = true)
                    }

                    ObjectKind.STRING -> {
                        loadString(dataInput, codeLocationsContext, restore = true)
                    }

                    ObjectKind.ACCESS_PATH -> {
                        val id = loadAccessPath(dataInput, codeLocationsContext, restore = true)
                        codeLocationsContext.restoreAccessPath(context, id)
                    }

                    ObjectKind.CODE_LOCATION -> {
                        val id = loadCodeLocation(dataInput, codeLocationsContext, restore = true)
                        codeLocationsContext.restoreCodeLocation(context, id)
                    }

                    ObjectKind.TRACEPOINT -> {
                        val tracePoint = loadTRTracePoint(context, dataInput)
                        check(tracePoint is TRSnapshotLineBreakpointTracePoint) {
                            "WebSocket trace reader only supports TRSnapshotLineBreakpointTracePoint, got ${tracePoint::class.simpleName}"
                        }

                        synchronized(threadTracePoints) {
                            threadTracePoints.computeIfAbsent(tracePoint.threadId) { mutableListOf() }
                                .add(tracePoint)
                        }

                        // Notify callback about the new trace point
                        try {
                            notifyListeners(tracePoint)
                        } catch (e: Exception) {
                            Logger.error { "Error in trace point listener callback: ${e.message}" }
                        }
                    }

                    ObjectKind.TRACEPOINT_FOOTER -> {
                        Logger.warn { "Unexpected TRACEPOINT_FOOTER in WebSocket stream (live debugger mode should not have container trace points)" }
                    }
                }
            }
        } catch (e: EOFException) {
            // End of this message's data, normal
        } catch (e: IOException) {
            if (!isTerminated) {
                Logger.error { "Error reading from WebSocket trace message: ${e.message}" }
            }
        }
    }

    override fun close() {
        stop()
    }

    fun isFinished(): Boolean = state is State.Eof || state is State.Stopped

    private fun readObjectKind(dataInput: DataInputStream): ObjectKind {
        val ordinal = dataInput.readByte().toInt()
        return ObjectKind.entries[ordinal]
    }
}

private const val MESSAGE_BUFFER_SIZE = 128
