/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.jetbrains.kotlinx.lincheck.*
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.commons.*
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode.*
import org.jetbrains.kotlinx.lincheck.transformation.transformers.*
import sun.nio.ch.lincheck.*

internal class LincheckClassVisitor(
    private val classVisitor: SafeClassWriter,
    private val instrumentationMode: InstrumentationMode,
    private val methods: Map<String, Map<Int, List<LocalVariableInfo>>>,
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
        if (access and ACC_NATIVE != 0) return mv
        if (instrumentationMode == STRESS) {
            return if (methodName != "<clinit>" && methodName != "<init>") {
                CoroutineCancellabilitySupportTransformer(mv, access, className, methodName, desc)
            } else {
                mv
            }
        }
        fun MethodVisitor.newAdapter() = GeneratorAdapter(this, access, methodName, desc)
        if (methodName == "<clinit>") {
            mv = WrapMethodInIgnoredSectionTransformer(fileName, className, methodName, mv.newAdapter())
            return mv
        }
        // In some newer versions of JDK, `ThreadPoolExecutor` uses
        // the internal `ThreadContainer` classes to manage threads in the pool;
        // This class, in turn, has the method `start,
        // that does not directly call `Thread.start` to start a thread,
        // but instead uses internal API `JavaLangAccess.start`.
        // To detect threads started in this way, we instrument this class
        // and inject the appropriate hook on calls to the `JavaLangAccess.start` method.
        if (isThreadContainerClass(className.toCanonicalClassName())) {
            if (methodName == "start") {
                mv = ThreadTransformer(fileName, className, methodName, desc, mv.newAdapter())
            } else {
                mv = WrapMethodInIgnoredSectionTransformer(fileName, className, methodName, mv.newAdapter())
            }
            return mv
        }
        // Wrap `ClassLoader::loadClass` calls into ignored sections
        // to ensure their code is not analyzed by the Lincheck.
        if (containsClassloaderInName(className.toCanonicalClassName())) {
            if (methodName == "loadClass" && desc == "(Ljava/lang/String;)Ljava/lang/Class;") {
                mv = WrapMethodInIgnoredSectionTransformer(fileName, className, methodName, mv.newAdapter())
            }
            return mv
        }
        // Wrap `MethodHandles.Lookup.findX` and related methods into ignored sections
        // to ensure their code is not analyzed by the Lincheck.
        if (isIgnoredMethodHandleMethod(className.toCanonicalClassName(), methodName)) {
            mv = WrapMethodInIgnoredSectionTransformer(fileName, className, methodName, mv.newAdapter())
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
            mv = WrapMethodInIgnoredSectionTransformer(fileName, className, methodName, mv.newAdapter())
            return mv
        }
        if (isCoroutineInternalClass(className.toCanonicalClassName())) {
            return mv
        }
        // Debugger implicitly evaluates toString for variables rendering
        // We need to disable breakpoints in such a case, as the numeration will break.
        // Breakpoints are disabled as we do not instrument toString and enter an ignored section,
        // so there are no beforeEvents inside.
        if (methodName == "<init>" || ideaPluginEnabled && methodName == "toString" && desc == "()Ljava/lang/String;") {
            mv = ObjectCreationTransformer(fileName, className, methodName, mv.newAdapter())
            if (isInTraceDebuggerMode) {
                // Lincheck does not support true identity hash codes (it always uses zeroes),
                // so there is no need for the `DeterministicInvokeDynamicTransformer` there.
                mv = DeterministicInvokeDynamicTransformer(fileName, className, methodName, mv.newAdapter())
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
        mv = JSRInlinerAdapter(mv, access, methodName, desc, signature, exceptions)
        mv = TryCatchBlockSorter(mv, access, methodName, desc, signature, exceptions)
        mv = CoroutineCancellabilitySupportTransformer(mv, access, className, methodName, desc)
        mv = CoroutineDelaySupportTransformer(fileName, className, methodName, mv.newAdapter())
        mv = ThreadTransformer(fileName, className, methodName, desc, mv.newAdapter())
        // We can do further instrumentation in methods of the custom thread subclasses,
        // but not in the `java.lang.Thread` itself.
        if (isThreadClass(className.toCanonicalClassName())) {
            return mv
        }
        if (access and ACC_SYNCHRONIZED != 0) {
            mv = SynchronizedMethodTransformer(fileName, className, methodName, mv.newAdapter(), classVersion)
        }
        // `skipVisitor` must not capture `MethodCallTransformer`
        // (to filter static method calls inserted by coverage library)
        val skipVisitor: MethodVisitor = mv
        mv = MethodCallTransformer(fileName, className, methodName, mv.newAdapter())
        mv = MonitorTransformer(fileName, className, methodName, mv.newAdapter())
        mv = WaitNotifyTransformer(fileName, className, methodName, mv.newAdapter())
        mv = ParkingTransformer(fileName, className, methodName, mv.newAdapter())
        mv = ObjectCreationTransformer(fileName, className, methodName, mv.newAdapter())
        if (isInTraceDebuggerMode) {
            // Lincheck does not support true identity hash codes (it always uses zeroes),
            // so there is no need for the `DeterministicInvokeDynamicTransformer` there.
            mv = DeterministicInvokeDynamicTransformer(fileName, className, methodName, mv.newAdapter())
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
        val locals: Map<Int, List<LocalVariableInfo>> = methods[methodName + desc] ?: emptyMap()
        mv = LocalVariablesAccessTransformer(fileName, className, methodName, mv.newAdapter(), locals)
        // Must appear in code after `SharedMemoryAccessTransformer` (to be able to skip this transformer)
        mv = CoverageBytecodeFilter(
            skipVisitor.newAdapter(),
            mv.newAdapter()
        )
        return mv
    }

}

internal open class ManagedStrategyMethodVisitor(
    protected val fileName: String,
    protected val className: String,
    protected val methodName: String,
    val adapter: GeneratorAdapter
) : MethodVisitor(ASM_API, adapter) {
    private var lineNumber = 0

    /**
     * Injects `beforeEvent` method invocation if IDEA plugin is enabled.
     *
     * @param type type of the event, needed just for debugging.
     * @param setMethodEventId a flag that identifies that method call event id set is required
     */
    protected fun invokeBeforeEventIfPluginEnabled(type: String, setMethodEventId: Boolean = false) {
        if (ideaPluginEnabled) {
            adapter.invokeBeforeEvent(type, setMethodEventId)
        }
    }

    protected fun loadNewCodeLocationId() {
        val stackTraceElement = StackTraceElement(className, methodName, fileName, lineNumber)
        val codeLocationId = CodeLocations.newCodeLocation(stackTraceElement)
        adapter.push(codeLocationId)
    }

    override fun visitLineNumber(line: Int, start: Label) {
        lineNumber = line
        super.visitLineNumber(line, start)
    }
}

private class WrapMethodInIgnoredSectionTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    private val tryBlock: Label = adapter.newLabel()
    private val catchBlock: Label = adapter.newLabel()

    override fun visitCode() = adapter.run {
        visitCode()
        invokeStatic(Injections::enterIgnoredSection)
        visitTryCatchBlock(tryBlock, catchBlock, catchBlock, null)
        visitLabel(tryBlock)
    }

    override fun visitInsn(opcode: Int) = adapter.run {
        when (opcode) {
            ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> {
                invokeStatic(Injections::leaveIgnoredSection)
            }
        }
        visitInsn(opcode)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) = adapter.run {
        visitLabel(catchBlock)
        invokeStatic(Injections::leaveIgnoredSection)
        visitInsn(ATHROW)
        visitMaxs(maxStack, maxLocals)
    }

}

// Set storing canonical names of the classes that call internal coroutine functions;
// it is used to optimize class re-transformation in stress mode by remembering
// exactly what classes need to be re-transformed (only the coroutines calling classes)
internal val coroutineCallingClasses = HashSet<String>()