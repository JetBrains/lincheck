/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.serialization

import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.trace.network.TracingCallbacks
import org.jetbrains.lincheck.trace.network.TracingServer
import org.jetbrains.lincheck.util.Logger
import java.io.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.reflect.KClass

/**
 * Network-based trace collecting strategy that streams trace data to a single subscriber.
 *
 * This class maintains a single subscriber that receives trace data.
 * Recorded trace points are first written into a bounded queue
 * and then polled and served to the subscriber by a background thread.
 * As a simple backpressure defense mechanism, when the queue is full, the oldest trace points are dropped.
 * While no client is connected, the queue is kept, not drained, so tracing can start before the client connects.
 * Trace points still queued when the trace ends go to [undeliveredHitsFilePath], or are dropped when it is `null`.
 *
 * Limitations:
 * - supports only [SnapshotLineBreakpointTracePoint] trace points;
 * - trace points are streamed as a flat list (no tree structure).
 *
 * @param context the trace context containing descriptors and metadata
 * @param queueCapacity the maximum number of trace points to buffer before the oldest ones are dropped.
 * @param undeliveredHitsFilePath where the trace points still queued when the trace ends without a client
 *   are written; `null` drops them.
 */
class NetworkStreamingTraceCollecting(
    val context: TraceContext,
    val tracingServer: TracingServer,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val undeliveredHitsFilePath: String? = null,
) : TraceCollectingStrategy, Closeable {
    private val tracePointQueue = ArrayBlockingQueue<SnapshotLineBreakpointTracePoint>(queueCapacity)

    private val recordedPoints = AtomicLong(0)
    private val droppedPoints = AtomicLong(0)

    private val lock = ReentrantLock()

    // Created by resetSender for the first client, and again for each new one.
    private lateinit var sender: NetworkTracePointSender

    // Tracks the last client reference we wrote to, so we can detect reconnects.
    private var lastClient: TracingCallbacks? = null

    private var lostClient: TracingCallbacks? = null

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

    override fun tracePointCreated(parent: ContainerHeaderTracePoint?, created: TracePoint) {
        check(created is SnapshotLineBreakpointTracePoint) {
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

    override fun openContainerTracePoint(container: ContainerHeaderTracePoint) {
        error("Container trace points are not supported by WebSocket trace collection strategy")
    }

    override fun completeContainerTracePoint(
        thread: Thread,
        header: ContainerHeaderTracePoint,
        footer: ContainerFooterTracePoint,
    ) {
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
        // A hit leaves the queue before it is written, so it waits here until a client has taken it:
        // when the send fails because the client left mid-write, the next client gets it, or the file does.
        var pending: SnapshotLineBreakpointTracePoint? = null
        try {
            while (true) {
                val client = clientReadyToReceiveTracePoints()
                if (client == null) {
                    // Nobody to write to: hold the queue for the client while the trace runs.
                    if (!running) break
                    Thread.sleep(CLIENT_POLL_MILLIS)
                    continue
                }
                val tracePoint = pending ?: tracePointQueue.poll(100, TimeUnit.MILLISECONDS)
                if (tracePoint == null) {
                    if (!running && tracePointQueue.isEmpty()) break
                    continue
                }
                pending = if (writeTracePointToSubscriber(client, tracePoint)) null else tracePoint
            }
        } catch (_: InterruptedException) {
            Logger.info { "Trace writer thread interrupted" }
        }
        saveUndelivered(pending)
    }

    private fun clientReadyToReceiveTracePoints(): TracingCallbacks? =
        tracingServer.connectedClient?.takeUnless { it === lostClient }

    private fun saveUndelivered(pending: SnapshotLineBreakpointTracePoint?) {
        val left = listOfNotNull(pending) + tracePointQueue
        if (left.isEmpty()) return
        val path = undeliveredHitsFilePath
        if (path == null) {
            Logger.warn { "Dropped ${left.size} trace points: no client connected before the trace writer stopped" }
            return
        }
        try {
            writeUndeliveredHitsFile(File(path), context, left)
            tracePointQueue.clear()
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot write ${left.size} undelivered trace points to $path" }
        }
    }

    /**
     * Starts a new sender for [client], whose reader has none of the context (descriptors, strings, etc.)
     * sent so far. The sink turns a transport failure into [ClientLostException], which
     * [writeTracePointToSubscriber] tells apart from a trace point that cannot be serialized.
     */
    private fun resetSender(client: TracingCallbacks) {
        sender = NetworkTracePointSender(
            sink = { frame ->
                try {
                    client.binaryTraceData(frame)
                } catch (e: Exception) {
                    throw ClientLostException(e)
                }
            },
            context = context,
        )
        Logger.info { "Network trace writer reset for new client" }
    }

    /**
     * Writes [tracePoint] to [client], starting a fresh stream (header first) when [client] is a new one.
     *
     * @return whether the writer is done with [tracePoint]: `false` when [client] went away before taking it,
     *   so the point waits for the next client; `true` when it was sent, or cannot be written at all and is dropped.
     */
    private fun writeTracePointToSubscriber(
        client: TracingCallbacks,
        tracePoint: SnapshotLineBreakpointTracePoint,
    ): Boolean {
        if (client !== lastClient) {
            resetSender(client)
            lastClient = client
        }
        return try {
            sender.send(tracePoint)
            true
        } catch (_: ClientLostException) {
            lostClient = client
            Logger.warn { "Client left before it took a trace point; keeping it for the next client" }
            false
        } catch (t: Throwable) {
            Logger.error(t) { "Error writing trace point to client" }
            true
        }
    }

    companion object {
        private const val DEFAULT_QUEUE_CAPACITY = 1024
        private const val CLIENT_POLL_MILLIS = 20L
    }
}

/** The client a frame was handed to is gone; the trace point it belongs to was not delivered. */
private class ClientLostException(cause: Throwable) : RuntimeException(cause)

/**
 * Writes [tracePoints] to [file] as the frames of a trace stream, for a client that connects
 * only after the producing process is gone.
 */
fun writeUndeliveredHitsFile(
    file: File,
    context: TraceContext,
    tracePoints: Iterable<SnapshotLineBreakpointTracePoint>,
) {
    DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
        val sender = NetworkTracePointSender({ frame -> out.writeInt(frame.size); out.write(frame) }, context)
        tracePoints.forEach(sender::send)
    }
}

/** Reads the trace points a [writeUndeliveredHitsFile] call left in [file], in the order they were queued. */
fun readUndeliveredHitsFile(file: File): List<SnapshotLineBreakpointTracePoint> {
    val hits = mutableListOf<SnapshotLineBreakpointTracePoint>()
    NetworkTraceReader().use { reader ->
        reader.addTracePointListener { hits += it }
        reader.start()
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            while (true) {
                val size = try { input.readInt() } catch (_: EOFException) { break }
                reader.processMessage(ByteArray(size).also(input::readFully))
            }
        }
    }
    return hits
}

/**
 * Per-subscriber trace context state.
 * Each subscriber maintains its own state since we don't deduplicate across subscribers.
 */
private class SubscriberTraceContextSavedState : TraceContextSavedState {
    // TODO: for simplicity, we do not attempt deduplication or string interning;
    //       just store all data in-place whenever requested.
    //       See JBRes-7900 for details.

    override fun isDescriptorSaved(descriptorClass: KClass<*>, id: Int): Boolean = false
    override fun markDescriptorSaved(descriptorClass: KClass<*>, id: Int): Unit = Unit
}


/**
 * Serializes [SnapshotLineBreakpointTracePoint] objects into the frames of the trace stream
 * and hands each frame to [sink] — a [TracingCallbacks] websocket, or any other frame consumer.
 *
 * This class encapsulates the header and per-trace-point serialization logic so that
 * both the lincheck tracing infrastructure and the control-plane can send trace points
 * without duplicating low-level protocol details.
 *
 * @param sink receives each frame, in order
 * @param context the trace context containing descriptors and metadata
 * @param runtime the producing runtime, preserved when forwarding a foreign trace
 */
class NetworkTracePointSender(
    private val sink: (ByteArray) -> Unit,
    context: TraceContext,
    private val runtime: String = RUNTIME_JVM,
) {
    constructor(callbacks: TracingCallbacks, context: TraceContext, runtime: String = RUNTIME_JVM) :
        this(callbacks::binaryTraceData, context, runtime)

    private val lock = ReentrantLock()
    private val byteStream = ByteArrayOutputStream(MESSAGE_BUFFER_SIZE)
    private val outputStream = DataOutputStream(byteStream)
    private var contextState: TraceContextSavedState = SubscriberTraceContextSavedState()
    private var writer: TraceWriter = NetworkTraceWriter(context, contextState, outputStream, outputStream)
    private var headerSent = false

    /**
     * Serializes and sends a single [SnapshotLineBreakpointTracePoint] over the WebSocket.
     *
     * On the first call, the trace protocol header (magic number, version and runtime) is sent
     * automatically before the trace point data.
     */
    fun send(tracePoint: SnapshotLineBreakpointTracePoint) = lock.withLock {
        // Send header on first write
        if (!headerSent) {
            byteStream.reset()
            outputStream.writeTraceHeader(runtime)
            outputStream.flush()
            sink(byteStream.toByteArray())
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

        // Write thread name
        outputStream.writeKind(ObjectKind.THREAD_NAME)
        outputStream.writeThreadName(tracePoint.threadId, tracePoint.threadName)

        // Write trace point
        writer.writeTracePoint(tracePoint)

        // Write block end
        outputStream.writeKind(ObjectKind.BLOCK_END)
        outputStream.flush()

        sink(byteStream.toByteArray())
    }
}

/**
 * Trace writer for trace WebSocket streaming.
 * Performs simple direct writes to a [DataOutputStream] without buffering.
 */
internal class NetworkTraceWriter(
    context: TraceContext,
    override val contextState: TraceContextSavedState,
    dataOutput: DataOutput,
    dataStream: OutputStream,
) : ContextAwareTraceWriter(
    context = context,
    dataStream = dataStream,
    dataOutput = dataOutput
) {
    override val currentDataPosition: Long = -1 // TODO: unsupported

    override val writerId: Int = -1 // TODO: unsupported

    override fun writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long) {
        // No index for WebSocket streaming
    }
}

internal typealias SnapshotLineBreakpointListener = (SnapshotLineBreakpointTracePoint) -> Unit

/**
 * Incremental trace reader that processes binary trace data.
 *
 * This is a transport-agnostic data processor. It does not own a connection —
 * binary data is pushed into it via [processMessage], and disconnection is
 * signalled via [handleDisconnect].
 *
 * Limitations:
 * - supports only [SnapshotLineBreakpointTracePoint] trace points;
 * - trace points are streamed as a flat list (no tree structure).
 */
class NetworkTraceReader : Closeable {
    
    val context = TraceContext()

    private val threadTracePoints = mutableMapOf<Int, MutableList<SnapshotLineBreakpointTracePoint>>()

    private val tracePointListeners = mutableListOf<SnapshotLineBreakpointListener>()

    // Trace points decoded while paused; delivered in order on [start]. Guarded by [tracePointListeners].
    private val pendingTracePoints = mutableListOf<SnapshotLineBreakpointTracePoint>()

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

    /** Runtime that produced the stream, as read from the header; `null` until the header arrives. */
    var runtime: String? = null
        private set

    /**
     * Signal that the data source has disconnected / reached EOF.
     * Transitions the reader to [State.Eof].
     */
    fun handleDisconnect() {
        _state.set(State.Eof)
    }


    fun getThreadTracePoints(threadId: Int): List<SnapshotLineBreakpointTracePoint> {
        synchronized(threadTracePoints) {
            return threadTracePoints[threadId]?.toList() ?: emptyList()
        }
    }

    fun getAllTracePoints(): List<List<SnapshotLineBreakpointTracePoint>> {
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

    fun addTracePointListener(listener: (SnapshotLineBreakpointTracePoint) -> Unit) {
        synchronized(tracePointListeners) {
            tracePointListeners.add(listener)
        }
    }

    private fun notifyListeners(tracePoint: SnapshotLineBreakpointTracePoint) {
        synchronized(tracePointListeners) {
            tracePointListeners.forEach { it(tracePoint) }
        }
    }

    private fun deliver(tracePoint: SnapshotLineBreakpointTracePoint) {
        synchronized(tracePointListeners) {
            if (state is State.Running) notifyListeners(tracePoint) else pendingTracePoints.add(tracePoint)
        }
    }

    /**
     * Starts delivering trace points to the listeners, beginning with those decoded while paused.
     * A reader starts paused, so frames that arrive before its consumer subscribes are kept, not lost —
     * also when the source has already disconnected by then: a process that streams its hits and exits
     * right away still gets them delivered. Only an explicit [stop] discards them.
     */
    fun start() {
        synchronized(tracePointListeners) {
            _state.getAndUpdate { currentState ->
                if (currentState is State.Paused) State.Running else currentState
            }
            if (state !is State.Stopped) {
                pendingTracePoints.forEach(::notifyListeners)
                pendingTracePoints.clear()
            }
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

    fun processMessage(data: ByteArray) {
        val dataInput = DataInputStream(ByteArrayInputStream(data))

        // First message should be the header
        if (!headerValidated) {
            runtime = dataInput.checkTraceHeader()
            headerValidated = true
            Logger.info { "WebSocket trace header validated successfully, runtime: $runtime" }
            return
        }

        // A stopped reader ignores the rest of the stream; a paused one keeps decoding (the context
        // objects must be applied in order) and holds the trace points for [start].
        if (isTerminated) return

        // Process the binary message containing trace point data
        try {
            while (dataInput.available() > 0) {
                val kind = dataInput.readKind()

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
                        loadString(dataInput, context, restore = true)
                    }

                    ObjectKind.ACCESS_PATH -> {
                        loadAccessPath(dataInput, context, restore = true)
                    }

                    ObjectKind.CODE_LOCATION -> {
                        loadCodeLocation(dataInput, context, restore = true)
                    }

                    ObjectKind.TRACEPOINT -> {
                        val tracePoint = dataInput.readTracePointData(context)
                        check(tracePoint is SnapshotLineBreakpointTracePoint) {
                            "WebSocket trace reader only supports SnapshotLineBreakpointTracePoint, got ${tracePoint::class.simpleName}"
                        }

                        synchronized(threadTracePoints) {
                            threadTracePoints.computeIfAbsent(tracePoint.threadId) { mutableListOf() }
                                .add(tracePoint)
                        }

                        try {
                            deliver(tracePoint)
                        } catch (e: Exception) {
                            Logger.error { "Error in trace point listener callback: ${e.message}" }
                        }
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
}

private const val MESSAGE_BUFFER_SIZE = 128
