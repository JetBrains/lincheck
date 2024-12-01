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
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import sun.nio.ch.lincheck.Injections
import java.lang.invoke.CallSite
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType


internal class InvokeDynamicTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
    override fun visitInvokeDynamicInsn(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Handle,
        vararg bootstrapMethodArguments: Any?
    ) = adapter.run {
        invokeIfInTestingCode(
            original = { visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments) },
        ) {
            val arguments = storeArguments(descriptor)
            
            // InvokeDynamic execution consists of the following steps:
            // 1. Calling bootstrap method, which creates the actual function to call later;
            // 2. Caching it by JVM;
            // 3. Calling it by JVM.
            // On the subsequent runs, JVM uses cached function.
            
            // The current implementation performs these steps manually each time.
            // Once native calls are supported, it would be possible to cache `MethodHandle`s creation.
            
            // Bootstrap method is a function, which creates the actual call-site.
            // Its first three parameters are predefined and are supplied by JVM when it invokes `invokedynamic`.
            // After those parameters, there are regular parameters.
            // Also, there can be `vararg` parameters.
            
            // (predefined, predefined, predefined, regular, regular, [Ljava/lang/Object; <- is vararg)
            // vararg parameter might exist or not
            
            // https://openjdk.org/jeps/309
            // https://www.infoq.com/articles/Invokedynamic-Javas-secret-weapon/
            // https://www.baeldung.com/java-string-concatenation-invoke-dynamic
            
            val bootstrapMethodParameterTypes = Type.getArgumentTypes(bootstrapMethodHandle.desc)
            require(bootstrapMethodParameterTypes[0] == Type.getType(MethodHandles.Lookup::class.java))
            require(bootstrapMethodParameterTypes[1] == Type.getType(String::class.java))
            require(bootstrapMethodParameterTypes[2] == Type.getType(MethodType::class.java))
            
            // pushing predefined arguments manually
            invokeStatic(MethodHandles::lookup)
            visitLdcInsn(name)
            visitLdcInsn(Type.getMethodType(descriptor))
            val jvmPredefinedParametersCount = 3
            
            val isMethodWithVarArgs = isMethodVarArgs(bootstrapMethodHandle)
            val notVarargsArgumentsCount = bootstrapMethodParameterTypes.size - jvmPredefinedParametersCount - (if (isMethodWithVarArgs) 1 else 0)
            val varargsArgumentsCount = bootstrapMethodArguments.size - notVarargsArgumentsCount
            // adding regular arguments
            for (arg in bootstrapMethodArguments.take(notVarargsArgumentsCount)) {
                visitLdcInsn(arg)
            }
            // adding vararg
            if (isMethodWithVarArgs) {
                val varargArguments = bootstrapMethodArguments.takeLast(varargsArgumentsCount)
                visitLdcInsn(varargsArgumentsCount)
                // [Ljava/lang/Object; -> Ljava/lang/Object;, [[Ljava/lang/Object; -> [Ljava/lang/Object;
                val arrayElementType = bootstrapMethodParameterTypes.last().descriptor.drop(1).let(Type::getType)
                newArray(arrayElementType)
                for ((i, arg) in varargArguments.withIndex()) {
                    dup()
                    visitLdcInsn(i)
                    visitLdcInsn(arg)
                    if (arg != null && arrayElementType.sort == Type.OBJECT) {
                        box(Type.getType(arg.javaClass))
                    }
                    arrayStore(arrayElementType)
                }
            }
            
            // creating java.lang.invoke.MethodHandle manually
            invokeInIgnoredSection {
                visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    bootstrapMethodHandle.owner,
                    bootstrapMethodHandle.name,
                    bootstrapMethodHandle.desc,
                    bootstrapMethodHandle.isInterface
                )
                invokeVirtual(
                    Type.getType(CallSite::class.java),
                    Method.getMethod(CallSite::class.java.getMethod("dynamicInvoker"))
                )
            }
            // todo cache all above when native calls are available
            loadLocals(arguments)
            advancingCounter {
                visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", descriptor, false
                )
            }
        }
    }

    private fun isMethodVarArgs(
        bootstrapMethodHandle: Handle,
    ): Boolean {
        val ownerClassName = bootstrapMethodHandle.owner.replace('/', '.')
        val methods = Class.forName(ownerClassName).declaredMethods
        return methods.single { Type.getMethodDescriptor(it) == bootstrapMethodHandle.desc }.isVarArgs
    }

    private fun GeneratorAdapter.advancingCounter(code: GeneratorAdapter.() -> Unit) {
        invokeStatic(Injections::getNextObjectId)
        val oldId = newLocal(Type.LONG_TYPE)
        storeLocal(oldId)
        tryCatchFinally(
            tryBlock = code,
            finallyBlock = {
                loadLocal(oldId)
                invokeStatic(Injections::advanceCurrentObjectIdWithKnownOldObjectId)
            }
        )
    }
}
