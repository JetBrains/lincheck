/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.jetbrains.lincheck.descriptors.AccessPath
import org.jetbrains.lincheck.descriptors.CodeLocations
import org.jetbrains.lincheck.util.ideaPluginEnabled
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.AnalyzerAdapter
import org.objectweb.asm.commons.GeneratorAdapter

internal open class LincheckMethodVisitor(
    protected val fileName: String,
    protected val className: String,
    protected val methodName: String,
    protected val descriptor: String,
    protected val access: Int,
    protected val methodInfo: MethodInformation,
    val adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : MethodVisitor(ASM_API, methodVisitor) {
    private var lineNumber = 0

    lateinit var analyzer: AnalyzerAdapter

    var ownerNameAnalyzer: OwnerNameAnalyzerAdapter? = null

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

    protected fun loadNewCodeLocationId(accessPath: AccessPath? = null): Int = adapter.run {
        val mappedLocation = methodInfo.smap.getLine("Kotlin", lineNumber)
        val stackTraceElement = if (mappedLocation != null) {
            if (mappedLocation.className == className) {
                if (mappedLocation.line in methodInfo.lineRange.first .. methodInfo.lineRange.second) {
                    // See comment in `else` branch
                    StackTraceElement(
                        /* declaringClass = */ mappedLocation.className,
                        /* methodName = */ UNKNOWN_METHOD_MARKER, // methodName,
                        /* fileName = */ mappedLocation.sourceName,
                        /* lineNumber = */ mappedLocation.line
                    )
                } else {
                    // TODO: "Smart" behavior leads to flaky tests on TeamCity
                    //  Investigate, why.
                    //  Tests in question are:
                    //  - org.jetbrains.kotlinx.lincheck_test.representation.SuspendTraceReportingTest.test
                    //  - org.jetbrains.kotlinx.lincheck_test.representation.CoroutineCancellationTraceReportingTest.test
                    StackTraceElement(
                        /* declaringClass = */ mappedLocation.className,
                        /* methodName = */ UNKNOWN_METHOD_MARKER, // methodInfo.findMethodByLine(mappedLocation.line, methodName),
                        /* fileName = */ mappedLocation.sourceName,
                        /* lineNumber = */ mappedLocation.line
                    )
                }
            } else {
                // Reset method name, as it is other class or out of current method line range.
                StackTraceElement(
                    /* declaringClass = */ mappedLocation.className,
                    /* methodName = */ UNKNOWN_METHOD_MARKER,
                    /* fileName = */ mappedLocation.sourceName,
                    /* lineNumber = */ mappedLocation.line
                )
            }
        } else {
            StackTraceElement(
                /* declaringClass = */ className,
                /* methodName = */ methodName,
                /* fileName = */ fileName,
                /* lineNumber = */ lineNumber)
        }
        val codeLocationId = CodeLocations.newCodeLocation(stackTraceElement, accessPath)
        push(codeLocationId)
        return codeLocationId
    }

    protected fun isKnownLineNumber(): Boolean =
        lineNumber > 0

    override fun visitLineNumber(line: Int, start: Label) {
        lineNumber = line
        super.visitLineNumber(line, start)
    }
}