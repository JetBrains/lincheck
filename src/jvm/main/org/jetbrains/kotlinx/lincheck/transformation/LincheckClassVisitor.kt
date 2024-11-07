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
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import sun.nio.ch.lincheck.*

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
            mv = run {
                val st = SnapshotTrackerTransformer(fileName, className, methodName, mv.newAdapter())
                val aa = AnalyzerAdapter(className, access, methodName, desc, st)
                st.analyzer = aa
                aa
            }
            return mv
        }
        /* Wrap `ClassLoader::loadClass` calls into ignored sections
         * to ensure their code is not analyzed by the Lincheck.
         */
        if (containsClassloaderInName(className)) {
            if (methodName == "loadClass") {
                mv = WrapMethodInIgnoredSectionTransformer(fileName, className, methodName, mv.newAdapter())
            }
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
        if (isStackTraceElementClass(className.canonicalClassName)) {
            mv = WrapMethodInIgnoredSectionTransformer(fileName, className, methodName, mv.newAdapter())
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
        // `skipVisitor` must not capture `MethodCallTransformer`
        // (to filter static method calls inserted by coverage library)
        val skipVisitor: MethodVisitor = mv
        mv = MethodCallTransformer(fileName, className, methodName, mv.newAdapter())
        mv = MonitorTransformer(fileName, className, methodName, mv.newAdapter())
        mv = WaitNotifyTransformer(fileName, className, methodName, mv.newAdapter())
        mv = ParkingTransformer(fileName, className, methodName, mv.newAdapter())
        mv = ObjectCreationTransformer(fileName, className, methodName, mv.newAdapter())
        mv = DeterministicHashCodeTransformer(fileName, className, methodName, mv.newAdapter())
        mv = DeterministicTimeTransformer(mv.newAdapter())
        mv = DeterministicRandomTransformer(fileName, className, methodName, mv.newAdapter())
        mv = UnsafeMethodTransformer(fileName, className, methodName, mv.newAdapter())
        mv = AtomicFieldUpdaterMethodTransformer(fileName, className, methodName, mv.newAdapter())
        mv = VarHandleMethodTransformer(fileName, className, methodName, mv.newAdapter())
        // `SharedMemoryAccessTransformer` goes first because it relies on `AnalyzerAdapter`,
        // which should be put in front of the byte-code transformer chain,
        // so that it can correctly analyze the byte-code and compute required type-information
        mv = run {
            val st = SnapshotTrackerTransformer(fileName, className, methodName, mv.newAdapter())
            val sv = SharedMemoryAccessTransformer(fileName, className, methodName,  st.newAdapter())
            val aa = AnalyzerAdapter(className, access, methodName, desc, sv)

            st.analyzer = aa
            sv.analyzer = aa
            aa
        }
        // Must appear in code after `SharedMemoryAccessTransformer` (to be able to skip this transformer)
        mv = CoverageBytecodeFilter(
            skipVisitor.newAdapter(),
            mv.newAdapter()
        )
        return mv
    }

}

internal open class ManagedStrategyWithAnalyzerClassVisitor(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    lateinit var analyzer: AnalyzerAdapter


    /*
     * For an array access instruction (either load or store),
     * tries to obtain the type of the read/written array element.
     *
     * If the type can be determined from the opcode of the instruction itself
     * (e.g., IALOAD/IASTORE) returns it immediately.
     *
     * Otherwise, queries the analyzer to determine the type of the array in the respective stack slot.
     * This is used in two cases:
     * - for `BALOAD` and `BASTORE` instructions, since they are used to access both boolean and byte arrays;
     * - for `AALOAD` and `AASTORE` instructions, to get the class name of the array elements.
     */
    protected fun getArrayElementType(opcode: Int): Type = when (opcode) {
        // Load
        IALOAD -> INT_TYPE
        FALOAD -> FLOAT_TYPE
        CALOAD -> CHAR_TYPE
        SALOAD -> SHORT_TYPE
        LALOAD -> LONG_TYPE
        DALOAD -> DOUBLE_TYPE
        BALOAD -> getArrayAccessTypeFromStack(2) ?: BYTE_TYPE
        AALOAD -> getArrayAccessTypeFromStack(2) ?: OBJECT_TYPE
        // Store
        IASTORE -> INT_TYPE
        FASTORE -> FLOAT_TYPE
        CASTORE -> CHAR_TYPE
        SASTORE -> SHORT_TYPE
        LASTORE -> LONG_TYPE
        DASTORE -> DOUBLE_TYPE
        BASTORE -> getArrayAccessTypeFromStack(3) ?: BYTE_TYPE
        AASTORE -> getArrayAccessTypeFromStack(3) ?: OBJECT_TYPE
        else -> throw IllegalStateException("Unexpected opcode: $opcode")
    }

    /*
     * Tries to obtain the type of array elements by inspecting the type of the array itself.
     * To do this, the method queries the analyzer to get the type of accessed array
     * which should lie on the stack.
     * If the analyzer does not know the type, then return null
     * (according to the ASM docs, this can happen, for example, when the visited instruction is unreachable).
     */
    protected fun getArrayAccessTypeFromStack(position: Int): Type? {
        if (analyzer.stack == null) return null
        val arrayDesc = analyzer.stack[analyzer.stack.size - position]
        check(arrayDesc is String)
        val arrayType = getType(arrayDesc)
        check(arrayType.sort == ARRAY)
        check(arrayType.dimensions > 0)
        return getType(arrayDesc.substring(1))
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