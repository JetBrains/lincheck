/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.jetbrains.kotlinx.lincheck.TimeTravellingInjections
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.GeneratorAdapter

class TimeTravelClassVisitor(
    classVisitor: ClassVisitor,
    private val classUnderTimeTravel: String,
    private val methodUnderTimeTravel: String
): ClassVisitor(ASM_API, classVisitor) {
    private lateinit var className: String

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String,
        interfaces: Array<String>
    ) {
        className = name
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitMethod(
        access: Int,
        methodName: String,
        desc: String,
        signature: String?,
        exceptions: Array<String>?
    ): MethodVisitor {
        fun MethodVisitor.newAdapter() = GeneratorAdapter(this, access, methodName, desc)

        var mv = super.visitMethod(access, methodName, desc, signature, exceptions)
        if (className == classUnderTimeTravel && methodName == methodUnderTimeTravel) {
            mv = JUnitTestMethodTransformer(methodUnderTimeTravel, mv.newAdapter())
        }

        return mv
    }
}

/**
 * Wraps junit methods marked with `@Test` in order to set up Lincheck in time-travelling mode.
 */
private class JUnitTestMethodTransformer(
    private val methodUnderTimeTravel: String,
    private val adapter: GeneratorAdapter
) : MethodVisitor(ASM_API, adapter) {

    /**
     * This method is called for the target class and method that require time-travelling functionality.
     */
    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        ifStatement(
            condition = {
                invokeStatic(TimeTravellingInjections::isFirstRun)
            },
            ifClause = {
                loadThis()
                push(methodUnderTimeTravel)
                // STACK: testInstance, currentTestMethod
                invokeStatic(TimeTravellingInjections::runWithLincheck)
            },
            elseClause = {
                visitMethodInsn(opcode, owner, name, desc, itf)
            }
        )
    }
}