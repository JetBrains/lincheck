/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation.transformers

import org.jetbrains.kotlinx.lincheck.transformation.*
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.GeneratorAdapter
import sun.nio.ch.lincheck.Injections

internal class ConstructorArgumentsSnapshotTrackerTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
    // `SafeClassWriter::isInstanceOf` method which checks the subclassing without loading the classes to VM
    private val isInstanceOf: (actualType: String, expectedSuperType: String) -> Boolean
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, adapter, methodVisitor) {

    /**
     * Searches for invocations of constructors `className::<init>(args)` and inserts bytecode
     * that will extract all objects from `args` which types are subtypes of the class `className`.
     * Snapshot tracker then tracks the extracted objects eagerly.
     *
     * This is a hack solution to the problem of impossibility of identifying whether
     * some object on stack in the constructor is a `this` reference or not.
     * So for instructions `GETFIELD`/`PUTFIELD` in constructors handler of `visitFieldInsn(...)` method below
     * we ignore the accesses to fields which owners are the same type as the constructor owner.
     * The optimization is done to avoid 'leaking this' problem during bytecode verification.
     * But sometimes `GETFIELD`/`PUTFIELD` are invoked on fields with owner objects are the same type as a constructor owner,
     * but which are not `this`. This can happen when:
     * - a non-static object is accessed which was passed to constructor arguments
     * - a non-static object is accessed which was created locally in constructor
     *
     * We only case about the 1nd case, because for the 2nd case when dealing with
     * a locally constructed object it can't be reached from static memory, so no need to track its fields.
     * The 2nd case where such object is passed via constructor argument is handled here.
     */
    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (name == "<init>") {
            val matchedArguments: Set<Int> = getArgumentTypes(desc)
                .mapIndexedNotNullTo(mutableSetOf()) { index, type ->
                    if (
                        !isArray(type) &&
                        !isPrimitive(type) &&
                        isInstanceOf(type.className.toInternalClassName(), owner)
                    ) index else null
                }

            if (matchedArguments.isEmpty()) {
                super.visitMethodInsn(opcode, owner, name, desc, itf)
                return
            }

            invokeIfInAnalyzedCode(
                original = {
                    super.visitMethodInsn(opcode, owner, name, desc, itf)
                },
                instrumented = {
                    // STACK: args
                    val arguments = storeArguments(desc)
                    val matchedLocals = arguments.filterIndexed { index, _ -> matchedArguments.contains(index) }.toIntArray()
                    // STACK: <empty>
                    pushArray(matchedLocals)
                    // STACK: array
                    invokeStatic(Injections::updateSnapshotBeforeConstructorCall)
                    // STACK: <empty>
                    loadLocals(arguments)
                    // STACK: args
                    visitMethodInsn(opcode, owner, name, desc, itf)
                }
            )
            return
        }

        super.visitMethodInsn(opcode, owner, name, desc, itf)
    }
}