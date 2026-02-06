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
import org.jetbrains.lincheck.util.Logger
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * TCP-based trace collecting strategy that streams trace data to multiple subscribers.
 *
 * This class maintains a pool of subscribers that receive trace data.
 * Recorded trace points are first written into a bounded queue
 * and then polled and served to each subscriber by a background thread.
 * As a simple backpressure defense mechanism, when the queue is full, the oldest trace points are dropped.
 *
 * Limitations:
 * - supports only [TRSnapshotLineBreakpointTracePoint] trace points;
 * - trace points are streamed as a flat list (no tree structure).
 *
 * @param context the trace context containing descriptors and metadata
 * @param queueCapacity the maximum number of trace points to the buffer.
 */
class TcpStreamingTraceCollecting(
    val context: TraceContext,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) : TraceCollectingStrategy, TcpTraceSubscriptionService, Closeable {

    private val subscribers = mutableListOf<TraceSubscriber>()
    private val tracePointQueue = ArrayBlockingQueue<TRSnapshotLineBreakpointTracePoint>(queueCapacity)

    private val recordedPoints = AtomicLong(0)
    private val droppedPoints = AtomicLong(0)

    private val lock = ReentrantLock()

    @Volatile
    private var running = true

    private val writerThread: Thread = thread(name = "TcpTraceWriter", isDaemon = true) {
        writerThreadLoop()
    }

    override fun addSubscriber(socket: Socket): TraceSubscriber? = lock.withLock {
        try {
            socket.tcpNoDelay = true // Disable Nagle's algorithm for lower latency

            val outputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), SOCKET_BUFFER_SIZE))

            // Create a trace context state for this subscriber (each subscriber gets an independent state)
            val subscriberContextState = SubscriberContextState()
            val writer = TcpTraceWriter(context, subscriberContextState, outputStream, outputStream)

            // Send header
            outputStream.writeLong(TRACE_MAGIC)
            outputStream.writeLong(TRACE_VERSION)
            outputStream.flush()

            val subscriber = TraceSubscriber(socket, outputStream, writer).also {
                subscribers.add(it)
            }
            Logger.info { "Added TCP trace subscriber from ${socket.remoteSocketAddress}. Total subscribers: ${subscribers.size}." }

            return subscriber
        } catch (e: IOException) {
            Logger.error { "Failed to add subscriber from ${socket.remoteSocketAddress}" }
            Logger.error(e)
            try {
                socket.close()
            } catch (_: IOException) {
                Logger.error { "Failed to close subscriber socket" }
                Logger.error(e)
            }
            return null
        }
    }

    override fun removeSubscriber(subscriber: TraceSubscriber): Unit = lock.withLock {
        subscriber.close()
        subscribers.remove(subscriber)
        Logger.info { "Removed TCP trace subscriber. Total subscribers: ${subscribers.size}" }
    }

    override fun subscriberCount(): Int = lock.withLock {
        subscribers.count { it.isActive }
    }

    override fun registerCurrentThread(threadId: Int) = lock.withLock {
        context.setThreadName(threadId, Thread.currentThread().name)
    }

    override fun completeThread(thread: Thread) {
        // No-op: all threads write directly to the shared queue, no per-thread buffers to flush
    }

    override fun tracePointCreated(parent: TRContainerTracePoint?, created: TRTracePoint) {
        check(created is TRSnapshotLineBreakpointTracePoint) {
            "Only snapshot line breakpoints are supported by TCP trace collection strategy"
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
        error("Container trace points are not supported by TCP trace collection strategy")
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
            Logger.warn { "TCP trace writer thread did not finish in time" }
        }

        // Send EOF to all subscribers and close them
        lock.withLock {
            for (subscriber in subscribers) {
                if (subscriber.isActive) {
                    subscriber.close()
                }
            }
            subscribers.clear()
        }

        Logger.info {
            val recorded = recordedPoints.get()
            val dropped = droppedPoints.get()
            "TCP trace streaming completed: recorded $recorded trace points, dropped $dropped trace points"
        }
    }

    override fun close() {
        traceEnded()
    }

    private fun writerThreadLoop() {
        while (/* volatile read */ running || tracePointQueue.isNotEmpty()) {
            try {
                val tracePoint = tracePointQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                writeTracePointToAllSubscribers(tracePoint)
            } catch (_: InterruptedException) {
                Logger.info { "Trace writer thread interrupted" }
                break
            } catch (t: Throwable) {
                Logger.error { "Error in trace writer thread" }
                Logger.error(t)
            }
        }

        // Drain remaining items
        while (tracePointQueue.isNotEmpty()) {
            try {
                val tracePoint = tracePointQueue.poll() ?: break
                writeTracePointToAllSubscribers(tracePoint)
            } catch (t: Throwable) {
                Logger.error { "Error in trace writer thread" }
                Logger.error(t)
            }
        }
    }

    private fun writeTracePointToAllSubscribers(tracePoint: TRSnapshotLineBreakpointTracePoint) {
        val subscribers = lock.withLock {
            subscribers.apply { retainAll { it.isActive } }.toList()
        }

        for (subscriber in subscribers) {
            if (!subscriber.isActive) {
                // simply skip inactive subscribers,
                // next trace point writer will clean up inactive subscribers
                continue
            }

            try {
                // TODO: for simplicity, we wrap each trace point into a separate block;
                //       in the future, as an optimization, we can group several consecutive trace points
                //       from the same thread into a single block.

                // Write block start
                subscriber.outputStream.writeKind(ObjectKind.BLOCK_START)
                subscriber.outputStream.writeInt(tracePoint.threadId)

                // Write trace point
                tracePoint.save(subscriber.writer)

                // Write block end
                subscriber.outputStream.writeKind(ObjectKind.BLOCK_END)
                subscriber.outputStream.flush()
            } catch (e: IOException) {
                Logger.error { "Error writing to subscriber ${subscriber.socket.remoteSocketAddress}" }
                Logger.error(e)
                subscriber.deactivate()
            }
        }
    }

    companion object {
        private const val DEFAULT_QUEUE_CAPACITY = 1024
    }
}

interface TcpTraceSubscriptionService {
    fun addSubscriber(socket: Socket): TraceSubscriber?
    fun removeSubscriber(subscriber: TraceSubscriber)
    fun subscriberCount(): Int
}

/**
 * Represents a trace data subscriber.
 *
 * @param socket the underlying TCP socket to send trace data to.
 * @param outputStream the data output stream for writing to the socket.
 * @param writer the trace writer for serializing trace points.
 * @param connectedAt timestamp when the subscriber connected.
 */
class TraceSubscriber internal constructor(
    val socket: Socket,
    val outputStream: DataOutputStream,
    internal val writer: TcpTraceWriter,
    val connectedAt: Long = System.currentTimeMillis(),
) : Closeable {
    @Volatile
    var isActive: Boolean = true
        private set

    fun deactivate() {
        isActive = false
    }

    override fun close() {
        deactivate()
        writer.close()
        try {
            socket.close()
        } catch (e: IOException) {
            Logger.error { "Error closing subscriber socket" }
            Logger.error(e)
        }
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
 * Trace writer for trace TCP streaming
 * Performs simple direct writes to a [DataOutputStream] without buffering.
 */
internal class TcpTraceWriter(
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
        // No index for TCP streaming
    }
}

internal typealias SnapshotLineBreakpointListener = (TRSnapshotLineBreakpointTracePoint) -> Unit

/**
 * Incremental TCP trace reader that reads trace data from a TCP stream.
 *
 * Limitations:
 * - supports only [TRSnapshotLineBreakpointTracePoint] trace points;
 * - trace points are streamed as a flat list (no tree structure).
 *
 * @param socket the TCP socket to read from.
 */
class TcpTraceReader(private val socket: Socket) : Closeable {
    private val inputStream = socket.getInputStream().buffered(SOCKET_BUFFER_SIZE)
    private val dataInput = DataInputStream(inputStream)

    init {
        try {
            readHeader()
        } catch (e: IOException) {
            Logger.error { "Failed to read TCP trace header: ${e.message}" }
            throw e
        }
    }

    val context: TraceContext =
        TraceContext()

    private val codeLocationsContext: CodeLocationsContext =
        CodeLocationsContext()

    private val threadTracePoints = mutableMapOf<Int, MutableList<TRSnapshotLineBreakpointTracePoint>>()

    private val tracePointListeners = mutableListOf<SnapshotLineBreakpointListener>()

    enum class State { RUNNING, PAUSED, STOPPED, EOF }

    private var _state = AtomicReference<State>(State.PAUSED)
    val state: State get() = _state.get()

    private val isRunning: Boolean
        get() = (state == State.RUNNING)

    private val isTerminated: Boolean
        get() = state.let { it == State.STOPPED || it == State.EOF }

    private val eofReached: Boolean
        get() = (state == State.EOF)

    private val readerThread: Thread =
        thread(name = "TcpTraceReader", isDaemon = true) { readerThreadLoop() }

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
        val previousState = _state.getAndUpdate { currentState ->
            if (currentState == State.PAUSED) State.RUNNING else currentState
        }
        if (previousState == State.PAUSED) {
            LockSupport.unpark(readerThread)
        }
    }

    fun stop() {
        _state.getAndUpdate { currentState ->
            if (currentState == State.RUNNING || currentState == State.PAUSED) State.STOPPED else currentState
        }
        try {
            if (readerThread.isAlive) {
                readerThread.interrupt()
            }
            readerThread.join(5_000) // Wait up to 5 seconds
        } catch (e: InterruptedException) {
            Logger.warn { "Interrupted while waiting for reader thread to finish" }
        }
    }

    fun pause() {
        _state.getAndUpdate { currentState ->
            if (currentState == State.RUNNING) State.PAUSED else currentState
        }
    }

    fun resume() {
        start()
    }

    private fun eof() {
        _state.set(State.EOF)
    }

    private fun readHeader() {
        val magic = dataInput.readLong()
        check(magic == TRACE_MAGIC) {
            "Wrong TCP trace magic 0x${magic.toString(16)}, expected 0x${TRACE_MAGIC.toString(16)}"
        }

        val version = dataInput.readLong()
        check(version == TRACE_VERSION) {
            "Wrong TCP trace version $version, expected $TRACE_VERSION"
        }

        Logger.info { "TCP trace header validated successfully" }
    }

    private fun readerThreadLoop() {
        try {
            while (true) {
                when (state) {
                    State.RUNNING -> {
                        readLoop()
                    }
                    State.PAUSED -> {
                        while (state == State.PAUSED) {
                            LockSupport.park()
                        }
                        continue
                    }
                    State.STOPPED, State.EOF -> {
                        return
                    }
                }
            }
        } catch (e: InterruptedException) {
            Logger.info { "TCP trace reader thread interrupted" }
        } catch (e: Throwable) {
            Logger.error { "Error in TCP trace reader thread: ${e.message}" }
        }
    }

    private fun readLoop() {
        try {
            while (state == State.RUNNING) {
                // Check if data is available, otherwise wait a bit
                if (inputStream.available() == 0) {
                    // Gracefully yield and wait for data
                    Thread.sleep(10) // Wait 10ms before checking again
                    continue
                }

                val kind = readObjectKind()

                when (kind) {
                    ObjectKind.EOF -> {
                        eof()
                        Logger.debug { "TCP trace EOF reached. Total trace points read: ${threadTracePoints.values.sumOf { it.size }}" }
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
                            "TCP trace reader only supports TRSnapshotLineBreakpointTracePoint, got ${tracePoint::class.simpleName}"
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
                        Logger.warn { "Unexpected TRACEPOINT_FOOTER in TCP stream (live debugger mode should not have container trace points)" }
                    }
                }
            }
        } catch (e: EOFException) {
            _state.set(State.EOF)
            Logger.info { "TCP trace stream ended (EOF). Total trace points: ${threadTracePoints.values.sumOf { it.size }}" }
        } catch (e: IOException) {
            if (!isTerminated) {
                Logger.error { "Error reading from TCP trace stream: ${e.message}" }
            } else {
                Logger.info { "TCP trace reader stopped" }
            }
            eof()
        }
    }

    override fun close() {
        stop()

        try {
            dataInput.close()
            socket.close()
        } catch (e: IOException) {
            Logger.error { "Error closing TCP trace reader: ${e.message}" }
        }
    }

    private fun readObjectKind(): ObjectKind {
        val ordinal = dataInput.readByte().toInt()
        return ObjectKind.entries[ordinal]
    }
}

/**
 * TCP server that listens for incoming trace reader connections.
 *
 * The server maintains a [TcpStreamingTraceCollecting] instance
 * and registers incoming connections as subscribers to receive trace data.
 *
 * @param port the port to listen on (0 for any available port)
 * @param subscriptionService the service to register trace data subscribers.
 */
class TcpTraceServer(
    val subscriptionService: TcpTraceSubscriptionService,
    port: Int = 0,
) : Closeable {
    private val serverSocket: ServerSocket = ServerSocket(port)

    /**
     * The actual port the server is listening on.
     */
    val port: Int get() = serverSocket.localPort

    @Volatile
    private var running = true

    private val acceptThread: Thread =
        thread(name = "TcpTraceServerAccept", isDaemon = true) { acceptThreadLoop() }

    private fun acceptThreadLoop() {
        Logger.info { "TCP trace server listening on port $port" }
        while (running) {
            try {
                acceptConnection()
            } catch (e: Exception) {
                if (running) {
                    Logger.error { "Error accepting TCP connection: ${e.message}" }
                }
            }
        }
    }

    private fun acceptConnection() {
        Logger.info { "Waiting for TCP trace connection on port $port..." }
        try {
            val socket = serverSocket.accept()
            Logger.info { "TCP trace reader connected from ${socket.remoteSocketAddress}" }
            subscriptionService.addSubscriber(socket)
        } catch (e: IOException) {
            if (running) {
                Logger.error { "Failed to accept TCP connection: ${e.message}" }
            }
        }
    }

    override fun close() {
        running = false
        try {
            serverSocket.close()
        } catch (e: IOException) {
            Logger.error { "Failed to close TCP server socket: ${e.message}" }
        }
        try {
            acceptThread.interrupt()
            acceptThread.join(5000) // Wait up to 5 seconds
        } catch (e: InterruptedException) {
            Logger.warn { "Interrupted while waiting for accept thread to finish" }
        }
    }
}

private const val SOCKET_BUFFER_SIZE = 128 // 128 byte