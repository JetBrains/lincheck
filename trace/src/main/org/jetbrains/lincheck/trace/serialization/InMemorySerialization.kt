/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.serialization

import org.jetbrains.lincheck.trace.TRContainerTracePoint
import org.jetbrains.lincheck.trace.TRTracePoint
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.util.Logger
import org.jetbrains.lincheck.util.collections.SimpleBitmap
import org.jetbrains.lincheck.util.tree.Tree
import org.jetbrains.lincheck.util.tree.mutableNode
import org.jetbrains.lincheck.util.tree.node
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

private class InMemoryTraceContextSavedState: SimpleTraceContextSavedState() {
    override val seenClassDescriptors = SimpleBitmap(1024)
    override val seenMethodDescriptors = SimpleBitmap(1024)
    override val seenFieldDescriptors = SimpleBitmap(1024)
    override val seenVariableDescriptors = SimpleBitmap(1024)
    override val seenStringDescriptors = SimpleBitmap(1024)
    override val seenCodeLocationDescriptors = SimpleBitmap(65536)
    override val seenAccessPathDescriptors = SimpleBitmap(1024)
}

internal class DirectTraceWriter(
    dataStream: OutputStream,
    indexStream: OutputStream,
    context: TraceContext,
    override val contextState: TraceContextSavedState = InMemoryTraceContextSavedState(),
    private val pos: PositionCalculatingOutputStream = PositionCalculatingOutputStream(dataStream),
) : ContextAwareTraceWriter(
    context = context,
    dataStream = pos,
    dataOutput = DataOutputStream(pos)
) {
    private val index = DataOutputStream(indexStream)

    private var currentWriterId: Int = 0
    private var currentBlockStart: Long = 0 // with header
    private var currentDataStart: Long = 0 // after header

    private var indexBytes: Long = 0

    override val currentDataPosition: Long get() = pos.currentPosition - currentDataStart
    override val writerId: Int get() = currentWriterId

    init {
        dataOutput.writeTraceHeader()
        index.writeTraceIndexHeader()
        indexBytes += Long.SIZE_BYTES * 2
    }

    override fun close() {
        super.close()
        index.writeLong(INDEX_MAGIC)
        indexBytes += Long.SIZE_BYTES
        index.close()

        Logger.info { "Data size: ${pos.currentPosition} bytes" }
        Logger.info { "Index size: $indexBytes bytes" }
    }

    override fun writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long) {
        if (kind == ObjectKind.TRACEPOINT) {
            index.writeIndexCell(kind, id, startPos, endPos)
        } else {
            index.writeIndexCell(kind, id, startPos + currentDataStart, endPos + currentDataStart)
        }
        indexBytes += INDEX_CELL_SIZE
    }

    fun startNewRoot(id: Int) {
        currentWriterId = id
        currentBlockStart = pos.currentPosition
        dataOutput.writeKind(ObjectKind.BLOCK_START)
        dataOutput.writeInt(id)
        currentDataStart = pos.currentPosition
    }

    fun endRoot() {
        val endPos = pos.currentPosition
        dataOutput.writeKind(ObjectKind.BLOCK_END)
        index.writeIndexCell(ObjectKind.BLOCK_START, currentWriterId, currentBlockStart, endPos)
        indexBytes += INDEX_CELL_SIZE
    }
}

class MemoryTraceCollecting(
    private val context: TraceContext,
    private val collectFlat: Boolean,
): TraceCollectingStrategy {
    // The tree structure is owned by the strategy (trace points themselves stay flat):
    // each thread grows its own tree, tracking the currently open containers as a stack,
    // and every created point is attached to the container on top of it.
    private class ThreadTreeBuilder {
        var root: Tree.MutableNode<TRTracePoint>? = null
        val openContainers: MutableList<Tree.MutableNode<TRTracePoint>> = arrayListOf()
        var lastCreatedNode: Tree.MutableNode<TRTracePoint>? = null
    }

    // `registerCurrentThread` and `tracePointCreated` are called concurrently from worker threads,
    // so structural modifications (new thread registrations) must not race with reads/writes.
    private val treeBuilders = ConcurrentHashMap<Int, ThreadTreeBuilder>()

    private val _flatListsPerThread = ConcurrentHashMap<Int, MutableList<TRTracePoint>>()
    val flatListsPerThread: Map<Int, List<TRTracePoint>> get() = _flatListsPerThread

    override fun registerCurrentThread(threadId: Int) {
        context.setThreadName(threadId, Thread.currentThread().name)
        if (collectFlat) {
            _flatListsPerThread[threadId] = mutableListOf()
        } else {
            treeBuilders[threadId] = ThreadTreeBuilder()
        }
    }

    override fun completeThread(thread: Thread) {}

    override fun tracePointCreated(
        parent: TRContainerTracePoint?,
        created: TRTracePoint
    ) {
        if (collectFlat) {
            _flatListsPerThread[created.threadId]?.add(created) ?: Logger.warn {
                "Thread #${created.threadId} is not registered at the moment of creation of ${created.toText(verbose = false)} trace point"
            }
            return
        }

        val builder = treeBuilders[created.threadId] ?: run {
            Logger.warn { "Thread #${created.threadId} is not registered at the moment of creation of ${created.toText(verbose = false)} trace point" }
            return
        }
        val node = mutableNode<TRTracePoint>(created)
        builder.lastCreatedNode = node
        val top = builder.openContainers.lastOrNull()
        if (top != null) {
            if (parent !== top.data) {
                Logger.warn { "Parent of ${created.toText(verbose = false)} trace point does not match the currently open container" }
            }
            top.children.add(node)
        } else if (builder.root == null) {
            builder.root = node
        }
    }

    override fun openContainerTracePoint(container: TRContainerTracePoint) {
        val builder = treeBuilders[container.threadId] ?: return
        val node = builder.lastCreatedNode
        if (node == null || node.data !== container) {
            Logger.warn { "Opened container ${container.toText(verbose = false)} trace point is not the last created trace point" }
            return
        }
        builder.openContainers.add(node)
    }

    override fun completeContainerTracePoint(thread: Thread, container: TRContainerTracePoint) {
        val builder = treeBuilders[container.threadId] ?: return
        val stack = builder.openContainers
        // The completed container is normally on top, but the tracker may abandon enclosed
        // frames without completing them (e.g. super constructor calls on exception),
        // so pop everything above the matching container as well to stay in sync.
        val index = stack.indexOfLast { it.data === container }
        if (index < 0) {
            Logger.warn { "Completed container ${container.toText(verbose = false)} trace point is not open" }
            return
        }
        while (stack.size > index) {
            stack.removeLast()
        }
    }

    /**
     * Do nothing.
     * Trace collected in memory can be saved by external means, if needed.
     */
    override fun traceEnded() {}

    /**
     * Returns the recorded trace as one tree per thread root, in threadId order.
     * In flat collection mode each recorded point becomes its own single-node tree,
     * in the flattened per-thread order.
     *
     * Must be called only after the recording has ended.
     */
    fun getRecordedTrees(): List<Tree<TRTracePoint>> {
        if (collectFlat) {
            return _flatListsPerThread.entries
                .sortedBy { it.key }
                .flatMap { (_, points) -> points.map { Tree(node(it)) } }
        }
        return treeBuilders.entries
            .sortedBy { it.key }
            .mapNotNull { (threadId, builder) ->
                val root = builder.root
                if (root == null) {
                    val threadName = context.getThreadName(threadId)
                    Logger.error { "Trace Recorder: Thread #${threadId + 1} ($threadName): No root call found" }
                    null
                } else {
                    Tree<TRTracePoint>(root)
                }
            }
    }
}

/**
 * Top-level function to save full-depth recorded trace old-style (all in once)
 */
fun saveRecorderTrace(baseFileName: String, context: TraceContext, trees: List<Tree<TRTracePoint>>) {
    val (data, index) = openNewStandardDataAndIndex(baseFileName)
    return saveRecorderTrace(
        data = data,
        index = index,
        context = context,
        trees = trees
    )
}


// TODO: check if there are multiple functions like this which could unified with the Tree API
fun saveRecorderTrace(data: OutputStream, index: OutputStream, context: TraceContext, trees: List<Tree<TRTracePoint>>) {
    DirectTraceWriter(data, index, context).use { tw ->
        trees.forEachIndexed { id, tree ->
            val root = tree.root ?: return@forEachIndexed
            tw.startNewRoot(id)
            tw.writeThreadName(id, context.getThreadName(id))
            saveTraceTree(tw, root)
            tw.endRoot()
        }
    }
}

private fun saveTraceTree(writer: TraceWriter, node: Tree.Node<TRTracePoint>) {
    val tracepoint = node.data
    writer.writeTracePoint(tracepoint)
    if (tracepoint is TRContainerTracePoint) {
        node.children.forEach { saveTraceTree(writer, it) }
        writer.writeTracePointFooter(tracepoint)
    }
}

private fun DataOutput.writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long) {
    writeByte(kind.ordinal)
    writeInt(id)
    writeLong(startPos)
    writeLong(endPos)
}
