/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.jetbrains.lincheck.jvm.agent.transformers.MethodCallMinimalTransformer
import org.jetbrains.lincheck.jvm.agent.transformers.ObjectCreationMinimalTransformer
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.commons.*
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode.*
import org.jetbrains.lincheck.jvm.agent.transformers.*
import org.jetbrains.lincheck.util.*

internal class LincheckClassVisitor(
    private val classVisitor: SafeClassWriter,
    private val instrumentationMode: InstrumentationMode,
    private val classInformation: ClassInformation
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

        val methodInfo = classInformation.methodInformation(methodName, desc)

        val isNative = (access and ACC_NATIVE != 0)
        if (isNative) {
            Logger.debug { "Skipping transformation of the native method $className.$methodName" }
            return mv
        } else {
            Logger.debug { "Transforming method $className.$methodName" }
        }

        if (instrumentationMode == STRESS) {
            if (methodName == "<clinit>" || methodName == "<init>") return mv

            val adapter = GeneratorAdapter(mv, access, methodName, desc)
            mv = adapter

            // in Stress mode we apply only `CoroutineCancellabilitySupportTransformer`
            // to track coroutine suspension points
            mv = CoroutineCancellabilitySupportTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)

            return mv
        }

        if (instrumentationMode == TRACE_RECORDING) {
            mv = JSRInlinerAdapter(mv, access, methodName, desc, signature, exceptions)
            mv = TryCatchBlockSorter(mv, access, methodName, desc, signature, exceptions)

            val adapter = GeneratorAdapter(mv, access, methodName, desc)
            mv = adapter

            if (shouldWrapInIgnoredSection(className, methodName, desc)) {
                // Note: <clinit> case is handle here as well
                mv = IgnoredSectionWrapperTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
                return mv
            }

            if (methodName == "<init>") {
                mv = applyObjectCreationTransformer(methodName, desc, access, methodInfo, adapter, mv)
                return mv
            }

            // We need this in TRACE_RECORDING mode to register new threads
            mv = ThreadTransformer(fileName, className, methodName, methodInfo, desc, access, adapter, mv)
            // If it is Thread don't instrument all other things in it
            if (
                isThreadClass(className.toCanonicalClassName()) ||
                isThreadContainerThreadStartMethod(className.toCanonicalClassName(), methodName)
            ) {
                return mv
            }

            mv = applyObjectCreationTransformer(methodName, desc, access, methodInfo, adapter, mv)

            var methodCallTransformer: MethodCallTransformerBase? = null
            mv = applyMethodCallTransformer(methodName, desc, access, methodInfo, adapter, mv).also {
                methodCallTransformer = it
            }

            // `SharedMemoryAccessTransformer` goes first because it relies on `AnalyzerAdapter`,
            // which should be put in front of the byte-code transformer chain,
            // so that it can correctly analyze the byte-code and compute required type-information
            var sharedMemoryAccessTransformer: SharedMemoryAccessTransformer? = null
            mv = applySharedMemoryAccessTransformer(methodName, desc, access, methodInfo, adapter, mv).also {
                sharedMemoryAccessTransformer = it
            }

            mv = LocalVariablesAccessTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv, methodInfo.locals)
            mv = InlineMethodCallTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)

            mv = applyOwnerNameAnalyzerAdapter(access, methodName, desc, methodInfo, mv,
                methodCallTransformer,
                sharedMemoryAccessTransformer,
            )
            mv = applyAnalyzerAdapter(access, methodName, desc, mv,
                sharedMemoryAccessTransformer,
            )

            // This tacker must be before all transformers that use MethodVariables to track variable regions
            mv = LabelsTracker(mv, methodInfo)

            return mv
        }

        mv = JSRInlinerAdapter(mv, access, methodName, desc, signature, exceptions)
        mv = TryCatchBlockSorter(mv, access, methodName, desc, signature, exceptions)

        val initialVisitor = mv
        val adapter = GeneratorAdapter(mv, access, methodName, desc)
        mv = adapter

        // NOTE: `shouldWrapInIgnoredSection` should be before `shouldNotInstrument`,
        //       otherwise we may incorrectly forget to add some ignored sections
        //       and start tracking events in unexpected places
        if (shouldWrapInIgnoredSection(className, methodName, desc)) {
            mv = IgnoredSectionWrapperTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
            return mv
        }
        if (shouldNotInstrument(className, methodName, desc)) {
            // Must appear last in the code, to completely hide intrinsic candidate methods from all transformers
            mv = IntrinsicCandidateMethodFilter(className, methodName, desc, initialVisitor, mv)
            return mv
        }

        // Debugger implicitly evaluates `toString()` for variables rendering.
        // We need to ensure there are no `beforeEvents` calls inside `toString()`
        // to ensure the event numeration will remain the same.
        if (ideaPluginEnabled && isToStringMethod(methodName, desc)) {
            mv = applyObjectCreationTransformer(methodName, desc, access, methodInfo, adapter, mv)
            mv = applyDeterministicInvokeDynamicTransformer(methodName, desc, access, methodInfo, adapter, mv)
            return mv
        }

        // Currently, constructors are treated in a special way to avoid problems
        // with `VerificationError` due to leaking this problem,
        // see: https://github.com/JetBrains/lincheck/issues/424
        if ((methodName == "<init>" && instrumentationMode == MODEL_CHECKING)) {
            var sharedMemoryAccessTransformer: SharedMemoryAccessTransformer? = null
            mv = applyObjectCreationTransformer(methodName, desc, access, methodInfo, adapter, mv)
            mv = applySharedMemoryAccessTransformer(methodName, desc, access, methodInfo, adapter, mv).also {
                sharedMemoryAccessTransformer = it
            }
            mv = applyAnalyzerAdapter(access, methodName, desc, mv, sharedMemoryAccessTransformer)
            mv = applyOwnerNameAnalyzerAdapter(access, methodName, desc, methodInfo, mv,
                methodCallTransformer = null,
                sharedMemoryAccessTransformer,
            )
            return mv
        }

        // For `java.lang.Thread` class (and `ThreadContainer.start()` method),
        // we only apply `ThreadTransformer` and skip all other transformations
        if (isThreadClass(className.toCanonicalClassName()) ||
            isThreadContainerThreadStartMethod(className.toCanonicalClassName(), methodName)
        ) {
            mv = ThreadTransformer(fileName, className, methodName, methodInfo, desc, access, adapter, mv)
            // Must appear last in the code, to completely hide intrinsic candidate methods from all transformers
            mv = IntrinsicCandidateMethodFilter(className, methodName, desc, initialVisitor, mv)
            return mv
        }

        mv = CoroutineCancellabilitySupportTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        mv = CoroutineDelaySupportTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)

        mv = ThreadTransformer(fileName, className, methodName, methodInfo, desc, access, adapter, mv)

        var methodCallTransformer: MethodCallTransformerBase? = null
        mv = applyMethodCallTransformer(methodName, desc, access, methodInfo, adapter, mv).also {
            methodCallTransformer = it
        }

        mv = applyObjectCreationTransformer(methodName, desc, access, methodInfo, adapter, mv)

        mv = applyDeterministicInvokeDynamicTransformer(methodName, desc, access, methodInfo, adapter, mv)
        mv = applyConstantHashCodeTransformer(methodName, desc, access, methodInfo, adapter, mv)

        mv = applySynchronizationTrackingTransformers(methodName, desc, access, methodInfo, adapter, mv)

        // `SharedMemoryAccessTransformer` goes first because it relies on `AnalyzerAdapter`,
        // which should be put in front of the byte-code transformer chain,
        // so that it can correctly analyze the byte-code and compute required type-information
        var sharedMemoryAccessTransformer: SharedMemoryAccessTransformer? = null
        mv = applySharedMemoryAccessTransformer(methodName, desc, access, methodInfo, adapter, mv).also {
            sharedMemoryAccessTransformer = it
        }

        mv = LocalVariablesAccessTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv, methodInfo.locals)
        mv = InlineMethodCallTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)

        mv = applyAnalyzerAdapter(access, methodName, desc, mv,
            sharedMemoryAccessTransformer,
        )
        mv = applyOwnerNameAnalyzerAdapter(access, methodName, desc, methodInfo, mv,
            methodCallTransformer,
            sharedMemoryAccessTransformer,
        )

        // This tacker must be before all transformers that use MethodVariables to track variable regions
        mv = LabelsTracker(mv, methodInfo)

        // Must appear in code after `SharedMemoryAccessTransformer` (to be able to skip this transformer).
        // It can appear earlier in code than `IntrinsicCandidateMethodFilter` because if kover instruments intrinsic methods
        // (which cannot disallow) then we don't need to hide coverage instrumentation from lincheck,
        // because lincheck will not see intrinsic method bodies at all.
        mv = CoverageBytecodeFilter(initialVisitor, mv)

        // Must appear last in the code, to completely hide intrinsic candidate methods from all transformers
        mv = IntrinsicCandidateMethodFilter(className, methodName, desc, initialVisitor, mv)

        return mv
    }

    private fun applyObjectCreationTransformer(
        methodName: String,
        desc: String,
        access: Int,
        methodInfo: MethodInformation,
        adapter: GeneratorAdapter,
        methodVisitor: MethodVisitor,
    ): ObjectCreationTransformerBase {
        var mv = methodVisitor
        if (instrumentationMode == TRACE_RECORDING) {
            mv = ObjectCreationMinimalTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        } else {
            mv = ObjectCreationTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        }
        return mv
    }

    private fun applyMethodCallTransformer(
        methodName: String,
        desc: String,
        access: Int,
        methodInfo: MethodInformation,
        adapter: GeneratorAdapter,
        methodVisitor: MethodVisitor,
    ): MethodCallTransformerBase {
        var mv = methodVisitor
        if (instrumentationMode == TRACE_RECORDING) {
            mv = MethodCallMinimalTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        } else {
            mv = MethodCallTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        }
        return mv
    }

    private fun applySynchronizationTrackingTransformers(
        methodName: String,
        desc: String,
        access: Int,
        methodInfo: MethodInformation,
        adapter: GeneratorAdapter,
        methodVisitor: MethodVisitor,
    ): MethodVisitor {
        var mv = methodVisitor
        val isSynchronized = (access and ACC_SYNCHRONIZED != 0)
        if (isSynchronized) {
            mv = SynchronizedMethodTransformer(fileName, className, methodName, desc, access, methodInfo, classVersion, adapter, mv)
        }
        mv = MonitorTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        mv = WaitNotifyTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        mv = ParkingTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        return mv
    }

    private fun applySharedMemoryAccessTransformer(
        methodName: String,
        desc: String,
        access: Int,
        methodInfo: MethodInformation,
        adapter: GeneratorAdapter,
        methodVisitor: MethodVisitor,
    ): SharedMemoryAccessTransformer {
        var mv = methodVisitor
        if (instrumentationMode != TRACE_RECORDING) {
            // this transformer is required because currently the snapshot tracker
            // does not trace memory accesses inside constructors
            mv = ConstructorArgumentsSnapshotTrackerTransformer(
                fileName, className, methodName, desc, access, methodInfo, adapter, mv,
                classVisitor::isInstanceOf
            )
        }
        mv = SharedMemoryAccessTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        return mv
    }

    private fun applyDeterministicInvokeDynamicTransformer(
        methodName: String,
        desc: String,
        access: Int,
        methodInfo: MethodInformation,
        adapter: GeneratorAdapter,
        methodVisitor: MethodVisitor,
    ): MethodVisitor {
        var mv = methodVisitor
        // In trace debugger mode we record hash codes of tracked objects and substitute them on re-run.
        // In model checking mode we track all hash code calls in the instrumented code.
        // Since in model checking we always use constant for identity hash codes,
        // there is no need for the `DeterministicInvokeDynamicTransformer` there.
        if (instrumentationMode == TRACE_DEBUGGING) {
            mv = DeterministicInvokeDynamicTransformer(fileName, className, methodName, desc, access, methodInfo, classVersion, adapter, mv)
        }
        return mv
    }

    private fun applyConstantHashCodeTransformer(
        methodName: String,
        desc: String,
        access: Int,
        methodInfo: MethodInformation,
        adapter: GeneratorAdapter,
        methodVisitor: MethodVisitor,
    ): MethodVisitor {
        var mv = methodVisitor
        // In trace debugger mode we record hash codes of tracked objects and substitute them on re-run.
        // In model checking mode we track all hash code calls in the instrumented code.
        if (instrumentationMode == MODEL_CHECKING) {
            mv = ConstantHashCodeTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        }
        return mv
    }

    private fun applyOwnerNameAnalyzerAdapter(
        access: Int,
        methodName: String,
        descriptor: String,
        metaInfo: MethodInformation,
        methodVisitor: MethodVisitor,
        methodCallTransformer: MethodCallTransformerBase?,
        sharedMemoryAccessTransformer: SharedMemoryAccessTransformer?,
    ): OwnerNameAnalyzerAdapter {
        val ownerNameAnalyzer = OwnerNameAnalyzerAdapter(className, access, methodName, descriptor, methodVisitor,
            metaInfo.locals
        )
        methodCallTransformer?.ownerNameAnalyzer = ownerNameAnalyzer
        sharedMemoryAccessTransformer?.ownerNameAnalyzer = ownerNameAnalyzer
        return ownerNameAnalyzer
    }

    private fun applyAnalyzerAdapter(
        access: Int,
        methodName: String,
        descriptor: String,
        methodVisitor: MethodVisitor,
        sharedMemoryAccessTransformer: SharedMemoryAccessTransformer?,
    ): AnalyzerAdapter {
        val analyzerAdapter = AnalyzerAdapter(className, access, methodName, descriptor, methodVisitor)
        sharedMemoryAccessTransformer?.analyzer = analyzerAdapter
        return analyzerAdapter
    }
}