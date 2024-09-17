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
        super.visit(version, access, name, signature, superName, interfaces)
        println("Class name under observation: $name")
        className = name
    }

    override fun visitMethod(
        access: Int,
        methodName: String,
        desc: String,
        signature: String?,
        exceptions: Array<String>?
    ): MethodVisitor {
        fun MethodVisitor.newAdapter() = GeneratorAdapter(this, access, methodName, desc)
        println("Visiting method: $className::$methodName(...)")

        var mv = super.visitMethod(access, methodName, desc, signature, exceptions)
        if (className == classUnderTimeTravel && methodName == methodUnderTimeTravel) {
            println("Inside a visit junit-method")
            mv = JUnitTestMethodTransformer(classUnderTimeTravel, methodUnderTimeTravel, mv.newAdapter())
        }

        return mv
    }
}

/**
 * Wraps junit methods marked with `@Test` in order to set up Lincheck in time-travelling mode.
 */
private class JUnitTestMethodTransformer(
    private val classUnderTimeTravel: String,
    private val methodUnderTimeTravel: String,
    private val adapter: GeneratorAdapter
) : MethodVisitor(ASM_API, adapter) {

    /**
     * This method is called for the target class and method that require time-travelling functionality.
     */
    override fun visitCode() = adapter.run {
        ifStatement(
            condition = {
                invokeStatic(TimeTravellingInjections::isFirstRun)
            },
            ifClause = {
                push(classUnderTimeTravel)
                push(methodUnderTimeTravel)
                // STACK: testClassName, testMethodName
                invokeStatic(TimeTravellingInjections::runWithLincheck)
            },
            elseClause = {
                visitCode()
            }
        )
    }
}