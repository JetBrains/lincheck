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
        val isSynchronized = (access and ACC_SYNCHRONIZED != 0)

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

        mv = JSRInlinerAdapter(mv, access, methodName, desc, signature, exceptions)
        mv = TryCatchBlockSorter(mv, access, methodName, desc, signature, exceptions)

        val initialVisitor = mv
        val adapter = GeneratorAdapter(mv, access, methodName, desc)
        mv = adapter

        val profile = instrumentationMode.transformationProfile
        val chain = TransformerChain(
            config = profile.getMethodConfiguration(className.toCanonicalClassName(), methodName, desc),
            adapter = adapter,
            initialMethodVisitor = mv,
        )

        chain.addTransformer { adapter, mv ->
            CoroutineCancellabilitySupportTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        }
        chain.addTransformer { adapter, mv ->
            CoroutineDelaySupportTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        }

        chain.addTransformer { adapter, mv ->
            ThreadTransformer(fileName, className, methodName, methodInfo, desc, access, adapter, mv)
        }

        chain.addTransformer { adapter, mv ->
            applyMethodCallTransformer(methodName, desc, access, methodInfo, adapter, mv)
        }

        chain.addTransformer { adapter, mv ->
            applyObjectCreationTransformer(methodName, desc, access, methodInfo, adapter, mv)
        }

        chain.addTransformer { adapter, mv ->
            DeterministicInvokeDynamicTransformer(fileName, className, methodName, desc, access, methodInfo, classVersion, adapter, mv)
        }

        chain.addTransformer { adapter, mv ->
            ConstantHashCodeTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        }

        if (isSynchronized) {
            chain.addTransformer { adapter, mv ->
                SynchronizedMethodTransformer(fileName, className, methodName, desc, access, methodInfo, classVersion, adapter, mv)
            }
        }
        chain.addTransformer { adapter, mv ->
            MonitorTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        }
        chain.addTransformer { adapter, mv ->
            WaitNotifyTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        }
        chain.addTransformer { adapter, mv ->
            ParkingTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        }

        chain.addTransformer { adapter, mv ->
            applySharedMemoryAccessTransformer(methodName, desc, access, methodInfo, adapter, mv)
        }
        chain.addTransformer { adapter, mv ->
            LocalVariablesAccessTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv, methodInfo.locals)
        }

        chain.addTransformer { adapter, mv ->
            InlineMethodCallTransformer(fileName, className, methodName, desc, access, methodInfo, adapter, mv)
        }

        chain.addTransformer { _, mv ->
            AnalyzerAdapter(className, access, methodName, desc, mv)
        }
        chain.addTransformer { _, mv ->
            OwnerNameAnalyzerAdapter(className, access, methodName, desc, mv, methodInfo.locals)
        }

        mv = chain.methodVisitors.last()

        // This tacker must be before all transformers that use MethodVariables to track variable regions
        mv = LabelsTracker(mv, methodInfo)

        // Must appear in code after `SharedMemoryAccessTransformer` (to be able to skip this transformer).
        // It can appear earlier in code than `IntrinsicCandidateMethodFilter` because if kover instruments intrinsic methods
        // (which cannot disallow) then we don't need to hide coverage instrumentation from lincheck,
        // because lincheck will not see intrinsic method bodies at all.
        if (instrumentationMode == MODEL_CHECKING || instrumentationMode == TRACE_DEBUGGING) {
            mv = CoverageBytecodeFilter(initialVisitor, mv)
        }

        // Must appear last in the code, to completely hide intrinsic candidate methods from all transformers
        if (instrumentationMode == MODEL_CHECKING || instrumentationMode == TRACE_DEBUGGING) {
            mv = IntrinsicCandidateMethodFilter(className, methodName, desc, initialVisitor, mv)
        }

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