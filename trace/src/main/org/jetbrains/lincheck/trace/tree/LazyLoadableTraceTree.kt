/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.tree

import org.jetbrains.lincheck.trace.TRContainerTracePoint
import org.jetbrains.lincheck.trace.TRTracePoint
import org.jetbrains.lincheck.trace.serialization.LazyTraceReader
import org.jetbrains.lincheck.util.collections.LazyLoadableList
import org.jetbrains.lincheck.util.tree.Tree
import org.jetbrains.lincheck.util.tree.unloadChildren

/**
 * A [Tree] over trace points backed by serialized trace data.
 *
 * Nodes are materialized lazily from the trace file via [LazyTraceReader]
 * and can be unloaded again to free memory (see [LazyLoadableTraceNode]).
 *
 * @param batchLoading when `true`, a node materializes all of its children in one sequential scan
 *   on the first access to the children elements (grandchildren stay unloaded);
 *   when `false`, children are discovered by a skim and materialized one by one.
 */
class LazyLoadableTraceTree<T : TRTracePoint>(
    reader: LazyTraceReader,
    rootTracePoint: T?,
    batchLoading: Boolean = false,
) : Tree<T> {
    override val root: LazyLoadableTraceNode<T>? =
        rootTracePoint?.let { LazyLoadableTraceNode(reader, it, batchLoading = batchLoading) }
}

/**
 * Reads all per-thread root trace points shallowly and wraps each into a lazily loaded [LazyLoadableTraceTree].
 *
 * @param batchLoading see [LazyLoadableTraceTree].
 */
fun LazyTraceReader.readTraceTrees(batchLoading: Boolean = false): List<LazyLoadableTraceTree<TRTracePoint>> =
    readShallowRoots().map { root -> LazyLoadableTraceTree(this, root, batchLoading) }

/**
 * A [Tree.Node] whose children are materialized on demand from the serialized trace data.
 *
 * The tree structure lives entirely in the nodes:
 * trace points stay flat, and loading is fully encapsulated here —
 * users of the tree only ever access [children] and invoke [unloadChildren].
 *
 * Without [batchLoading], children are discovered by skimming the trace data
 * ([LazyTraceReader.readChildren]) once, on the first query of the children size or an element,
 * and then materialized one by one.
 * The skimmed children list lives as long as the node itself:
 * [unloadChildren] drops only the cached child nodes,
 * and a re-skim happens only when the node itself is dropped and materialized again.
 *
 * With [batchLoading], all children are materialized in one sequential scan
 * on the first access to the [children] elements, while grandchildren stay unloaded;
 * after [unloadChildren], the next access re-scans the whole level.
 */
class LazyLoadableTraceNode<T : TRTracePoint>(
    private val reader: LazyTraceReader,
    // TODO: capture some kind of ReaderModel to do centralized reading
    override val data: T,
    parent: Tree.Node<T>? = null,
    private val batchLoading: Boolean = false,
) : Tree.LazyLoadableNode<T> {
    override var parent: Tree.Node<T>? = parent
        private set

    override val children: LazyLoadableList<LazyLoadableTraceNode<T>> = computeChildren()

    private fun computeChildren(): LazyLoadableList<LazyLoadableTraceNode<T>> {
        // Note: currently LazyTraceReader returns the LazyLoadedList itself, but with TRTracePoint elements instead of LazyLoadableTraceNode's.
        //       This introduces some performance penalty of having to create one more layer of LazyLoadedList's here which wrap
        //       the logic of lists returned by the LazyTraceReader. Beware of its performance impact.
        val container = data as? TRContainerTracePoint
            ?: return LazyLoadableList(size = 0, load = { error("Leaf trace points have no children") })
        if (batchLoading) {
            val childTracePoints = reader.readAllChildren(container)
            return LazyLoadableList(
                loadAll = {
                    childTracePoints.map { child ->
                        @Suppress("UNCHECKED_CAST")
                        LazyLoadableTraceNode(reader, child as T, parent = this, batchLoading = true)
                    }
                },
                // Delegated to the captured list, which answers from the reader's index without loading the batch.
                computeIsEmpty = { childTracePoints.isEmpty() },
                // Drop the reader-cached trace point batch together with the node wrappers.
                unloadAll = { childTracePoints.unloadAll() },
            )
        }
        val childTracePoints = reader.readChildren(container)
        return LazyLoadableList(
            // Deferred to the captured list, so the skim happens only when the size or an element is queried.
            computeSize = { childTracePoints.size },
            load = { index ->
                @Suppress("UNCHECKED_CAST")
                LazyLoadableTraceNode(reader, childTracePoints[index] as T, parent = this)
            },
            // Drop the reader-cached trace point together with the node wrapper.
            unload = { index -> childTracePoints.unload(index) },
        )
    }
}
