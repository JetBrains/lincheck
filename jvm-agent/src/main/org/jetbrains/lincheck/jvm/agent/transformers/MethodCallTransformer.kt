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
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.jvm.agent.bytecodeinfo.MethodInformation
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.createAndRegisterMethodDescriptor
import org.jetbrains.lincheck.trace.isThisAccess
import org.jetbrains.lincheck.util.isInLincheckPackage
import org.jetbrains.lincheck.util.isIntellijInstrumentationCoverageAgentClass
import org.jetbrains.lincheck.util.isRecognizedLoggingLibraryClass
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
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

    override val requiresTypeAnalyzer: Boolean = true
    override val requiresAccessPathAnalyzer: Boolean = true

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
        // A super()/this() call has UNINITIALIZED_THIS as the receiver on the operand stack.
        // We detect this via the AnalyzerAdapter before emitting any instrumentation.
        val isUninitThisCall = isConstructorCall && isReceiverUninitializedThis(desc)
        val receiverType = getType("L$owner;")
        val argumentNames = getArgumentNames(desc, opcode)
        val accessPath = when {
            opcode == INVOKESTATIC && name.endsWith($$"$default") &&
                    argumentNames?.firstOrNull()?.locations?.singleOrNull()?.isThisAccess() == true -> argumentNames[0]
            else -> getAccessPath(desc, opcode)
        }

        // We assume that constructors return an object even though they don't
        // (because their descriptor specifies the 'void' return type).
        // Later 'processMethodCallReturn' takes this object and reports it as "return value" of a constructor.
        //
        // This assumption holds because the constructor invocation bytecode sequence looks as follows:
        // `NEW Clazz; DUP; push args; INVOKESPECIAL Clazz.<init>`.
        //
        // Before invoking the `<init>` method, the stack has the following shape:
        //   `STACK: uninitializedThis, uninitializedThis, args`
        // and after its invocation:
        //    `STACK: this (initialized)`
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
        val methodId = context.createAndRegisterMethodDescriptor(
            owner.toCanonicalClassName(),
            sanitizedMethodName,
            Types.convertAsmMethodType(desc)
        ).id

        val threadDescriptorLocal = newLocal(OBJECT_TYPE).also {
            invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
            storeLocal(it)
        }

        // creates and pushes onto the stack a result interceptor object
        // to allow event tracker to intercept method call if it wishes,
        // if configuration disables method result interception,
        // does not create an object and pushes `null` instead
        val resultInterceptorLocal = newLocal(OBJECT_TYPE).also {
            pushResultInterceptor(
                threadDescriptorLocal,
                shouldIntercept = shouldInterceptResult(isConstructorCall),
            )
            storeLocal(it)
        }

        // STACK: <empty>
        processMethodCallEnter(
            methodId,
            receiverLocal,
            argumentsArrayLocal,
            accessPath,
            argumentNames,
            threadDescriptorLocal,
            resultInterceptorLocal,
            isUninitThisCall,
        )
        // STACK: <empty>
        if (isUninitThisCall) {
            // An exception handler cannot carry UNINITIALIZED_THIS in its frame.
            invokeMethod(opcode, owner, name, desc, itf, receiverLocal, argumentLocals)
        } else {
            tryCatchFinally(
                tryBlock = {
                    processMethodCall(opcode, owner, name, desc, itf,
                        returnType,
                        receiverLocal,
                        argumentLocals,
                        resultInterceptorLocal,
                        isConstructorCall,
                    )
                    // STACK: result?
                },
                catchBlock = {
                    // STACK: exception
                    processMethodCallException(
                        methodId,
                        receiverLocal,
                        argumentsArrayLocal,
                        threadDescriptorLocal,
                        resultInterceptorLocal,
                        isConstructorCall,
                    )
                    // STACK: exception
                    throwException()
                }
            )
        }
        // A return callback may substitute an exception after completing the call's tracking state.
        // Keep it outside the handler so it does not also trigger the exception callback.
        processMethodCallReturn(
            returnType,
            methodId,
            receiverLocal,
            argumentsArrayLocal,
            threadDescriptorLocal,
            resultInterceptorLocal,
            isUninitThisCall,
            isConstructorCall,
        )
        // STACK: result?
    }

    /**
     * Returns true when the receiver of the constructor call being visited has
     * [Opcodes.UNINITIALIZED_THIS] as its verification type in the operand stack — i.e., this
     * is a `super()` or `this()` delegation call inside the current constructor body.
     *
     * The check reads [typeAnalyzer]'s stack state which reflects the original bytecode frame
     * before any instrumentation bytecode is emitted.
     */
    private fun isReceiverUninitializedThis(desc: String): Boolean {
        val stack = typeAnalyzer?.stack ?: return false
        val argSlots = getArgumentTypes(desc).sumOf { it.size }
        return stack.getStackElementAt(argSlots) == Opcodes.UNINITIALIZED_THIS
    }

    private fun GeneratorAdapter.processMethodCallEnter(
        methodId: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int,
        accessPath: AccessPath?,
        argumentNames: List<AccessPath?>?,
        threadDescriptorLocal: Int,
        resultInterceptorLocal: Int,
        isUninitThisCall: Boolean = false,
    ) {
        // STACK: <empty>
        loadLocal(threadDescriptorLocal)
        // STACK: descriptor
        loadNewCodeLocationId(createCurrentMethodCallCodeLocation(accessPath = accessPath, argumentNames = argumentNames))
        // STACK: descriptor, codeLocation
        push(methodId)
        pushReceiver(receiverLocal, isUninitThisCall)
        loadLocal(argumentsArrayLocal)
        loadLocal(resultInterceptorLocal)
        // STACK: descriptor, codeLocation, methodId, receiver?, argumentsArray, interceptor?
        invokeStatic(Injections::beforeMethodCall)
        // STACK: <empty>
        invokeBeforeEventIfPluginEnabled("method call ${this@MethodCallTransformer.methodName}")
    }

    private fun GeneratorAdapter.processMethodCall(
        opcode: Int,
        owner: String,
        name: String,
        desc: String,
        itf: Boolean,
        returnType: Type,
        receiverLocal: Int?,
        argumentLocals: IntArray,
        resultInterceptorLocal: Int,
        isConstructorCall: Boolean,
    ) {
        // Result interception is not applicable to constructors:
        //  - for super()/this() calls, INVOKESPECIAL leaves the stack empty;
        //  - for NEW/<init> pairs, skipping the constructor would leave an uninitialized value.
        if (!shouldInterceptResultBeforeMethodCall(isConstructorCall)) {
            invokeMethod(opcode, owner, name, desc, itf, receiverLocal, argumentLocals)
            return
        }

        ifStatement(
            condition = {
                isResultIntercepted(resultInterceptorLocal)
            },
            thenClause = {
                getOrThrowInterceptedResult(resultInterceptorLocal, returnType)
            },
            elseClause = {
                invokeMethod(opcode, owner, name, desc, itf, receiverLocal, argumentLocals)
            },
        )
    }

    private fun GeneratorAdapter.processMethodCallReturn(
        returnType: Type,
        methodId: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int,
        threadDescriptorLocal: Int,
        resultInterceptorLocal: Int,
        isUninitThisCall: Boolean,
        isConstructorCall: Boolean,
    ) {
        // STACK: result?
        val resultLocal = when {
            (returnType == VOID_TYPE) -> null
            isUninitThisCall -> newLocal(returnType).also {
                // For super()/this() calls the INVOKESPECIAL leaves the stack empty and initialises
                // `this` in-place. We load local 0 (now a fully initialised reference) to use as the
                // reported result.
                visitVarInsn(Opcodes.ALOAD, 0)
                storeLocal(it)
            }
            else -> newLocal(returnType).also { storeLocal(it) }
        }
        loadLocal(threadDescriptorLocal)
        push(methodId)
        pushReceiver(receiverLocal, isUninitThisCall)
        loadLocal(argumentsArrayLocal)
        resultLocal?.let {
            loadLocal(it)
            box(returnType)
        }
        loadLocal(resultInterceptorLocal)

        // STACK: descriptor, methodId, receiver, arguments, result?, interceptor?
        when {
            isUninitThisCall -> {
                // Report `this` as the result but do NOT push it onto the stack — the original
                // super()/this() call is void from the caller's perspective.
                invokeStatic(Injections::afterMethodCallReturn)
                // STACK: <empty>
            }
            shouldInterceptResultAfterMethodCall(isConstructorCall) -> {
                if (returnType == VOID_TYPE) {
                    invokeStatic(Injections::afterMethodCallReturnVoidOrIntercepted)
                } else {
                    invokeStatic(Injections::afterMethodCallReturnOrIntercepted)
                    unbox(returnType)
                }
                // STACK: result?
            }
            returnType == VOID_TYPE -> {
                invokeStatic(Injections::afterMethodCallReturnVoid)
                // STACK: <empty>
            }
            else -> {
                invokeStatic(Injections::afterMethodCallReturn)
                // STACK: <empty>
                loadLocal(resultLocal!!)
                // STACK: result
            }
        }
        // STACK: result?
    }

    private fun GeneratorAdapter.processMethodCallException(
        methodId: Int,
        receiverLocal: Int?,
        argumentsArrayLocal: Int,
        threadDescriptorLocal: Int,
        resultInterceptorLocal: Int,
        isConstructorCall: Boolean,
    ) {
        // STACK: exception
        val exceptionLocal = newLocal(THROWABLE_TYPE)
        storeLocal(exceptionLocal)
        // STACK: <empty>
        loadLocal(threadDescriptorLocal)
        push(methodId)
        pushReceiver(
            receiverLocal,
            isUninitThisCall = false /* exception handling is not applicable for the super(...) calls */
        )
        loadLocal(argumentsArrayLocal)
        loadLocal(exceptionLocal)
        loadLocal(resultInterceptorLocal)

        // STACK: descriptor, methodId, receiver, params, exception, interceptor?
        if (shouldInterceptResultAfterMethodCall(isConstructorCall)) {
            invokeStatic(Injections::afterMethodCallExceptionOrIntercepted)
        } else {
            invokeStatic(Injections::afterMethodCallException)
            loadLocal(exceptionLocal)
        }
        // STACK: exception
    }

    private fun GeneratorAdapter.invokeMethod(
        opcode: Int,
        owner: String,
        name: String,
        desc: String,
        itf: Boolean,
        receiverLocal: Int?,
        argumentLocals: IntArray,
    ) {
        // STACK: <empty>
        receiverLocal?.let { loadLocal(it) }
        loadLocals(argumentLocals)
        // STACK: receiver?, arguments
        mv.visitMethodInsn(opcode, owner, name, desc, itf)
        // STACK: result?
    }

    private fun getAccessPath(desc: String, opcode: Int): AccessPath? {
        val stack = accessPathAnalyzer?.stack ?: return null
        if (opcode == INVOKESTATIC) return null
        val position = getArgumentTypes(desc).sumOf { it.size }
        return stack.getStackElementAt(position)
    }

    private fun getArgumentNames(desc: String, opcode: Int): List<AccessPath?>? {
        val stack = accessPathAnalyzer?.stack ?: return null
        var position = 0
        val argumentTypes = getArgumentTypes(desc)
        return argumentTypes.reversed().map { argType ->
            val argPath = stack.getStackElementAt(position)
            position += argType.size
            argPath
        }.reversed()
    }

    private fun GeneratorAdapter.pushReceiver(receiverLocal: Int?, isUninitThisCall: Boolean) {
        // STACK: <empty>
        if (isUninitThisCall) {
            // For super()/this() calls the receiver slot holds UNINITIALIZED_THIS which cannot be
            // passed to instrumentation methods (causes VerifyError). Push the UNINITIALIZED_THIS sentinel instead.
            pushUninitializedThisSubstitute()
        } else {
            if (receiverLocal != null) {
                loadLocal(receiverLocal)
            } else {
                pushNull()
            }
        }
        // STACK: receiver?
    }

    @Suppress("UNUSED_PARAMETER")
    private fun shouldTrackMethodCall(className: String, methodName: String, descriptor: String): Boolean {
        if (methodName == "<init>" && !configuration.trackConstructorCalls) return false
        if (isIgnoredClass(className)) return false
        if (isCoroutineResumptionSyntheticAccessor(className, methodName)) return false
        // `ThreadLocalRandom` is useless for the user,
        // and it depends on static initialization which is not instrumented.
        if (isThreadLocalRandomCurrent(className, methodName)) return false
        return true
    }

    private fun shouldInterceptResult(isConstructorCall: Boolean): Boolean =
        shouldInterceptResultBeforeMethodCall(isConstructorCall) ||
        shouldInterceptResultAfterMethodCall(isConstructorCall)

    private fun shouldInterceptResultBeforeMethodCall(isConstructorCall: Boolean): Boolean =
        configuration.interceptMethodCallResults && !isConstructorCall

    private fun shouldInterceptResultAfterMethodCall(isConstructorCall: Boolean): Boolean =
        configuration.interceptMethodCallResultsAfterInvocation && !isConstructorCall

    private fun isIgnoredClass(className: String) =
        isInLincheckPackage(className.toCanonicalClassName()) ||
        isIntellijInstrumentationCoverageAgentClass(className.toCanonicalClassName()) ||
        isRecognizedLoggingLibraryClass(className.toCanonicalClassName()) ||
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
        className == "java/util/Properties" ||
        className == "java/lang/invoke/MethodHandles"

    @Suppress("UNUSED_PARAMETER")
    private fun isCoroutineResumptionSyntheticAccessor(className: String, methodName: String): Boolean =
        (this.methodName == "invokeSuspend") && methodName.startsWith("access\$")

    private fun isThreadLocalRandomCurrent(className: String, methodName: String): Boolean {
        return className == "java/util/concurrent/ThreadLocalRandom" && methodName == "current"
    }

    private fun sanitizeMethodName(
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
                val canonicalName = owner.toCanonicalClassName()
                if (LincheckInstrumentation.shouldTransform(canonicalName, instrumentationMode, loader = null)) {
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
