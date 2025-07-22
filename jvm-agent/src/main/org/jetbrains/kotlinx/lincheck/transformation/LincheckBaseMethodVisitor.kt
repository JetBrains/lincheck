/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.jetbrains.lincheck.descriptors.AccessPath
import org.jetbrains.lincheck.descriptors.CodeLocations
import org.jetbrains.lincheck.util.ideaPluginEnabled
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.GeneratorAdapter

internal open class LincheckBaseMethodVisitor(
    protected val fileName: String,
    protected val className: String,
    protected val methodName: String,
    val adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor
) : MethodVisitor(ASM_API, methodVisitor) {
    private var lineNumber = 0

    /**
     * Injects `beforeEvent` method invocation if IDEA plugin is enabled.
     *
     * @param type type of the event, needed just for debugging.
     */
    protected fun invokeBeforeEventIfPluginEnabled(type: String) = adapter.run {
        if (ideaPluginEnabled) {
            invokeBeforeEvent(type)
        }
    }

    protected fun loadNewCodeLocationId() = adapter.run {
        val stackTraceElement = StackTraceElement(className, methodName, fileName, lineNumber)
        val codeLocationId = CodeLocations.newCodeLocation(stackTraceElement)
        push(codeLocationId)
    }

    protected fun isKnownLineNumber(): Boolean =
        lineNumber > 0

    override fun visitLineNumber(line: Int, start: Label) {
        lineNumber = line
        super.visitLineNumber(line, start)
    }
}