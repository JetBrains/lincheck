/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.transformers

import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.descriptors.AccessPath
import org.jetbrains.lincheck.descriptors.OwnerName
import org.jetbrains.lincheck.util.isInLincheckPackage
import org.jetbrains.lincheck.util.isInTraceRecorderMode
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.Type
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.*
import sun.nio.ch.lincheck.*

/**
 * [MethodCallTransformerBase] tracks method calls,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal abstract class MethodCallTransformerBase(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, adapter, methodVisitor) {

    override val requiresOwnerNameAnalyzer: Boolean = true

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        // TODO: do not ignore <init> for lincheck managed strategy
        if ((name == "<init>" && !isInTraceRecorderMode) || isIgnoredMethod(className = owner)) {
            super.visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }
        if (isCoroutineInternalClass(owner.toCanonicalClassName())) {
            invokeInsideIgnoredSection {
                super.visitMethodInsn(opcode, owner, name, desc, itf)
            }
            return
        }
        if (isCoroutineResumptionSyntheticAccessor(owner, name)) {
            super.visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }
        // It is useless for the user, and it depends on static initialization which is not instrumented.
        if (isThreadLocalRandomCurrent(owner, name)) {
            super.visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }
        invokeIfInAnalyzedCode(
            original = {
                super.visitMethodInsn(opcode, owner, name, desc, itf)
            },
            instrumented = {
                processMethodCall(desc, opcode, owner, name, itf)
            }
        )
    }

    private fun isThreadLocalRandomCurrent(owner: String, methodName: String): Boolean {
        return owner == "java/util/concurrent/ThreadLocalRandom" && methodName == "current"
    }

    protected abstract fun processMethodCall(desc: String, opcode: Int, owner: String, name: String, itf: Boolean)

    protected fun GeneratorAdapter.pushReceiver(receiverLocal: Int?) {
        // STACK: <empty>
        if (receiverLocal != null) {
            loadLocal(receiverLocal)
        } else {
            pushNull()
        }
        // STACK: receiver?
    }

    protected fun GeneratorAdapter.processMethodCallEnter(
        methodId: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int,
        ownerName: OwnerName? = null,
        argumentNames: List<AccessPath?>? = null,
    ) {
        // STACK: <empty>
        loadNewCodeLocationId(accessPath = ownerName, argumentNames = argumentNames)
        // STACK: codeLocation
        push(methodId)
        pushReceiver(receiverLocal)
        loadLocal(argumentsArrayLocal)
        // STACK: codeLocation, methodId, receiver?, argumentsArray
        invokeStatic(Injections::onMethodCall)
        // STACK: deterministicCallDescriptor
        invokeBeforeEventIfPluginEnabled("method call ${this@MethodCallTransformerBase.methodName}")
    }

    protected fun GeneratorAdapter.processMethodCallReturn(
        returnType: Type,
        deterministicCallIdLocal: Int?,
        deterministicMethodDescriptorLocal: Int?,
        methodId: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int
    ) {
        // STACK: result?
        val resultLocal = when {
            (returnType == VOID_TYPE) -> null
            else -> newLocal(returnType).also { storeLocal(it) }
        }
        if (deterministicCallIdLocal != null) {
            loadLocal(deterministicCallIdLocal)
        } else {
            push(0L)
        }
        if (deterministicMethodDescriptorLocal != null) {
            loadLocal(deterministicMethodDescriptorLocal)
        } else {
            pushNull()
        }
        push(methodId)
        pushReceiver(receiverLocal)
        loadLocal(argumentsArrayLocal)
        resultLocal?.let {
            loadLocal(it)
            box(returnType)
        }
        // STACK: deterministicCallId, deterministicMethodDescriptor, methodId, receiver, arguments, result?
        when {
            returnType == VOID_TYPE -> invokeStatic(Injections::onMethodCallReturnVoid)
            else                    -> {
                invokeStatic(Injections::onMethodCallReturn)
                // STACK: boxedResult
                unbox(returnType)
                // STACK: result
            }
        }
        // STACK: result?
    }

    protected fun GeneratorAdapter.processMethodCallException(
        deterministicCallIdLocal: Int?,
        deterministicMethodDescriptorLocal: Int?,
        methodId: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int,
    ) {
        // STACK: exception
        val exceptionLocal = newLocal(THROWABLE_TYPE)
        storeLocal(exceptionLocal)
        // STACK: <empty>
        if (deterministicCallIdLocal != null) {
            loadLocal(deterministicCallIdLocal)
        } else {
            push(0L)
        }
        if (deterministicMethodDescriptorLocal != null) {
            loadLocal(deterministicMethodDescriptorLocal)
        } else {
            pushNull()
        }
        push(methodId)
        pushReceiver(receiverLocal)
        loadLocal(argumentsArrayLocal)
        loadLocal(exceptionLocal)
        // STACK: deterministicCallId, deterministicMethodDescriptor, methodId, receiver, params, exception
        invokeStatic(Injections::onMethodCallException)
        // STACK: Throwable
        throwException()
    }

    protected fun getOwnerName(desc: String, opcode: Int): AccessPath? {
        val stack = ownerNameAnalyzer?.stack ?: return null
        if (opcode == INVOKESTATIC) return null
        val position = getArgumentTypes(desc).sumOf { it.size }
        return stack.getStackElementAt(position)
    }

    protected fun getArgumentNames(desc: String, opcode: Int): List<AccessPath?>? {
        val stack = ownerNameAnalyzer?.stack ?: return null
        var position = 0
        val argumentTypes = getArgumentTypes(desc)
        return argumentTypes.reversed().map { argType ->
            val argPath = stack.getStackElementAt(position)
            position += argType.size
            argPath
        }.reversed()
    }

    protected fun isIgnoredMethod(className: String) =
        isInLincheckPackage(className.toCanonicalClassName()) ||
        className == "kotlin/jvm/internal/Intrinsics" ||
        className == "java/util/Objects" ||
        className == "java/lang/String" ||
        className == "java/lang/Boolean" ||
        className == "java/lang/Number" ||
        className == "java/lang/Long" ||
        className == "java/lang/Integer" ||
        className == "java/lang/Short" ||
        className == "java/lang/Byte" ||
        className == "java/lang/Double" ||
        className == "java/lang/Float" ||
        className == "java/util/Locale" ||
        className == "org/slf4j/helpers/Util" ||
        className == "java/util/Properties" ||
        className == "java/lang/invoke/MethodHandles"

    @Suppress("UNUSED_PARAMETER")
    protected fun isCoroutineResumptionSyntheticAccessor(className: String, methodName: String): Boolean =
        (this.methodName == "invokeSuspend") && methodName.startsWith("access\$")

    protected fun sanitizeMethodName(
        owner: String, originalName: String, instrumentationMode: InstrumentationMode
    ): String {
        fun callRecursive(originalName: String) = sanitizeMethodName(owner, originalName, instrumentationMode)

        fun endsWithModuleName(): Boolean {
            val tail = originalName.substringAfterLast("$", "")
            if (tail.isEmpty()) return false
            val sourceSetName = tail.substringAfterLast('_', "")
            if (sourceSetName.isEmpty()) return false
            return sourceSetName == "main" || sourceSetName == "test" ||
                    sourceSetName.endsWith("Main") || sourceSetName.endsWith("Test")
        }
        return when (originalName) {
            "constructor-impl", "box-impl", "unbox-impl" -> originalName
            else if originalName.startsWith("access$") -> {
                val base = originalName.removePrefix("access$")
                if (originalName.endsWith($$"$p") || originalName.endsWith($$"$cp")) {
                    callRecursive(base.removeSuffix($$"$p").removeSuffix($$"$cp"))
                } else {
                    // will be excluded further by postprocessor
                    $$"access$$${callRecursive(base)}"
                }
            }
            else if originalName.endsWith($$"$default") -> {
                val base = callRecursive(originalName.removeSuffix($$"$default"))
                if (LincheckClassFileTransformer.shouldTransform(owner.toCanonicalClassName(), instrumentationMode)) {
                    // will be excluded further by postprocessor
                    $$"$$base$default"
                } else {
                    base
                }
            }
            else if originalName.contains("-") -> callRecursive(originalName.substringBeforeLast('-'))
            else if originalName.contains("$") && endsWithModuleName() -> callRecursive(originalName.substringBeforeLast('$'))
            else -> originalName
        }
    }
}
