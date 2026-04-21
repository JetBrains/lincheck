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

import org.jetbrains.lincheck.descriptors.*
import org.jetbrains.lincheck.trace.TraceContext

internal fun AccessLocation.save(out: TraceWriter, traceContext: TraceContext) {
    when (this) {
        is LocalVariableAccessLocation       -> save(out, traceContext)
        is StaticFieldAccessLocation         -> save(out, traceContext)
        is ObjectFieldAccessLocation         -> save(out, traceContext)
        is ArrayElementByIndexAccessLocation -> save(out)
        is ArrayElementByNameAccessLocation  -> save(out, traceContext)
    }
}

internal fun AccessLocation.saveReferences(out: TraceWriter, traceContext: TraceContext) {
    when (this) {
        is LocalVariableAccessLocation       -> saveReferences(out, traceContext)
        is StaticFieldAccessLocation         -> saveReferences(out, traceContext)
        is ObjectFieldAccessLocation         -> saveReferences(out, traceContext)
        is ArrayElementByIndexAccessLocation -> { /* no-op */ }
        is ArrayElementByNameAccessLocation  -> { /* no-op */ }
    }
}

// Note: since `saveReferences` methods are called first, then, when `save` method is called,
//       all preceding checks are fulfilled, so no need to write them here
private fun LocalVariableAccessLocation.save(out: TraceWriter, traceContext: TraceContext) {
    check(traceContext.variablePool.contains(variableDescriptor.key)) { "Access location references must be saved before-hand, but location $this has unsaved variable $variableDescriptor" }
    val variableDescriptorId = traceContext.variablePool.getId(variableDescriptor.key)
    out.writeAccessLocationKind(AccessLocationKind.LOCAL_VARIABLE)
    out.writeInt(variableDescriptorId)
}

private fun StaticFieldAccessLocation.save(out: TraceWriter, traceContext: TraceContext) {
    check(traceContext.fieldPool.contains(fieldDescriptor.key)) { "Access location references must be saved before-hand, but location $this has unsaved field $fieldDescriptor" }
    val fieldDescriptorId = traceContext.fieldPool.getId(fieldDescriptor.key)
    out.writeAccessLocationKind(AccessLocationKind.STATIC_FIELD)
    out.writeInt(fieldDescriptorId)
}

private fun ObjectFieldAccessLocation.save(out: TraceWriter, traceContext: TraceContext) {
    check(traceContext.fieldPool.contains(fieldDescriptor.key)) { "Access location references must be saved before-hand, but location $this has unsaved field $fieldDescriptor" }
    val fieldDescriptorId = traceContext.fieldPool.getId(fieldDescriptor.key)
    out.writeAccessLocationKind(AccessLocationKind.OBJECT_FIELD)
    out.writeInt(fieldDescriptorId)
}

private fun ArrayElementByIndexAccessLocation.save(out: TraceWriter) {
    out.writeAccessLocationKind(AccessLocationKind.ARRAY_ELEMENT_BY_INDEX)
    out.writeInt(index)
}

private fun ArrayElementByNameAccessLocation.save(out: TraceWriter, traceContext: TraceContext) {
    // register or get existing access path and write its id to output stream
    check(traceContext.accessPathPool.contains(indexAccessPath)) { "Access location references must be saved before-hand, but location $this has unsaved access path $indexAccessPath" }
    val indexId = traceContext.accessPathPool.getId(indexAccessPath)
    out.writeAccessLocationKind(AccessLocationKind.ARRAY_ELEMENT_BY_NAME)
    out.writeInt(indexId)
}


private fun LocalVariableAccessLocation.saveReferences(out: TraceWriter, traceContext: TraceContext) {
    val variableDescriptorId = traceContext.variablePool.register(variableDescriptor)
    out.writeVariableDescriptor(variableDescriptorId)
}

private fun StaticFieldAccessLocation.saveReferences(out: TraceWriter, traceContext: TraceContext) {
    val fieldDescriptorId = traceContext.fieldPool.register(fieldDescriptor)
    out.writeFieldDescriptor(fieldDescriptorId)
}

private fun ObjectFieldAccessLocation.saveReferences(out: TraceWriter, traceContext: TraceContext) {
    val fieldDescriptorId = traceContext.fieldPool.register(fieldDescriptor)
    out.writeFieldDescriptor(fieldDescriptorId)
}