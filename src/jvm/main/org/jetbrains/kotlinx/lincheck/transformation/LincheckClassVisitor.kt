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
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.*
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode.*
import org.jetbrains.kotlinx.lincheck.transformation.transformers.*
import sun.nio.ch.lincheck.*
import kotlin.collections.HashSet

internal class LincheckClassVisitor(
    private val instrumentationMode: InstrumentationMode,
    classVisitor: ClassVisitor
) : ClassVisitor(ASM_API, classVisitor) {
    private val ideaPluginEnabled = ideaPluginEnabled()
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
        if (methodName == "<clinit>" ||
            // Debugger implicitly evaluates toString for variables rendering
            // We need to disable breakpoints in such a case, as the numeration will break.
            // Breakpoints are disabled as we do not instrument toString and enter an ignored section,
            // so there are no beforeEvents inside.
            ideaPluginEnabled && methodName == "toString" && desc == "()Ljava/lang/String;") {
            mv = WrapMethodInIgnoredSectionTransformer(fileName, className, methodName, mv.newAdapter())
            return mv
        }
        if (methodName == "<init>") {
            mv = ObjectCreationTransformer(fileName, className, methodName, mv.newAdapter())
            return mv
        }
        if (className.contains("ClassLoader")) {
            if (methodName == "loadClass") {
                mv = WrapMethodInIgnoredSectionTransformer(fileName, className, methodName, mv.newAdapter())
            }
            return mv
        }
        if (isCoroutineInternalClass(className)) {
            return mv
        }
        mv = JSRInlinerAdapter(mv, access, methodName, desc, signature, exceptions)
        mv = TryCatchBlockSorter(mv, access, methodName, desc, signature, exceptions)
        mv = CoroutineCancellabilitySupportTransformer(mv, access, className, methodName, desc)
        if (access and ACC_SYNCHRONIZED != 0) {
            mv = SynchronizedMethodTransformer(fileName, className, methodName, mv.newAdapter(), classVersion)
        }
        val skipVisitor: MethodVisitor = mv // Must not capture `MethodCallTransformer` (in order to filter static method calls inserted by coverage library)
        mv = MethodCallTransformer(fileName, className, methodName, mv.newAdapter())
        mv = MonitorTransformer(fileName, className, methodName, mv.newAdapter())
        mv = WaitNotifyTransformer(fileName, className, methodName, mv.newAdapter())
        mv = ParkingTransformer(fileName, className, methodName, mv.newAdapter())
        mv = ObjectCreationTransformer(fileName, className, methodName, mv.newAdapter())
        mv = UnsafeMethodTransformer(fileName, className, methodName, mv.newAdapter())
        mv = AtomicFieldUpdaterMethodTransformer(fileName, className, methodName, mv.newAdapter())
        mv = VarHandleMethodTransformer(fileName, className, methodName, mv.newAdapter())
        mv = run {
            val sv = SharedMemoryAccessTransformer(fileName, className, methodName, mv.newAdapter())
            val aa = AnalyzerAdapter(className, access, methodName, desc, sv)
            sv.analyzer = aa
            aa
        }
        mv = CoverageBytecodeFilter(
            skipVisitor.newAdapter(),
            mv.newAdapter()
        ) // Must appear in code after `SharedMemoryAccessTransformer` (in order to be able to skip this transformer
        mv = DeterministicHashCodeTransformer(fileName, className, methodName, mv.newAdapter())
        mv = DeterministicTimeTransformer(mv.newAdapter())
        mv = DeterministicRandomTransformer(fileName, className, methodName, mv.newAdapter())
        return mv
    }

}

internal open class ManagedStrategyMethodVisitor(
    protected val fileName: String,
    protected val className: String,
    protected val methodName: String,
    val adapter: GeneratorAdapter
) : MethodVisitor(ASM_API, adapter) {

    private val ideaPluginEnabled = ideaPluginEnabled()

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

// TODO: doesn't support exceptions
private class WrapMethodInIgnoredSectionTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
    private var enteredInIgnoredSectionLocal = 0

    override fun visitCode() = adapter.run {
        enteredInIgnoredSectionLocal = newLocal(BOOLEAN_TYPE)
        invokeStatic(Injections::enterIgnoredSection)
        storeLocal(enteredInIgnoredSectionLocal)
        visitCode()
    }

    override fun visitInsn(opcode: Int) = adapter.run {
        when (opcode) {
            ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> {
                ifStatement(
                    condition = { loadLocal(enteredInIgnoredSectionLocal) },
                    ifClause = { invokeStatic(Injections::leaveIgnoredSection) },
                    elseClause = {}
                )
            }
        }
        visitInsn(opcode)
    }
}

// Set storing canonical names of the classes that call internal coroutine functions;
// it is used to optimize class re-transformation in stress mode by remembering
// exactly what classes need to be re-transformed (only the coroutines calling classes)
internal val coroutineCallingClasses = HashSet<String>()