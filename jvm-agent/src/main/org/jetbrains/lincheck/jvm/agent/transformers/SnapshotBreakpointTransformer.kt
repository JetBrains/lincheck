/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.transformers

import org.jetbrains.lincheck.jvm.agent.LincheckMethodVisitor
import org.jetbrains.lincheck.jvm.agent.LiveDebuggerSettings
import org.jetbrains.lincheck.jvm.agent.MethodInformation
import org.jetbrains.lincheck.jvm.agent.invokeStatic
import org.jetbrains.lincheck.trace.TraceContext
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.GeneratorAdapter
import sun.nio.ch.lincheck.Injections

internal class SnapshotBreakpointTransformer(
    fileName: String, 
    className: String, 
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    context: TraceContext,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
    private val liveDebuggerSettings: LiveDebuggerSettings
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, context, adapter, methodVisitor) {
    
    override fun visitLineNumber(line: Int, start: Label) {
        super.visitLineNumber(line, start)
        if (liveDebuggerSettings.breakPoints.any { it.lineNumber == line && it.fileName == fileName} ) {
            adapter.invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
            loadNewCodeLocationId()
            adapter.invokeStatic(Injections::onSnapshotBreakpoint)
        }
    }
}