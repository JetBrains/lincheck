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
import java.net.Socket
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Simplified TCP-based trace collecting strategy that streams trace data to a remote host.
 * Writes directly to TCP stream with synchronization for thread safety.
 */
class TcpStreamingTraceCollecting(
    private val host: String,
    private val port: Int,
    val context: TraceContext,
) : TraceCollectingStrategy, ContextSavingState {

    private val socket: Socket
    private val outputStream: DataOutputStream
    private val writer: TcpTraceWriter

    private val lock = ReentrantLock()

    private val points = AtomicInteger(0)

    init {
        try {
            // Establish TCP connection
            socket = Socket(host, port)
            socket.tcpNoDelay = true // Disable Nagle's algorithm for lower latency
            outputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), SOCKET_OUTPUT_BUFFER_SIZE))
            writer = TcpTraceWriter(context, this, outputStream, outputStream)

            // Send header
            lock.withLock {
                outputStream.writeLong(TRACE_MAGIC)
                outputStream.writeLong(TRACE_VERSION)
                outputStream.flush()
            }

            Logger.info { "TCP trace streaming initialized to $host:$port" }
        } catch (e: IOException) {
            Logger.error { "Failed to initialize TCP streaming to $host:$port: ${e.message}" }
            throw e
        }
    }

    override fun registerCurrentThread(threadId: Int) {
        lock.withLock {
            context.setThreadName(threadId, Thread.currentThread().name)
        }
    }

    override fun completeThread(thread: Thread) {
        // No-op: we write directly without per-thread buffers
    }

    override fun tracePointCreated(parent: TRContainerTracePoint?, created: TRTracePoint) {
        check(created is TRSnapshotLineBreakpointTracePoint) {
            "Only snapshot line breakpoints are supported by TCP trace collection strategy"
        }

        lock.withLock {
            points.incrementAndGet()

            // Write block start
            outputStream.writeKind(ObjectKind.BLOCK_START)
            outputStream.writeInt(created.threadId)

            // Write trace point
            created.save(writer)

            // Write block end
            outputStream.writeKind(ObjectKind.BLOCK_END)
            outputStream.flush()
        }
    }

    override fun completeContainerTracePoint(thread: Thread, container: TRContainerTracePoint) {
        check(false) {
            "Container trace points are not supported by TCP trace collection strategy"
        }
    }

    override fun traceEnded() {
        lock.withLock {
            try {
                // Send EOF marker
                outputStream.writeKind(ObjectKind.EOF)
                outputStream.flush()
                outputStream.close()
                socket.close()
            } catch (e: IOException) {
                Logger.error { "Error closing TCP connection: ${e.message}" }
            }

            Logger.info { "TCP trace streaming completed. Points: ${points.get()}" }
        }
    }

    // `ContextSavingState` implementation.
    // TODO: for simplicity, we do not attempt deduplication or interning;
    //       just store all data in-place whenever requested.

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

    private val stringId = AtomicInteger(1)
    private val accessPathId = AtomicInteger(1)

    override fun isStringSaved(value: String): Int = -(stringId.getAndIncrement())

    override fun markStringSaved(value: String): Unit = Unit

    override fun isAccessPathSaved(value: AccessPath): Int = -(accessPathId.getAndIncrement())

    override fun markAccessPathSaved(value: AccessPath): Unit = Unit
}

/**
 * Simple direct trace writer that writes to a DataOutputStream without buffering.
 * Used for TCP streaming where we write directly to the network stream.
 */
private class TcpTraceWriter(
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

    override fun close() {
        // Do not close the underlying stream
    }
}

/**
 * Incremental TCP trace reader that reads trace data from a TCP stream.
 *
 * Designed for live debugger mode where only [TRSnapshotLineBreakpointTracePoint] trace points
 * are streamed as a flat list (no tree structure).
 *
 * The reader runs in a background thread and continuously reads from the TCP stream.
 *
 * @param socket the TCP socket to read from.
 */
class TcpTraceReader(private val socket: Socket) : Closeable {
    private val inputStream = socket.getInputStream().buffered(SOCKET_OUTPUT_BUFFER_SIZE)
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

    private val tracePointListeners = mutableListOf<(TRSnapshotLineBreakpointTracePoint) -> Unit>()

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
 * TCP server that listens for incoming trace connections and delegates reading to [TcpTraceReader].
 *
 * This class separates the concern of accepting connections from reading trace data.
 * When a client connects, it creates a [TcpTraceReader] to handle the incoming trace stream.
 *
 * @param context the trace context
 * @param port the port to listen on (0 for any available port)
 */
class TcpTraceServer(
    val port: Int = 0,
    val onConnection: (TcpTraceReader) -> Unit,
) : Closeable {
    private val serverSocket: ServerSocket = ServerSocket(port)

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
            Logger.info { "TCP trace client connected from ${socket.remoteSocketAddress}" }
            onConnection(TcpTraceReader(socket))
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

private const val SOCKET_OUTPUT_BUFFER_SIZE = 128 // 128 byte