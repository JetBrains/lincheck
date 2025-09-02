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
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.Injections.HandlePojo
import sun.nio.ch.lincheck.TraceDebuggerTracker
import java.lang.invoke.CallSite
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType


/**
 * A class that deterministically emulates the behavior of `invokedynamic` bytecode instruction.
 * It extends the `ManagedStrategyMethodVisitor` to provide specific transformations and augmentations
 * for managing and executing dynamic invocation instructions. The implementation ensures deterministic
 * behavior especially in the context of testing or debugging.
 *
 * @param fileName The name of the file containing the class being visited.
 * @param className The name of the class being visited.
 * @param methodName The name of the method being visited.
 * @param adapter The `GeneratorAdapter` used to modify and emit bytecode instructions.
 */
internal class DeterministicInvokeDynamicTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    metaInfo: MethodInformation,
    private val classVersion: Int,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, metaInfo, adapter, methodVisitor) {

    override fun visitInvokeDynamicInsn(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Handle,
        vararg bootstrapMethodArguments: Any?
    ) = adapter.run {
        invokeIfInAnalyzedCode(
            original = {
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
            },
            instrumented = {
                // Emulating INVOKE_DYNAMIC behavior deterministically

                val arguments = storeArguments(descriptor)

                getOrPutCallSiteForInvokeDynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments)
                getCallSiteTarget()

                loadLocals(arguments)
                invokeMethodHandle(descriptor)
            }
        )
    }

    private fun GeneratorAdapter.invokeMethodHandle(descriptor: String) {
        advancingCounter {
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", descriptor, false)
        }
    }

    private fun GeneratorAdapter.getOrPutCallSiteForInvokeDynamic(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Handle,
        bootstrapMethodArguments: Array<out Any?>
    ) {
        // The key for Map.get(key)
        putInvokeDynamicCallStaticArgumentsOnStack(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments)

        // Map.get(key)
        invokeInsideIgnoredSection {
            invokeStatic(Injections::getCachedInvokeDynamicCallSite)
        }

        // On null value compute and cache the call site
        dup()
        advancingCounter {
            val onCallSite = newLabel()
            ifNonNull(onCallSite)
            pop()
            computeAndCacheInvokeDynamicCallSite(bootstrapMethodHandle, name, descriptor, bootstrapMethodArguments)
            visitLabel(onCallSite)
        }
    }

    private fun GeneratorAdapter.computeAndCacheInvokeDynamicCallSite(
        bootstrapMethodHandle: Handle,
        name: String,
        descriptor: String,
        bootstrapMethodArguments: Array<out Any?>
    ) {
        // The value to return
        createCallSiteManuallyWithMetafactory(bootstrapMethodHandle, name, descriptor, bootstrapMethodArguments)
        val callSite = newLocal(callSiteType)
        storeLocal(callSite)
        
        // The key for Map.set(key, value)
        putInvokeDynamicCallStaticArgumentsOnStack(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments)
        
        // The value for Map.set(key, value)
        loadLocal(callSite)
        
        // Map.set(key, value)
        invokeInsideIgnoredSection {
            invokeStatic(Injections::putCachedInvokeDynamicCallSite)
        }
        
        loadLocal(callSite)
    }

    private fun GeneratorAdapter.putInvokeDynamicCallStaticArgumentsOnStack(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Handle,
        bootstrapMethodArguments: Array<out Any?>
    ) {
        visitLdcInsn(name)
        visitLdcInsn(descriptor)
        createHandlePojo(bootstrapMethodHandle)

        visitLdcInsn(bootstrapMethodArguments.size)
        newArray(anyType)
        for ((i, arg) in bootstrapMethodArguments.withIndex()) {
            dup()
            visitLdcInsn(i)
            visitLdcInsn(arg)
            if (arg != null) {
                box(Type.getType(arg.javaClass))
            }
            arrayStore(anyType)
        }
    }

    private fun GeneratorAdapter.createHandlePojo(bootstrapMethodHandle: Handle) {
        newInstance(handlePojoType)
        dup()
        visitLdcInsn(bootstrapMethodHandle.tag)
        visitLdcInsn(bootstrapMethodHandle.owner)
        visitLdcInsn(bootstrapMethodHandle.name)
        visitLdcInsn(bootstrapMethodHandle.desc)
        visitLdcInsn(bootstrapMethodHandle.isInterface)
        invokeConstructor(handlePojoType, handlePojoConstructor)
    }

    private fun GeneratorAdapter.createCallSiteManuallyWithMetafactory(
        bootstrapMethodHandle: Handle,
        name: String,
        descriptor: String,
        bootstrapMethodArguments: Array<out Any?>
    ) {
        putStackArgumentsForMetafactory(bootstrapMethodHandle, name, descriptor, bootstrapMethodArguments)
        invokeInsideIgnoredSection {
            visitMethodInsn(
                INVOKESTATIC,
                bootstrapMethodHandle.owner,
                bootstrapMethodHandle.name,
                bootstrapMethodHandle.desc,
                bootstrapMethodHandle.isInterface
            )
        }
    }
    
    private fun GeneratorAdapter.getCallSiteTarget() = invokeInsideIgnoredSection {
        invokeVirtual(callSiteType, callSiteTargetMethod)
    }

    private fun GeneratorAdapter.putStackArgumentsForMetafactory(
        bootstrapMethodHandle: Handle,
        name: String,
        descriptor: String,
        bootstrapMethodArguments: Array<out Any?>
    ) {
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
        require(bootstrapMethodParameterTypes[0] == methodHandlesLookupType)
        require(bootstrapMethodParameterTypes[1] == STRING_TYPE)
        require(bootstrapMethodParameterTypes[2] == methodTypeType)

        // pushing predefined arguments manually
        if (classVersion == 52 /* Java 8 */) {
            invokeInsideIgnoredSection {
                push(this@DeterministicInvokeDynamicTransformer.className.replace('/', '.'))
                invokeStatic(Injections::getClassForNameOrNull)
                dup()
                val endLabel = newLabel()
                val ifNonNullLabel = newLabel()
                ifNonNull(ifNonNullLabel)
                pop()
                invokeStatic(MethodHandles::lookup)
                goTo(endLabel)
                visitLabel(ifNonNullLabel)
                invokeStatic(Injections::trustedLookup)
                visitLabel(endLabel)
            }
        } else {
            invokeStatic(MethodHandles::lookup)
        }
        visitLdcInsn(name)
        visitLdcInsn(Type.getMethodType(descriptor))
        val jvmPredefinedParametersCount = 3

        val isMethodWithVarArgs = isMethodVarArgs(bootstrapMethodHandle)
        val notVarargsArgumentsCount =
            bootstrapMethodParameterTypes.size - jvmPredefinedParametersCount - (if (isMethodWithVarArgs) 1 else 0)
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
    }

    private fun isMethodVarArgs(
        bootstrapMethodHandle: Handle,
    ): Boolean {
        val ownerClassName = bootstrapMethodHandle.owner.replace('/', '.')
        // Calling Class.forName is considered safe here
        // because bootstrap methods calling themselves with invoke dynamic seem unpractical.
        val methods = Class.forName(ownerClassName).declaredMethods
        return methods.single { Type.getMethodDescriptor(it) == bootstrapMethodHandle.desc }.isVarArgs
    }

    // TODO: Investigate whether it is possible to refactor this code to remove advanceCurrentObjectId from 
    //  the event tracker API and solve the problem with the ignored sections instead.
    private fun GeneratorAdapter.advancingCounter(code: GeneratorAdapter.() -> Unit) {
        val trackers = TraceDebuggerTracker.entries
        val oldIds = trackers.map { tracker ->
            getStatic(trackerEnumType, tracker.name, trackerEnumType)
            invokeStatic(Injections::getNextTraceDebuggerEventTrackerId)
            val oldId = newLocal(Type.LONG_TYPE)
            storeLocal(oldId)
            oldId
        }
        tryCatchFinally(
            tryBlock = code,
            finallyBlock = {
                for ((index, tracker) in trackers.withIndex()) {
                    getStatic(trackerEnumType, tracker.name, trackerEnumType)
                    loadLocal(oldIds[index])
                    invokeStatic(Injections::advanceCurrentTraceDebuggerEventTrackerId)
                }
            }
        )
    }

    companion object {
        private val trackerEnumType = Type.getType(TraceDebuggerTracker::class.java)
        private val callSiteType = Type.getType(CallSite::class.java)
        private val handlePojoType = Type.getType(HandlePojo::class.java)
        private val anyType = Type.getType(Any::class.java)
        private val methodTypeType = Type.getType(MethodType::class.java)
        private val methodHandlesLookupType = Type.getType(MethodHandles.Lookup::class.java)
        private val callSiteTargetMethod = Method.getMethod(CallSite::class.java.getMethod("getTarget"))
        private val handlePojoConstructor = Method.getMethod(
            HandlePojo::class.java.getDeclaredConstructor(
                Int::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Boolean::class.java
            )
        )
    }
}
