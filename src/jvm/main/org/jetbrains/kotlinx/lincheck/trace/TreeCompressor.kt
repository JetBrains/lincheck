/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.trace

internal fun SingleThreadedTable<TraceNode>.compressSuspendImpl() = compressNodes { node ->
    val singleChild = if (node.children.size == 1) node.children[0] else return@compressNodes node
    if (node !is CallNode || singleChild !is CallNode) return@compressNodes node
    if ("${node.tracePoint.methodName}\$suspendImpl" != singleChild.tracePoint.methodName) return@compressNodes node
    
    val newNode = node.copy()
    // trace grandchildren to children, inherit correct stackTraceElement, decrement depth
    singleChild.children.forEach { 
        if (it.tracePoint is CodeLocationTracePoint) {
            (it.tracePoint as CodeLocationTracePoint).codeLocation = singleChild.tracePoint.codeLocation
        }
        it.decrementCallDepthOfTree()
        newNode.addChild(it) 
    }
    newNode
}
        
private fun SingleThreadedTable<TraceNode>.compressNodes(compressionRule: (TraceNode) -> TraceNode): SingleThreadedTable<TraceNode> = map {
    it.map { it.compress(compressionRule) }
}

private fun TraceNode.compress(compressionRule: (TraceNode) -> TraceNode): TraceNode {
    val compressedNode = compressionRule(this)
    val newNode = compressedNode.copy()
    compressedNode.children.forEach { newNode.addChild(it.compress(compressionRule)) }
    return newNode
}
