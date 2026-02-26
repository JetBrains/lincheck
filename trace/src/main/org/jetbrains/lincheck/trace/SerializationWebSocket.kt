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

import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.jetbrains.lincheck.descriptors.AccessPath
import org.jetbrains.lincheck.util.Logger
import java.io.*
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * WebSocket-based trace collecting strategy that streams trace data to a single subscriber.
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
class WebSocketStreamingTraceCollecting(
    val context: TraceContext,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) : TraceCollectingStrategy, WebSocketTraceSubscriptionService, Closeable {

    @Volatile
    private var subscriber: TraceSubscriber? = null
    private val tracePointQueue = ArrayBlockingQueue<TRSnapshotLineBreakpointTracePoint>(queueCapacity)

    private val recordedPoints = AtomicLong(0)
    private val droppedPoints = AtomicLong(0)

    private val lock = ReentrantLock()

    @Volatile
    private var running = true

    private val writerThread: Thread = thread(name = "WebSocketTraceWriter", isDaemon = true) {
        writerThreadLoop()
    }

    override fun addSubscriber(conn: WebSocket): TraceSubscriber? = lock.withLock {
        try {
            // Close existing subscriber if any
            subscriber?.close()

            val byteStream = ByteArrayOutputStream(MESSAGE_BUFFER_SIZE)
            val outputStream = DataOutputStream(byteStream)

            // Create a trace context state for this subscriber (each subscriber gets an independent state)
            val subscriberContextState = SubscriberContextState()
            val writer = WebSocketTraceWriter(context, subscriberContextState, outputStream, outputStream)

            // Send header as a binary WebSocket message
            outputStream.writeLong(TRACE_MAGIC)
            outputStream.writeLong(TRACE_VERSION)
            conn.send(byteStream.toByteArray())
            byteStream.reset()

            val newSubscriber = TraceSubscriber(conn, byteStream, outputStream, writer)
            subscriber = newSubscriber
            Logger.info { "Added WebSocket trace subscriber from ${conn.remoteSocketAddress}." }

            return newSubscriber
        } catch (e: Exception) {
            Logger.error(e) { "Failed to add subscriber from ${conn.remoteSocketAddress}" }
            try {
                conn.close()
            } catch (_: Exception) {
                Logger.error(e) { "Failed to close subscriber WebSocket" }
            }
            return null
        }
    }

    override fun removeSubscriber(subscriber: TraceSubscriber): Unit = lock.withLock {
        subscriber.close()
        if (this.subscriber === subscriber) {
            this.subscriber = null
        }
        Logger.info { "Removed WebSocket trace subscriber." }
    }

    override fun hasSubscriber(): Boolean {
        return subscriber?.conn?.isOpen == true
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

        // Send EOF to subscriber and close
        lock.withLock {
            subscriber?.close()
            subscriber = null
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

    private fun writeTracePointToSubscriber(tracePoint: TRSnapshotLineBreakpointTracePoint) {
        val subscriber = subscriber ?: return
        if (!subscriber.conn.isOpen) return

        try {
            // TODO: for simplicity, we wrap each trace point into a separate block;
            //       in the future, as an optimization, we can group several consecutive trace points
            //       from the same thread into a single block.

            // Reset the byte buffer for this message
            subscriber.byteStream.reset()

            // Write block start
            subscriber.outputStream.writeKind(ObjectKind.BLOCK_START)
            subscriber.outputStream.writeInt(tracePoint.threadId)

            subscriber.outputStream.writeKind(ObjectKind.THREAD_NAME)
            subscriber.outputStream.writeInt(tracePoint.threadId)
            subscriber.outputStream.writeUTF(tracePoint.threadName)

            // Write trace point
            tracePoint.save(subscriber.writer)

            // Write block end
            subscriber.outputStream.writeKind(ObjectKind.BLOCK_END)
            subscriber.outputStream.flush()

            // Send the accumulated bytes as a single binary WebSocket message
            subscriber.conn.send(subscriber.byteStream.toByteArray())
        } catch (e: Exception) {
            Logger.error(e) { "Error writing to subscriber ${subscriber.conn.remoteSocketAddress}" }
        }
    }

    companion object {
        private const val DEFAULT_QUEUE_CAPACITY = 1024
    }
}

interface WebSocketTraceSubscriptionService {
    fun addSubscriber(conn: WebSocket): TraceSubscriber?
    fun removeSubscriber(subscriber: TraceSubscriber)
    fun hasSubscriber(): Boolean
}

/**
 * Represents a trace data subscriber connected via WebSocket.
 *
 * @param conn the underlying WebSocket connection to send trace data to.
 * @param byteStream the byte array output stream used to accumulate binary data before sending.
 * @param outputStream the data output stream for writing serialized trace data.
 * @param writer the trace writer for serializing trace points.
 * @param connectedAt timestamp when the subscriber connected.
 */
class TraceSubscriber internal constructor(
    val conn: WebSocket,
    internal val byteStream: ByteArrayOutputStream,
    val outputStream: DataOutputStream,
    internal val writer: WebSocketTraceWriter,
    val connectedAt: Long = System.currentTimeMillis(),
) : Closeable {

    override fun close() {
        writer.close()
        try {
            conn.close()
        } catch (e: Exception) {
            Logger.error(e) { "Error closing subscriber WebSocket" }
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
 * Trace writer for trace WebSocket streaming.
 * Performs simple direct writes to a [DataOutputStream] without buffering.
 */
internal class WebSocketTraceWriter(
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
 * Incremental WebSocket trace reader that reads trace data from a WebSocket connection.
 *
 * Limitations:
 * - supports only [TRSnapshotLineBreakpointTracePoint] trace points;
 * - trace points are streamed as a flat list (no tree structure).
 *
 * @param serverUri the WebSocket server URI to connect to (e.g., "ws://localhost:5555").
 */
// TODO refactor/remove state logic (is it needed / worth the added complexity?)
class WebSocketTraceReader(private val serverUri: URI) : Closeable {
    
    val context: TraceContext =
        TraceContext()

    private val codeLocationsContext: CodeLocationsContext =
        CodeLocationsContext()

    private val threadTracePoints = mutableMapOf<Int, MutableList<TRSnapshotLineBreakpointTracePoint>>()

    private val tracePointListeners = mutableListOf<SnapshotLineBreakpointListener>()

    sealed class State(val onDisconnect: () -> Unit) {
        abstract fun withDisconnectHandler(handler: () -> Unit): State
        
        class Running(onDisconnect: () -> Unit) : State(onDisconnect) {
            override fun withDisconnectHandler(handler: () -> Unit) = Running(handler)
        }
        class Paused(onDisconnect: () -> Unit) : State(onDisconnect) {
            override fun withDisconnectHandler(handler: () -> Unit) = Paused(handler)
        }
        class Stopped(onDisconnect: () -> Unit) : State(onDisconnect) {
            override fun withDisconnectHandler(handler: () -> Unit) = Stopped(handler)
        }
        class Eof(onDisconnect: () -> Unit) : State(onDisconnect) {
            override fun withDisconnectHandler(handler: () -> Unit): Eof {
                // When already eof directly call onDisconnect
                handler()
                return Eof(handler)
            }
        }
    
        fun toRunning() = Running(onDisconnect)
        fun toPaused() = Paused(onDisconnect)
        fun toStopped() = Stopped(onDisconnect)
        fun toEof() = Eof(onDisconnect)
    }

    private var _state = AtomicReference<State>(State.Paused({}))
    val state: State get() = _state.get()

    private val isTerminated: Boolean
        get() = state.let { it is State.Stopped || it is State.Eof }

    private var headerValidated = false

    private val wsClient: WebSocketClient = object : WebSocketClient(serverUri) {
        override fun onOpen(handshakedata: ServerHandshake?) {
            Logger.info { "WebSocket trace reader connected to $serverUri" }
        }

        override fun onMessage(message: String) {
            // Text messages not expected
            Logger.warn { "Unexpected text message received in WebSocket trace reader" }
        }

        override fun onMessage(bytes: ByteBuffer) {
            try {
                val data = ByteArray(bytes.remaining())
                bytes.get(data)
                processMessage(data)
            } catch (e: Exception) {
                Logger.error { "Error processing WebSocket message: ${e.message}" }
            }
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            Logger.info { "WebSocket trace reader closed: code=$code, reason=$reason, remote=$remote" }
            val prevState = _state.getAndUpdate { it.toEof() }
            // Only invoke onDisconnect if we weren't already in a terminal state
            // (EOF handler already called onDisconnect)
            if (prevState !is State.Eof && prevState !is State.Stopped) {
                prevState.onDisconnect()
            }
        }

        override fun onError(ex: Exception?) {
            Logger.error { "WebSocket trace reader error: ${ex?.message}" }
        }
    }

    init {
        val connected = wsClient.connectBlocking(10, TimeUnit.SECONDS)
        if (!connected) {
            Logger.error { "Failed to connect WebSocket trace reader to $serverUri within 10 seconds" }
        }
    }
    
    fun registerOnDisconnectListener(onDisconnected: (() -> Unit)) {
        val wrappedDisconnect = {
            try {
                onDisconnected()
            } catch (t: Throwable) {
                Logger.error(t) { "Error in onDisconnected callback" }
            }
        }
        _state.getAndUpdate { it.withDisconnectHandler(wrappedDisconnect) }
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
            if (currentState is State.Paused) currentState.toRunning() else currentState
        }
    }

    fun stop() {
        _state.getAndUpdate { currentState ->
            if (currentState is State.Running || currentState is State.Paused) currentState.toStopped() else currentState
        }
        try {
            wsClient.close()
        } catch (e: InterruptedException) {
            Logger.warn { "Interrupted while closing WebSocket client" }
        }
    }

    fun pause() {
        _state.getAndUpdate { currentState ->
            if (currentState is State.Running) currentState.toPaused() else currentState
        }
    }

    fun resume() {
        start()
    }

    private fun processMessage(data: ByteArray) {
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
                        _state.getAndUpdate { it.toEof() }
                        Logger.debug { "WebSocket trace EOF reached. Total trace points read: ${threadTracePoints.values.sumOf { it.size }}" }
                        _state.get().onDisconnect()
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

    private fun readObjectKind(dataInput: DataInputStream): ObjectKind {
        val ordinal = dataInput.readByte().toInt()
        return ObjectKind.entries[ordinal]
    }
}

/**
 * WebSocket server that listens for incoming trace reader connections.
 *
 * The server maintains a [WebSocketStreamingTraceCollecting] instance
 * and registers incoming connections as subscribers to receive trace data.
 *
 * @param port the port to listen on (0 for any available port)
 * @param subscriptionService the service to register trace data subscribers.
 */
class WebSocketTraceServer(
    port: Int,
    val subscriptionService: WebSocketTraceSubscriptionService,
    private val onDisconnected: (() -> Unit)? = null,
) : Closeable {

    private val wsServer: WebSocketServer
    private val subscriberLock = ReentrantLock()
    private var currentSubscriber: Pair<WebSocket, TraceSubscriber>? = null

    /**
     * The actual port the server is listening on.
     */
    val port: Int get() = wsServer.port

    init {
        wsServer = object : WebSocketServer(InetSocketAddress(port)) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
                Logger.info { "WebSocket trace reader connected from ${conn.remoteSocketAddress}" }
                subscriberLock.withLock {
                    // Only one client allowed at a time — close any existing connection
                    currentSubscriber?.let { (existingConn, existingSubscriber) ->
                        Logger.info { "Closing existing WebSocket connection from ${existingConn.remoteSocketAddress} to allow new client" }
                        subscriptionService.removeSubscriber(existingSubscriber)
                        try {
                            existingConn.close()
                        } catch (e: Exception) {
                            Logger.error(e) { "Error closing existing WebSocket connection" }
                        }
                    }
                    val subscriber = subscriptionService.addSubscriber(conn)
                    currentSubscriber = if (subscriber != null) conn to subscriber else null
                }
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
                Logger.info { "WebSocket trace reader disconnected from ${conn.remoteSocketAddress}" }
                subscriberLock.withLock {
                    currentSubscriber?.let { (existingConn, subscriber) ->
                        if (existingConn === conn) {
                            subscriptionService.removeSubscriber(subscriber)
                            currentSubscriber = null
                            Logger.info { "WebSocket trace subscriber disconnected" }
                            try {
                                onDisconnected?.invoke()
                            } catch (t: Throwable) {
                                Logger.error(t) { "Error in onDisconnected callback" }
                            }
                        }
                    }
                }
            }

            override fun onMessage(conn: WebSocket, message: String) {
                Logger.warn { "Unexpected message received on WebSocket trace server" }
            }

            override fun onMessage(conn: WebSocket, message: ByteBuffer) {
                Logger.warn { "Unexpected message received on WebSocket trace server" }
            }

            override fun onError(conn: WebSocket?, ex: Exception?) {
                Logger.error { "WebSocket trace server error: ${ex?.message}" }
            }

            override fun onStart() {
                Logger.info { "WebSocket trace server started on port ${this.port}" }
            }
        }
        wsServer.isReuseAddr = true
        wsServer.start()
        Logger.info { "WebSocket trace server listening on port ${wsServer.port}" }
    }

    override fun close() {
        try {
            wsServer.stop(5000) // Wait up to 5 seconds for graceful shutdown
        } catch (e: Exception) {
            Logger.error { "Failed to close WebSocket server: ${e.message}" }
        }
    }
}

private const val MESSAGE_BUFFER_SIZE = 128
