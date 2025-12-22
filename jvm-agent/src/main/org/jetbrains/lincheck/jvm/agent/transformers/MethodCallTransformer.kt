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
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.isThisAccess
import org.jetbrains.lincheck.util.isInLincheckPackage
import org.jetbrains.lincheck.util.isIntellijInstrumentationCoverageAgentClass
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.Type
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.*
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import sun.nio.ch.lincheck.*

/**
 * [MethodCallTransformer] tracks method calls,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class MethodCallTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    context: TraceContext,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
    val configuration: TransformationConfiguration,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, context, adapter, methodVisitor) {

    override val requiresOwnerNameAnalyzer: Boolean = true

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (!shouldTrackMethodCall(owner, name, desc)) {
            super.visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }
        // TODO: unify with `IgnoredSectionWrapperTransformer` ?
        if (isCoroutineInternalClass(owner.toCanonicalClassName())) {
            invokeInsideIgnoredSection {
                super.visitMethodInsn(opcode, owner, name, desc, itf)
            }
            return
        }

        processMethodCall(desc, opcode, owner, name, itf)
    }

    private fun GeneratorAdapter.processMethodCall(desc: String, opcode: Int, owner: String, name: String, itf: Boolean) {
        val isConstructorCall = (name == "<init>")
        val receiverType = getType("L$owner;")
        val argumentNames = getArgumentNames(desc, opcode)
        val ownerName = when {
            opcode == INVOKESTATIC && name.endsWith($$"$default") &&
                    argumentNames?.firstOrNull()?.locations?.singleOrNull()?.isThisAccess() == true -> argumentNames[0]
            else -> getOwnerName(desc, opcode)
        }

        // We assume that constructors return an object even though they don't
        // (because their descriptor specifies the 'void' return type).
        // Later 'processMethodCallReturn' takes this object and reports it as "return value" of a constructor.
        //
        // This assumption holds because the constructor invocation bytecode sequence looks as follows:
        // `NEW Clazz; DUP; push args; INVOKESPECIAL Clazz.<init>`.
        //
        // Before invoking the `<init>` method, the stack has the following shape:
        //   `STACK: this (uninitialized), this (uninitialized), args`
        // and after its invocation:
        //    `STACK: this (initialized)`.
        val returnType = if (isConstructorCall) receiverType else getReturnType(desc)

        // STACK: receiver?, arguments
        val argumentLocals = storeArguments(desc)
        val argumentsArrayLocal = newLocal(OBJECT_ARRAY_TYPE).also {
            pushArray(argumentLocals)
            storeLocal(it)
        }
        val receiverLocal = when {
            (opcode != INVOKESTATIC && !isConstructorCall) -> newLocal(receiverType).also { storeLocal(it) }
            else -> null
        }
        val sanitizedMethodName = sanitizeMethodName(owner, name, InstrumentationMode.TRACE_RECORDING)
        val methodId = context.getOrCreateMethodId(owner.toCanonicalClassName(), sanitizedMethodName, Types.convertAsmMethodType(desc))

        val threadDescriptorLocal = newLocal(OBJECT_TYPE).also {
            invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
            storeLocal(it)
        }

        val resultInterceptorLocal = newLocal(OBJECT_TYPE).also {
            invokeStatic(Injections::createResultInterceptor)
            storeLocal(it)
        }

        // STACK: <empty>
        processMethodCallEnter(methodId, receiverLocal, argumentsArrayLocal, ownerName, argumentNames, threadDescriptorLocal, resultInterceptorLocal)
        // STACK: <empty>
        tryCatchFinally(
            tryBlock = {
                // Stack <empty>
                ifStatement(
                    condition =  {
                        loadLocal(resultInterceptorLocal)
                        // Stack <resultInterceptor>
                        invokeStatic(Injections::isResultIntercepted)
                        // Stack <empty>
                    },
                    thenClause = {
                        // Stack <empty>
                        loadLocal(resultInterceptorLocal)
                        // Stack <resultInterceptor>
                        invokeStatic(Injections::getResultOrThrow)
                        // Stack <result>
                        if (returnType == VOID_TYPE) pop() else unbox(returnType)
                        // Stack <result?>
                    },
                    elseClause = {
                        // Stack <empty>
                        receiverLocal?.let { loadLocal(it) }
                        loadLocals(argumentLocals)
                        // STACK: receiver?, arguments
                        mv.visitMethodInsn(opcode, owner, name, desc, itf)
                        // Stack <result?>
                    },
                )
                // STACK: result?
                processMethodCallReturn(
                    returnType,
                    methodId,
                    receiverLocal,
                    argumentsArrayLocal,
                    threadDescriptorLocal,
                    resultInterceptorLocal,
                )
                // STACK: result?
            },
            catchBlock = {
                // STACK: exception
                dup()
                // STACK: exception, exception
                processMethodCallException(
                    methodId,
                    receiverLocal,
                    argumentsArrayLocal,
                    threadDescriptorLocal,
                    resultInterceptorLocal,
                )
                // STACK: exception
                throwException()
            }
        )
    }

    protected fun GeneratorAdapter.processMethodCallEnter(
        methodId: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int,
        ownerName: OwnerName?,
        argumentNames: List<AccessPath?>?,
        threadDescriptorLocal: Int,
        resultInterceptorLocal: Int,
    ) {
        // STACK: <empty>
        loadLocal(threadDescriptorLocal)
        // STACK: descriptor
        loadNewCodeLocationId(accessPath = ownerName, argumentNames = argumentNames)
        // STACK: descriptor, codeLocation
        push(methodId)
        pushReceiver(receiverLocal)
        loadLocal(argumentsArrayLocal)
        loadLocal(resultInterceptorLocal)
//        pushNull() // result interceptor

        // STACK: descriptor, codeLocation, methodId, receiver?, argumentsArray, interceptor?
        invokeStatic(Injections::onMethodCall)
        // STACK: deterministicCallDescriptor (NOTE: Isn't the stack empty here?)
        invokeBeforeEventIfPluginEnabled("method call ${this@MethodCallTransformer.methodName}")
    }

    protected fun GeneratorAdapter.processMethodCallReturn(
        returnType: Type,
        methodId: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int,
        threadDescriptorLocal: Int,
        resultInterceptorLocal: Int,
    ) {
        // STACK: result?
        val resultLocal = when {
            (returnType == VOID_TYPE) -> null
            else -> newLocal(returnType).also { storeLocal(it) }
        }
        loadLocal(threadDescriptorLocal)
        push(methodId)
        pushReceiver(receiverLocal)
        loadLocal(argumentsArrayLocal)
        resultLocal?.let {
            loadLocal(it)
            box(returnType)
        }
        loadLocal(resultInterceptorLocal)

        // STACK: descriptor, methodId, receiver, arguments, result?, interceptor?
        when {
            returnType == VOID_TYPE -> {
                invokeStatic(Injections::onMethodCallReturnVoid)
            }
            else -> {
                invokeStatic(Injections::onMethodCallReturn)
                // STACK: <empty>
                loadLocal(resultLocal!!)
                // STACK: result
            }
        }
        // STACK: result?
    }

    protected fun GeneratorAdapter.processMethodCallException(
        methodId: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int,
        threadDescriptorLocal: Int,
        resultInterceptorLocal: Int,
    ) {
        // STACK: exception
        val exceptionLocal = newLocal(THROWABLE_TYPE)
        storeLocal(exceptionLocal)
        // STACK: <empty>
        loadLocal(threadDescriptorLocal)
        push(methodId)
        pushReceiver(receiverLocal)
        loadLocal(argumentsArrayLocal)
        loadLocal(exceptionLocal)
        loadLocal(resultInterceptorLocal)

        // STACK: descriptor, methodId, receiver, params, exception, interceptor?
        invokeStatic(Injections::onMethodCallException)
        // STACK: <empty>
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

    protected fun GeneratorAdapter.pushReceiver(receiverLocal: Int?) {
        // STACK: <empty>
        if (receiverLocal != null) {
            loadLocal(receiverLocal)
        } else {
            pushNull()
        }
        // STACK: receiver?
    }

    @Suppress("UNUSED_PARAMETER")
    protected fun shouldTrackMethodCall(className: String, methodName: String, descriptor: String): Boolean {
        // TODO: do not ignore <init>
        if (methodName == "<init>" && !configuration.trackConstructorCalls) return false
        if (isIgnoredClass(className)) return false
        if (isCoroutineResumptionSyntheticAccessor(className, methodName)) return false
        // `ThreadLocalRandom` is useless for the user,
        // and it depends on static initialization which is not instrumented.
        if (isThreadLocalRandomCurrent(className, methodName)) return false
        return true
    }

    private fun isIgnoredClass(className: String) =
        isInLincheckPackage(className.toCanonicalClassName()) ||
        isIntellijInstrumentationCoverageAgentClass(className.toCanonicalClassName()) ||
        className == "kotlin/jvm/internal/Intrinsics" ||
        className == "java/util/Objects" ||
        className == "java/lang/String" ||
        className == "java/lang/Character" ||
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
    private fun isCoroutineResumptionSyntheticAccessor(className: String, methodName: String): Boolean =
        (this.methodName == "invokeSuspend") && methodName.startsWith("access\$")

    private fun isThreadLocalRandomCurrent(className: String, methodName: String): Boolean {
        return className == "java/util/concurrent/ThreadLocalRandom" && methodName == "current"
    }

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
