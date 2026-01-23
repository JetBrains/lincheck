/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.transformers

import org.objectweb.asm.commons.GeneratorAdapter
import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.trace.TraceContext
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import sun.nio.ch.lincheck.*
import sun.misc.Unsafe

/**
 * [CatchBlockStartTransformer] tracks all starts of `catch` blocks
 */
internal class CatchBlockStartTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    context: TraceContext,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, context, adapter, methodVisitor) {

    override fun visitLabel(label: Label)  = adapter.run {
        super.visitLabel(label)
        if (!methodInfo.labels.isCatchTarget(label)) return

        // Stack: exception
        dup()
        // Stack: exception, exception
        invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
        // Stack: exception, exception, threadDescriptor
        swap()
        // Stack: exception, threadDescriptor, exception
        loadNewCodeLocationId()
        // Stack: exception, threadDescriptor, exception, locationId
        swap()
        // Stack: exception, threadDescriptor, locationId, exception
        invokeStatic(Injections::onCatch)
    }
}
