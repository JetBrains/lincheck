/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import org.jetbrains.lincheck.descriptors.AccessLocation
import org.jetbrains.lincheck.descriptors.ArrayElementByIndexAccessLocation
import org.jetbrains.lincheck.descriptors.ArrayElementByNameAccessLocation
import org.jetbrains.lincheck.descriptors.LocalVariableAccessLocation
import org.jetbrains.lincheck.descriptors.ObjectFieldAccessLocation
import org.jetbrains.lincheck.descriptors.StaticFieldAccessLocation

internal fun AccessLocation.save(out: TraceWriter, traceContext: TraceContext, savingState: ContextSavingState) {
    when (this) {
        is LocalVariableAccessLocation       -> save(out, traceContext)
        is StaticFieldAccessLocation         -> save(out, traceContext)
        is ObjectFieldAccessLocation         -> save(out, traceContext)
        is ArrayElementByIndexAccessLocation -> save(out)
        is ArrayElementByNameAccessLocation  -> save(out, savingState)
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
    check(traceContext.hasVariableDescriptor(variableDescriptor)) { "Access location references must be saved before-hand, but location $this has unsaved variable $variableDescriptor" }
    val variableDescriptorId = traceContext.getOrCreateVariableId(variableDescriptor)
    out.writeLocationKind(AccessLocationKind.LOCAL_VARIABLE)
    out.writeInt(variableDescriptorId)
}

private fun StaticFieldAccessLocation.save(out: TraceWriter, traceContext: TraceContext) {
    check(traceContext.hasFieldDescriptor(fieldDescriptor)) { "Access location references must be saved before-hand, but location $this has unsaved field $fieldDescriptor" }
    val fieldDescriptorId = traceContext.getOrCreateFieldId(fieldDescriptor)
    out.writeLocationKind(AccessLocationKind.STATIC_FIELD)
    out.writeInt(fieldDescriptorId)
}

private fun ObjectFieldAccessLocation.save(out: TraceWriter, traceContext: TraceContext) {
    check(traceContext.hasFieldDescriptor(fieldDescriptor)) { "Access location references must be saved before-hand, but location $this has unsaved field $fieldDescriptor" }
    val fieldDescriptorId = traceContext.getOrCreateFieldId(fieldDescriptor)
    out.writeLocationKind(AccessLocationKind.OBJECT_FIELD)
    out.writeInt(fieldDescriptorId)
}

private fun ArrayElementByIndexAccessLocation.save(out: TraceWriter) {
    out.writeLocationKind(AccessLocationKind.ARRAY_ELEMENT_BY_INDEX)
    out.writeInt(index)
}

private fun ArrayElementByNameAccessLocation.save(out: TraceWriter, savingState: ContextSavingState) {
    // register or get existing access path and write its id to output stream
    val indexId = savingState.isAccessPathSaved(indexAccessPath)
    check(indexId > 0) { "Access paths saving order must guarantee that inner access paths are already saved" }
    out.writeLocationKind(AccessLocationKind.ARRAY_ELEMENT_BY_NAME)
    out.writeInt(indexId)
}


private fun LocalVariableAccessLocation.saveReferences(out: TraceWriter, traceContext: TraceContext) {
    val variableDescriptorId = traceContext.getOrCreateVariableId(variableDescriptor)
    out.writeVariableDescriptor(variableDescriptorId)
}

private fun StaticFieldAccessLocation.saveReferences(out: TraceWriter, traceContext: TraceContext) {
    val fieldDescriptorId = traceContext.getOrCreateFieldId(fieldDescriptor)
    out.writeFieldDescriptor(fieldDescriptorId)
}

private fun ObjectFieldAccessLocation.saveReferences(out: TraceWriter, traceContext: TraceContext) {
    val fieldDescriptorId = traceContext.getOrCreateFieldId(fieldDescriptor)
    out.writeFieldDescriptor(fieldDescriptorId)
}