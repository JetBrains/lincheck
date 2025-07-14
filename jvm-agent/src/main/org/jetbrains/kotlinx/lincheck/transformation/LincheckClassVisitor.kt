/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.jetbrains.lincheck.descriptors.CodeLocations
import org.jetbrains.kotlinx.lincheck.trace.recorder.transformers.MethodCallMinimalTransformer
import org.jetbrains.kotlinx.lincheck.trace.recorder.transformers.ObjectCreationMinimalTransformer
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.commons.*
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode.*
import org.jetbrains.kotlinx.lincheck.transformation.transformers.*
import org.jetbrains.lincheck.util.Logger
import org.jetbrains.lincheck.util.ideaPluginEnabled
import org.jetbrains.lincheck.util.isInTraceDebuggerMode
import org.jetbrains.lincheck.util.isThreadContainerClass
import org.jetbrains.lincheck.util.isIntellijRuntimeAgentClass
import sun.nio.ch.lincheck.*

internal class LincheckClassVisitor(
    private val classVisitor: SafeClassWriter,
    private val instrumentationMode: InstrumentationMode,
    private val methods: Map<String, MethodVariables>,
) : ClassVisitor(ASM_API, classVisitor) {
    private var classVersion = 0

    private var fileName: String = ""
    private var className: String = "" // internal class name

    override fun visitField(
        access: Int,
        fieldName: String,
        descriptor: String?,
        signature: String?,
        value: Any?
    ): FieldVisitor {
        if (access and ACC_FINAL != 0) {
            FinalFields.addFinalField(className, fieldName)
        } else {
            FinalFields.addMutableField(className, fieldName)
        }
        return super.visitField(access, fieldName, descriptor, signature, value)
    }

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String,
        interfaces: Array<String>
    ) {
        className = name
        classVersion = version
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitSource(source: String, debug: String?) {
        fileName = source
        super.visitSource(source, debug)
    }

    override fun visitMethod(
        access: Int,
        methodName: String,
        desc: String,
        signature: String?,
        exceptions: Array<String>?
    ): MethodVisitor {
        var mv = super.visitMethod(access, methodName, desc, signature, exceptions)
        if (access and ACC_NATIVE != 0) {
            Logger.debug { "Skipping transformation of the native method $className.$methodName" }
            return mv
        }

        fun MethodVisitor.newAdapter() = GeneratorAdapter(this, access, methodName, desc)
        fun MethodVisitor.newNonRemappingAdapter() = GeneratorAdapterWithoutLocals(this, access, methodName, desc)

        val isStatic = access and ACC_STATIC != 0

        if (instrumentationMode == STRESS) {
            return if (methodName != "<clinit>" && methodName != "<init>") {
                CoroutineCancellabilitySupportTransformer(mv, access, className, methodName, desc)
            } else {
                mv
            }
        }

        if (instrumentationMode == TRACE_RECORDING) {
            if (methodName == "<clinit>" || methodName == "<init>") return mv

            val locals = methods[methodName + desc] ?: MethodVariables()

            mv = JSRInlinerAdapter(mv, access, methodName, desc, signature, exceptions)
            mv = TryCatchBlockSorter(mv, access, methodName, desc, signature, exceptions)

            // If it is Thread don't instrument all other things in it
            if (isThreadSubClass(className)) {
                // We need this in TRACE_RECORDING mode to register new threads
                mv = ThreadTransformer(fileName, className, methodName, desc, mv.newAdapter())
                return mv
            }

            mv = ObjectCreationMinimalTransformer(fileName, className, methodName, mv.newAdapter())
            mv = MethodCallMinimalTransformer(fileName, className, methodName, mv.newAdapter())


            // `SharedMemoryAccessTransformer` goes first because it relies on `AnalyzerAdapter`,
            // which should be put in front of the byte-code transformer chain,
            // so that it can correctly analyze the byte-code and compute required type-information
            mv = run {
                val sharedMemoryAccessTransformer = SharedMemoryAccessTransformer(fileName, className, methodName, mv.newAdapter())
                val analyzerAdapter = AnalyzerAdapter(className, access, methodName, desc, sharedMemoryAccessTransformer)
                sharedMemoryAccessTransformer.analyzer = analyzerAdapter
                analyzerAdapter
            }
            mv = LocalVariablesAccessTransformer(fileName, className, methodName, mv.newAdapter(), desc, isStatic, locals)

            // Inline method call transformer relies on the original variables' indices, so it should go before (in FIFO order)
            // all transformers which can create local variables.
            // All visitors created AFTER InlineMethodCallTransformer must use a non-remapping Generator adapter.
            // mv = InlineMethodCallTransformer(fileName, className, methodName, desc, mv.newNonRemappingAdapter(), locals, mv)

            // This tacker must be before all transformers that use MethodVariables to track variable regions
            mv = LocalVariablesTracker(mv, locals)
            return mv
        }

        val intrinsicDelegateVisitor = mv
        mv = JSRInlinerAdapter(mv, access, methodName, desc, signature, exceptions)
        mv = TryCatchBlockSorter(mv, access, methodName, desc, signature, exceptions)
        if (methodName == "<clinit>") {
            mv = IgnoredSectionWrapperTransformer(fileName, className, methodName, mv.newAdapter())
            return mv
        }
        // Wrap `ClassLoader::loadClass` calls into ignored sections
        // to ensure their code is not analyzed by the Lincheck.
        if (isClassLoaderClassName(className.toCanonicalClassName())) {
            if (isLoadClassMethod(methodName, desc)) {
                mv = IgnoredSectionWrapperTransformer(fileName, className, methodName, mv.newAdapter())
            }
            return mv
        }
        // In some newer versions of JDK, `ThreadPoolExecutor` uses
        // the internal `ThreadContainer` classes to manage threads in the pool;
        // This class, in turn, has the method `start`,
        // that does not directly call `Thread.start` to start a thread,
        // but instead uses internal API `JavaLangAccess.start`.
        // To detect threads started in this way, we instrument this class
        // and inject the appropriate hook on calls to the `JavaLangAccess.start` method.
        if (isThreadContainerClass(className.toCanonicalClassName())) {
            if (methodName == "start") {
                mv = ThreadTransformer(fileName, className, methodName, desc, mv.newAdapter())
            } else {
                mv = IgnoredSectionWrapperTransformer(fileName, className, methodName, mv.newAdapter())
            }
            return mv
        }
        // Wrap `MethodHandles.Lookup.findX` and related methods into ignored sections
        // to ensure their code is not analyzed by the Lincheck.
        if (isIgnoredMethodHandleMethod(className.toCanonicalClassName(), methodName)) {
            mv = IgnoredSectionWrapperTransformer(fileName, className, methodName, mv.newAdapter())
            return mv
        }
        /* Wrap all methods of the ` StackTraceElement ` class into ignored sections.
         * Although `StackTraceElement` own bytecode should not be instrumented,
         * it may call functions from `java.util` classes (e.g., `HashMap`),
         * which can be instrumented and analyzed.
         * At the same time, `StackTraceElement` methods can be called almost at any point
         * (e.g., when an exception is thrown and its stack trace is being collected),
         * and we should ensure that these calls are not analyzed by Lincheck.
         *
         * See the following issues:
         *   - https://github.com/JetBrains/lincheck/issues/376
         *   - https://github.com/JetBrains/lincheck/issues/419
         */
        if (isStackTraceElementClass(className.toCanonicalClassName())) {
            mv = IgnoredSectionWrapperTransformer(fileName, className, methodName, mv.newAdapter())
            return mv
        }
        // Wrap IntelliJ IDEA runtime agent's methods into ignored section.
        if (isIntellijRuntimeAgentClass(className.toCanonicalClassName())) {
            mv = WrapMethodInIgnoredSectionTransformer(fileName, className, methodName, mv.newAdapter())
            return mv
        }
        /*
         * Instrumentation of `java.util.Arrays` class causes some subtle flaky bugs.
         * See details in https://github.com/JetBrains/lincheck/issues/717.
         */
        if (isJavaUtilArraysClass(className.toCanonicalClassName())) {
            // `java.util.Arrays` contains intrinsic methods --- we need to process them
            mv = IntrinsicCandidateMethodFilter(className, methodName, desc, intrinsicDelegateVisitor.newAdapter(), mv.newAdapter())
            return mv
        }
        if (isCoroutineInternalClass(className.toCanonicalClassName())) {
            return mv
        }
        // Debugger implicitly evaluates `toString()` for variables rendering.
        // We need to ensure there are no `beforeEvents` calls inside `toString()`
        // to ensure the event numeration will remain the same.
        if (ideaPluginEnabled && isToStringMethod(methodName, desc)) {
            mv = ObjectCreationTransformer(fileName, className, methodName, mv.newAdapter())
            // TODO: replace with proper instrumentation mode for debugger, don't use globals
            if (isInTraceDebuggerMode) {
                // Lincheck does not support true identity hash codes (it always uses zeroes),
                // so there is no need for the `DeterministicInvokeDynamicTransformer` there.
                mv = DeterministicInvokeDynamicTransformer(
                    fileName, className, methodName, classVersion, mv.newAdapter()
                )
            }
            return mv
        }
        // Currently, constructors are treated in a special way to avoid problems
        // with `VerificationError` due to leaking this problem,
        // see: https://github.com/JetBrains/lincheck/issues/424
        if ((methodName == "<init>" && !isInTraceDebuggerMode)) {
            mv = ObjectCreationTransformer(fileName, className, methodName, mv.newAdapter())
            // TODO: replace with proper instrumentation mode for debugger, don't use globals
            if (isInTraceDebuggerMode) {
                // Lincheck does not support true identity hash codes (it always uses zeroes),
                // so there is no need for the `DeterministicInvokeDynamicTransformer` there.
                mv = DeterministicInvokeDynamicTransformer(
                    fileName, className, methodName, classVersion, mv.newAdapter()
                )
            }
            mv = run {
                val st = ConstructorArgumentsSnapshotTrackerTransformer(fileName, className, methodName, mv.newAdapter(), classVisitor::isInstanceOf)
                val sv = SharedMemoryAccessTransformer(fileName, className, methodName, st.newAdapter())
                val aa = AnalyzerAdapter(className, access, methodName, desc, sv)
                sv.analyzer = aa
                aa
            }
            return mv
        }
        mv = CoroutineCancellabilitySupportTransformer(mv, access, className, methodName, desc)
        mv = CoroutineDelaySupportTransformer(fileName, className, methodName, mv.newAdapter())
        // For `java.lang.Thread` class, we only apply `ThreadTransformer` and skip all other transformations
        if (isThreadClass(className.toCanonicalClassName())) {
            mv = ThreadTransformer(fileName, className, methodName, desc, mv.newAdapter())
            // Must appear last in the code, to completely hide intrinsic candidate methods from all transformers
            mv = IntrinsicCandidateMethodFilter(className, methodName, desc, intrinsicDelegateVisitor.newAdapter(), mv.newAdapter())
            return mv
        }
        if (instrumentationMode == MODEL_CHECKING) {
            if (access and ACC_SYNCHRONIZED != 0) {
                mv = SynchronizedMethodTransformer(fileName, className, methodName, mv.newAdapter(), classVersion)
            }
        }
        // `coverageDelegateVisitor` must not capture `MethodCallTransformer`
        // (to filter static method calls inserted by coverage library)
        val coverageDelegateVisitor: MethodVisitor = mv
        mv = MethodCallTransformer(fileName, className, methodName, mv.newAdapter())
        // These transformers are useful only in model checking mode: they
        // support thread scheduling and determinism, which is not needed in other modes.
        if (instrumentationMode == MODEL_CHECKING) {
            mv = MonitorTransformer(fileName, className, methodName, mv.newAdapter())
            mv = WaitNotifyTransformer(fileName, className, methodName, mv.newAdapter())
            mv = ParkingTransformer(fileName, className, methodName, mv.newAdapter())
            mv = ObjectCreationTransformer(fileName, className, methodName, mv.newAdapter())
        }
        mv = ThreadTransformer(fileName, className, methodName, desc, mv.newAdapter())
        // TODO: replace with proper instrumentation mode for debugger, don't use globals
        if (isInTraceDebuggerMode) {
            // Lincheck does not support true identity hash codes (it always uses zeroes),
            // so there is no need for the `DeterministicInvokeDynamicTransformer` there.
            mv = DeterministicInvokeDynamicTransformer(fileName, className, methodName, classVersion, mv.newAdapter())
        } else {
            // In trace debugger mode we record hash codes of tracked objects and substitute them on re-run,
            // otherwise, we track all hash code calls in the instrumented code
            // and substitute them with constant.
            mv = ConstantHashCodeTransformer(fileName, className, methodName, mv.newAdapter())
        }
        // `SharedMemoryAccessTransformer` goes first because it relies on `AnalyzerAdapter`,
        // which should be put in front of the byte-code transformer chain,
        // so that it can correctly analyze the byte-code and compute required type-information
        mv = run {
            val st = ConstructorArgumentsSnapshotTrackerTransformer(fileName, className, methodName, mv.newAdapter(), classVisitor::isInstanceOf)
            val sv = SharedMemoryAccessTransformer(fileName, className, methodName, st.newAdapter())
            val aa = AnalyzerAdapter(className, access, methodName, desc, sv)
            sv.analyzer = aa
            aa
        }
        val locals = methods[methodName + desc] ?: MethodVariables()

        mv = LocalVariablesAccessTransformer(fileName, className, methodName, mv.newAdapter(), desc, isStatic, locals)
        // Inline method call transformer relies on the original variables' indices, so it should go before (in FIFO order)
        // all transformers which can create local variables.
        // We cannot use trick with
        // All visitors created AFTER InlineMethodCallTransformer must use a non-remapping Generator adapter too
        // mv = InlineMethodCallTransformer(fileName, className, methodName, desc, mv.newNonRemappingAdapter(), locals, mv)

        // This tacker must be before all transformers that use MethodVariables to track variable regions
        mv = LocalVariablesTracker(mv, locals)

        // Must appear in code after `SharedMemoryAccessTransformer` (to be able to skip this transformer).
        // It can appear earlier in code than `IntrinsicCandidateMethodFilter` because if kover instruments intrinsic methods
        // (which cannot disallow) then we don't need to hide coverage instrumentation from lincheck,
        // because lincheck will not see intrinsic method bodies at all.
        mv = CoverageBytecodeFilter(coverageDelegateVisitor.newAdapter(), mv.newNonRemappingAdapter())
        // Must appear last in the code, to completely hide intrinsic candidate methods from all transformers
        mv = IntrinsicCandidateMethodFilter(className, methodName, desc, intrinsicDelegateVisitor.newAdapter(), mv.newNonRemappingAdapter())

        return mv
    }
}