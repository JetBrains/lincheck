/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck_custom

import org.jetbrains.kotlinx.lincheck_custom.TransformationClassLoader.*
import org.jetbrains.kotlinx.lincheck_custom.runner.FixedActiveThreadsExecutor
import org.jetbrains.kotlinx.lincheck_custom.strategy.managed.*
import org.objectweb.asm.*
import org.objectweb.asm.commons.*
import kotlin.reflect.jvm.*

internal class CancellabilitySupportClassTransformer(cv: ClassVisitor) : ClassVisitor(ASM_API, cv) {
    override fun visitMethod(access: Int, methodName: String?, desc: String?, signature: String?, exceptions: Array<String>?): MethodVisitor {
        var mv = super.visitMethod(access, methodName, desc, signature, exceptions)
        mv = CancellabilitySupportMethodTransformer(access, methodName, desc, mv)
        return mv
    }
}

internal class CancellabilityAndParkUnparkSupportClassTransformer(cv: ClassVisitor) : ClassVisitor(ASM_API, cv) {
    override fun visitMethod(access: Int, methodName: String?, desc: String?, signature: String?, exceptions: Array<String>?): MethodVisitor {
        var mv = super.visitMethod(access, methodName, desc, signature, exceptions)
        mv = CancellabilitySupportMethodTransformer(access, methodName, desc, mv)
        mv = ParkUnparkTransformer(access, methodName, desc, mv)
        return mv
    }
}

private class ParkUnparkTransformer(access: Int, methodName: String?, desc: String?, mv: MethodVisitor) :
    AdviceAdapter(ASM_API, mv, access, methodName, desc)
{
    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
        val isPark = owner.isUnsafe() && name == "park"
        if (isPark) invokeBeforePark()
        val isUnpark = owner.isUnsafe() && name == "unpark"
        if (isUnpark) {
            dup2()
        }
        super.visitMethodInsn(opcode, owner, name, desc, itf)
        if (isUnpark) {
            invokeAfterUnpark()
            pop()
        }
    }

    // STACK: withTimeout
    private fun invokeBeforePark() {
        invokeStatic(parkUnparkRunnerType, beforeParkMethod)
    }

    // STACK: thread
    private fun invokeAfterUnpark() {
        invokeStatic(parkUnparkRunnerType, afterUnparkMethod)
    }
}

private class CancellabilitySupportMethodTransformer(access: Int, methodName: String?, desc: String?, mv: MethodVisitor)
    : AdviceAdapter(ASM_API, mv, access, methodName, desc)
{
    override fun visitMethodInsn(opcodeAndSource: Int, className: String?, methodName: String?, descriptor: String?, isInterface: Boolean) {
        val isGetResult = ("kotlinx/coroutines/CancellableContinuation" == className || "kotlinx/coroutines/CancellableContinuationImpl" == className)
                          && "getResult" == methodName
        if (isGetResult) {
            this.dup()
            this.invokeStatic(storeCancellableContOwnerType, storeCancellableContMethod)
        }
        super.visitMethodInsn(opcodeAndSource, className, methodName, descriptor, isInterface)
    }
}

internal object ParkUnparkRunner {
    @JvmStatic
    fun beforePark() {
        (Thread.currentThread() as FixedActiveThreadsExecutor.TestThread).beforePark()
    }

    @JvmStatic
    fun afterUnpark(thread: Any) {
        (Thread.currentThread() as FixedActiveThreadsExecutor.TestThread).afterUnpark(thread)
    }
}

private val storeCancellableContMethod = Method.getMethod(::storeCancellableContinuation.javaMethod)
private val storeCancellableContOwnerType = Type.getType(::storeCancellableContinuation.javaMethod!!.declaringClass)

private val parkUnparkRunnerType = Type.getType(ParkUnparkRunner::class.java)
private val beforeParkMethod = Method.getMethod(ParkUnparkRunner::beforePark.javaMethod)
private val afterUnparkMethod = Method.getMethod(ParkUnparkRunner::afterUnpark.javaMethod)

private val OBJECT_TYPE = Type.getType(Any::class.java)
