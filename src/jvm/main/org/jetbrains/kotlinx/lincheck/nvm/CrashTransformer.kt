/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.nvm

import org.jetbrains.kotlinx.lincheck.TransformationClassLoader.ASM_API
import org.jetbrains.kotlinx.lincheck.annotations.CrashFree
import org.objectweb.asm.*
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import kotlin.reflect.jvm.javaMethod

/**
 * This transformer checks if crashes for a class/method are enabled.
 * @see CrashFree
 */
internal open class CrashEnabledVisitor(cv: ClassVisitor, initial: Boolean = true) :
    ClassVisitor(ASM_API, cv) {
    var shouldTransform = initial
        private set
    var name: String? = null
        private set
    var fileName: String? = null
        private set

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        this.name = name
    }

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        if (descriptor == CRASH_FREE_TYPE) {
            shouldTransform = false
        }
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitSource(source: String?, debug: String?) {
        super.visitSource(source, debug)
        fileName = source
    }
}

/** Insert crashes in stress testing mode. */
internal class CrashTransformer(cv: ClassVisitor) : CrashEnabledVisitor(cv) {
    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (!shouldTransform) return mv
        return CrashMethodTransformer(GeneratorAdapter(mv, access, name, descriptor), this.name, fileName)
    }
}

/**
 * Add crashes to a method.
 * Crashes are inserted before return instructions, writes to fields and nvm primitives operations.
 */
private class CrashMethodTransformer(
    private val adapter: GeneratorAdapter,
    private val className: String?,
    private val fileName: String?
) : MethodVisitor(ASM_API, adapter) {
    private var shouldTransform = adapter.name != "<clinit>" && (adapter.access and Opcodes.ACC_BRIDGE) == 0
    private var lineNumber = -1
    private var superConstructorCalled = adapter.name != "<init>"

    private fun callCrash() = adapter.run {
        if (!shouldTransform || !superConstructorCalled) return
        push(className)
        push(fileName)
        push(name)
        push(lineNumber)
        invokeStatic(NVM_STATE_HOLDER_TYPE, POSSIBLY_CRASH_METHOD)
    }

    override fun visitLineNumber(line: Int, start: Label?) {
        super.visitLineNumber(line, start)
        lineNumber = line
    }

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        if (descriptor == CRASH_FREE_TYPE) {
            shouldTransform = false
        }
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        if (!superConstructorCalled && opcode == Opcodes.INVOKESPECIAL) {
            superConstructorCalled = true
        }
        if (owner !== null && owner.startsWith("org/jetbrains/kotlinx/lincheck/nvm/api/")) {
            callCrash()
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) {
        if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
            callCrash()
        }
        super.visitFieldInsn(opcode, owner, name, desc)
    }

    override fun visitInsn(opcode: Int) {
        if (opcode in returnInstructions || opcode in storeInstructions) {
            callCrash()
        }
        super.visitInsn(opcode)
    }
}

/** This transformer does not let user code to catch [CrashError] exception (even as [Throwable]) and handle it. */
internal class CrashRethrowTransformer(cv: ClassVisitor) : ClassVisitor(ASM_API, cv) {
    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        val adapter = GeneratorAdapter(mv, access, name, descriptor)
        return object : MethodVisitor(ASM_API, mv) {
            private val catchLabels = hashSetOf<Label>()

            override fun visitTryCatchBlock(start: Label?, end: Label?, handler: Label?, type: String?) {
                super.visitTryCatchBlock(start, end, handler, type)
                check(type != CRASH_ERROR_TYPE.internalName) { "Catch CrashError is prohibited." }
                if (type == THROWABLE_TYPE.internalName && handler !== null) {
                    catchLabels.add(handler)
                }
            }

            override fun visitLabel(label: Label?) {
                super.visitLabel(label)
                if (label !in catchLabels) return
                adapter.run {
                    val continueCatch = newLabel()
                    dup()
                    instanceOf(CRASH_ERROR_TYPE)
                    ifZCmp(GeneratorAdapter.EQ, continueCatch)
                    throwException()
                    mark(continueCatch)
                }
            }
        }
    }
}

private val storeInstructions = Opcodes.IASTORE..Opcodes.SASTORE
private val returnInstructions = Opcodes.IRETURN..Opcodes.RETURN

private val CRASH_ERROR_TYPE = Type.getType(CrashError::class.java)
private val THROWABLE_TYPE = Type.getType(Throwable::class.java)
private val NVM_STATE_HOLDER_TYPE = Type.getType(NVMStateHolder::class.java)
private val POSSIBLY_CRASH_METHOD = Method.getMethod(NVMStateHolder::possiblyCrash.javaMethod)
private val CRASH_FREE_TYPE = Type.getDescriptor(CrashFree::class.java)
