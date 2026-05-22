/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.analysis

import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.jvm.agent.analysis.SafetyViolation.*
import org.jetbrains.lincheck.util.Logger
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.*

/**
 * Represents a safety violation found during bytecode analysis.
 * In this context "safety" means purity, that is side-effect-free behavior.
 *
 * Safety violations include:
 * - field writes;
 * - array writes;
 * - uninitialized class static field reads (causes class initialization);
 * - monitor operations (synchronized blocks);
 * - disallowed method calls;
 * - disallowed dynamic invocations;
 * - non-final method calls;
 * - recursion depth exceeding;
 * - loop detected.
 */
sealed class SafetyViolation {
    abstract val fileName: String?
    abstract val lineNumber: Int

    protected val fileNameOrUnknown: String get() = fileName ?: "<unknown_source>"

    data class FieldWrite(
        override val fileName: String?,
        override val lineNumber: Int,
        val owner: String,
        val fieldName: String
    ) : SafetyViolation() {
        override fun toString() = "Field write: $fieldName at $fileNameOrUnknown:$lineNumber"
    }

    data class UninitializedClassStaticFieldRead(
        override val fileName: String?,
        override val lineNumber: Int,
        val owner: String,
        val fieldName: String
    ) : SafetyViolation() {
        override fun toString() = "Field read of uninitialized class: ${owner.toCanonicalClassName().toSimpleClassName()}.$fieldName at $fileNameOrUnknown:$lineNumber"
    }

    data class ArrayWrite(
        override val fileName: String?,
        override val lineNumber: Int
    ) : SafetyViolation() {
        override fun toString() = "Array write: at $fileNameOrUnknown:$lineNumber"
    }

    data class MonitorOperation(
        override val fileName: String?,
        override val lineNumber: Int
    ) : SafetyViolation() {
        override fun toString() = "Monitor operation (synchronized block) at $fileNameOrUnknown:$lineNumber"
    }

    data class DisallowedMethodCall(
        override val fileName: String?,
        override val lineNumber: Int,
        val owner: String,
        val methodName: String,
        val causes: List<SafetyViolation> = emptyList()
    ) : SafetyViolation() {
        override fun toString(): String {
            val sb = StringBuilder()
            buildTree(sb, "", true)
            return sb.toString().trimEnd()
        }

        private fun buildTree(sb: StringBuilder, prefix: String, isRoot: Boolean) {
            val location = if (fileName != null) " at $fileNameOrUnknown:$lineNumber" else ""
            sb.append("Disallowed method call: $methodName$location")

            if (causes.isNotEmpty()) {
                causes.forEachIndexed { index, cause ->
                    sb.appendLine()
                    val isLast = index == causes.lastIndex
                    val childPrefix = if (isRoot) "" else prefix
                    // Claude-generated magic for printing nice tree connectors.
                    val newPrefix = childPrefix + if (isLast) "    " else "\u2502   "
                    val connector = if (isLast) "\u2514\u2500\u2500 " else "\u251c\u2500\u2500 "

                    sb.append(childPrefix).append(connector)
                    when (cause) {
                        is DisallowedMethodCall -> {
                            cause.buildTree(sb, newPrefix, false)
                        }
                        else -> sb.append(cause.toString())
                    }
                }
            }
        }
    }

    data class MaxCallDepthExceeded(
        override val fileName: String?,
        override val lineNumber: Int,
        val owner: String,
        val methodName: String
    ) : SafetyViolation() {
        override fun toString(): String {
            return "Maximum call depth exceeded: $methodName at $fileNameOrUnknown:$lineNumber"
        }
    }

    data class DisallowedDynamicInvocation(
        override val fileName: String?,
        override val lineNumber: Int,
        val bootstrapOwner: String,
        val bootstrapName: String
    ) : SafetyViolation() {
        override fun toString(): String {
            return "Disallowed dynamic invocation: $bootstrapName at $fileNameOrUnknown:$lineNumber"
        }
    }

    data class ClassNotAccessible(
        override val fileName: String?,
        override val lineNumber: Int,
        val className: String
    ) : SafetyViolation() {
        override fun toString() = "Class $className is not accessible or its bytecode cannot be loaded"
    }

    data class MethodNotFinal(
        override val fileName: String?,
        override val lineNumber: Int,
        val methodName: String
    ) : SafetyViolation() {
        override fun toString() = "Method $methodName is not final and can be overridden at runtime at $fileNameOrUnknown:$lineNumber"
    }

    data class LoopDetected(
        override val fileName: String?,
        override val lineNumber: Int
    ) : SafetyViolation() {
        override fun toString() = "Loop detected at $fileNameOrUnknown:$lineNumber"
    }

    data class AnalysisFailed(
        val cause: Throwable? = null
    ) : SafetyViolation() {
        override val fileName = null
        override val lineNumber = -1

        override fun toString(): String {
            val stackTrace = cause?.stackTraceToString()?.let { "$it\n" } ?: ""
            return "Analysis failed: $cause\n$stackTrace"
        }
    }
}

typealias FunctionCallPredicate = (internalClassName: String, methodName: String, methodDescriptor: String) -> Boolean

/**
 * Loads class bytecode for the analyzer.
 *
 * The argument is the internal class name (JVM format, e.g. `"java/lang/String"`).
 * Returning `null` reports that the class is not accessible from this provider's point of view,
 * surfaced as [SafetyViolation.ClassNotAccessible].
 *
 * When class loader is available, [ClassLoader.findClassBytecode] is typically used as bytecode provider.
 */
typealias ClassBytecodeProvider = (internalClassName: String) -> ByteArray?

/**
 * Reports whether a class has already been initialized in the target JVM.
 * Used by the safety analyzer to decide whether a `GETSTATIC` could trigger a side-effecting `<clinit>`.
 *
 * The argument is the internal class name (JVM format, e.g. `"java/lang/String"`).
 * Returning `true` indicates that the class has already been initialized;
 * false means that the class is not yet initialized or initialization status is unknown.
 *
 * On javaagent side the function [isClassAlreadyLoaded] can be used as the class loading status checker.
 */
typealias ClassLoadStatusChecker = (internalClassName: String) -> Boolean

/**
 * Describes a static or virtual method invocation (`invoke{static,virtual,interface,special}`).
 */
internal data class MethodInvocationInfo(
    val opcode: Int,
    val owner: String,
    val name: String,
    val descriptor: String,
    val fileName: String?,
    val lineNumber: Int,
)

/**
 * Describes an `invokedynamic` invocation.
 * Carries the bootstrap-method owner/name, plus the call-site source location.
 */
internal data class DynamicInvocationInfo(
    val bootstrapOwner: String,
    val bootstrapName: String,
    val fileName: String?,
    val lineNumber: Int,
)

/**
 * Describes a static field read (`GETSTATIC`).
 * Carries the declaring class, field name, plus the call-site source location.
 */
internal data class StaticFieldReadInfo(
    val owner: String,
    val name: String,
    val fileName: String?,
    val lineNumber: Int,
)

internal typealias MethodInvocationPredicate = (info: MethodInvocationInfo) -> Boolean
internal typealias DynamicInvocationPredicate = (info: DynamicInvocationInfo) -> Boolean
internal typealias StaticFieldReadPredicate = (info: StaticFieldReadInfo) -> Boolean

/**
 * Verifies that bytecode of a given method is "safe" and do not cause side effects.
 *
 * A function is considered safe if it:
 * - Does NOT modify ANY variables (no PUTFIELD, PUTSTATIC, *STORE, *ASTORE operations)
 * - Only reads fields/arrays (GETFIELD, GETSTATIC, *ALOAD operations are allowed)
 * - Only performs pure computations (arithmetic, comparisons, stack operations)
 * - Only calls allowed safe static JDK methods (e.g., Math.*, Integer.valueOf)
 * - Only calls allowed safe instance methods on final classes (e.g., String.length, Integer.intValue)
 */
object SideEffectChecker {

    /**
     * Checks if a method's bytecode is free of side effects by analyzing its instructions.
     *
     * Bytecode retrieval is kept orthogonal to the analysis:
     * the caller supplies [bytecodeProvider] that should resolve class name to its bytecode.
     * An implementation of the bytecode provider can rely on class loading, reading class bytes from files, etc.
     *
     * Same approach is used to check if a class is already initialized in the target JVM:
     * caller should supply the [isClassLoaded] function that reports whether the class has already been initialized.
     * The default is conservative: it always returns `false`;
     * as such by default every static read access is flagged.
     *
     * The analysis itself never touches a [ClassLoader] or [LincheckInstrumentation],
     * so it can be used outside javaagent (and the target JVM in general).
     *
     * @param className The fully qualified class name.
     * @param methodName The method name to check.
     * @param methodDescriptor The method descriptor (e.g., `"()V"`).
     * @param bytecodeProvider Looks up class bytes by internal class name.
     *   Called for the target class, each parent class while walking the hierarchy, and any non-stdlib
     *   class reached through recursive method analysis.
     *   A `null` result is reported as [SafetyViolation.ClassNotAccessible].
     *   Use [findClassBytecode] when the bytecode is accessible via a [ClassLoader].
     * @param isClassLoaded Answers "is this class already initialized in the target JVM?".
     *   Reading a static field of a not-yet-initialized class is reported as
     *   [SafetyViolation.UninitializedClassStaticFieldRead]
     *   (class init is itself a side effect).
     *   Default `{ false }` — conservative, every external `GETSTATIC` is flagged.
     * @return [DisallowedMethodCall] with a tree of violations if side effects found,
     *   `null` if the method is safe.
     */
    fun checkMethodForSideEffects(
        className: String,
        methodName: String,
        methodDescriptor: String,
        bytecodeProvider: ClassBytecodeProvider,
        isClassLoaded: ClassLoadStatusChecker = { false },
        allowedFunctionCalls: FunctionCallPredicate = { _, _, _ -> false }
    ): DisallowedMethodCall? = try {
        checkMethodForSideEffects(
            internalClassName = className.toInternalClassName(),
            methodName = methodName,
            methodDescriptor = methodDescriptor,
            bytecodeProvider = bytecodeProvider,
            isClassLoaded = isClassLoaded,
            maxCallDepth = 5,
            callerFileName = null,
            callerLineNumber = -1,
            allowedFunctionCalls = allowedFunctionCalls
        )
    } catch (t: Throwable) {
        DisallowedMethodCall(
            fileName = null,
            lineNumber = -1,
            owner = className,
            methodName = methodName,
            causes = listOf(
                AnalysisFailed(
                    cause = t
                )
            )
        )
    }

    /**
     * Analyzes method bytecode for side effects (writes, disallowed calls).
     * @return DisallowedMethodCall if violations found, `null` otherwise.
     */
    private fun checkMethodForSideEffects(
        internalClassName: String,
        methodName: String,
        methodDescriptor: String,
        bytecodeProvider: ClassBytecodeProvider,
        isClassLoaded: ClassLoadStatusChecker,
        maxCallDepth: Int,
        callerFileName: String?,
        callerLineNumber: Int,
        allowedFunctionCalls: FunctionCallPredicate
    ): DisallowedMethodCall? {
        // Find the method in the class hierarchy.
        val (classNode, methodNode) = findMethodInHierarchy(internalClassName, methodName, methodDescriptor, bytecodeProvider)
            ?: return DisallowedMethodCall(
                fileName = null,
                lineNumber = -1,
                owner = internalClassName,
                methodName = methodName,
                causes = listOf(ClassNotAccessible(null, -1, internalClassName.toCanonicalClassName()))
            )
        val fileName = classNode.sourceFile
        val methodAccess = methodNode.access
        val classAccess = classNode.access

        // The method cannot be overridden at runtime if it is declared `final`, `private`, or `static`,
        // or if its enclosing class is `final`; which transitively prevents override of every method on it —
        // Java's `final class`, Kotlin's default class declaration.
        val isEffectivelyFinal =
            (methodAccess and ACC_FINAL) != 0 ||
            (methodAccess and ACC_PRIVATE) != 0 ||
            (methodAccess and ACC_STATIC) != 0 ||
            (classAccess and ACC_FINAL) != 0

        // Method must be effectively final to be safe.
        // If it is not, skip the rest of the analysis — the override could do anything at runtime,
        // so further checks (instructions, loops) would describe one possible implementation only.
        if (!isEffectivelyFinal) {
            return DisallowedMethodCall(
                fileName = callerFileName,
                lineNumber = callerLineNumber,
                owner = internalClassName.toCanonicalClassName(),
                methodName = methodName,
                causes = listOf(MethodNotFinal(fileName, -1, methodName)),
            )
        }

        // Compose policy for SideEffectMethodAnalyzer:
        //   allowed   = whitelist + user-supplied predicate
        //   forbidden = stdlib classes (filtered by allow-wins inside the visitor)
        val analyzerAllowedFunctionCalls: MethodInvocationPredicate = { info ->
            isSafeMethod("${info.owner}.${info.name}", info.opcode) ||
                allowedFunctionCalls(info.owner, info.name, info.descriptor)
        }
        val analyzerForbiddenFunctionCalls: MethodInvocationPredicate = { info ->
            isStandardLibraryClass(info.owner)
        }

        // For invokedynamic, anything outside the allowlist is forbidden — the deferred
        // bucket should stay empty under this policy. The two predicates are kept
        // disjoint so the visitor's allow/forbid overlap WARN doesn't fire for every
        // safe `invokedynamic` (e.g. `StringConcatFactory.makeConcatWithConstants`).
        val analyzerAllowedDynamicInvocations: DynamicInvocationPredicate = { info ->
            "${info.bootstrapOwner}.${info.bootstrapName}" in SAFE_DYNAMIC_INVOCATIONS
        }
        val analyzerForbiddenDynamicInvocations: DynamicInvocationPredicate = { info ->
            "${info.bootstrapOwner}.${info.bootstrapName}" !in SAFE_DYNAMIC_INVOCATIONS
        }

        // A static field read is safe when it cannot trigger a side-effecting `<clinit>`:
        //   - same class as the method under analysis - `<clinit>` is already running,
        //   - stdlib class - `<clinit>` likely already fired by the time user code runs;
        //     a workaround for the caller's `isClassLoaded` typically missing bootstrap-loaded
        //     classes (their `Class.classLoader` is `null`),
        //   - class already loaded by the JVM (per caller-supplied `isClassLoaded`) -
        //     `<clinit>` has already fired.
        val analyzerIsSafeStaticFieldRead: StaticFieldReadPredicate = { info ->
            info.owner == classNode.name ||
            isStandardLibraryClass(info.owner) ||
            isClassLoaded(info.owner)
        }

        val analyzer = SideEffectMethodAnalyzer(
            fileName = fileName,
            allowedFunctionCalls = analyzerAllowedFunctionCalls,
            forbiddenFunctionCalls = analyzerForbiddenFunctionCalls,
            allowedDynamicInvocations = analyzerAllowedDynamicInvocations,
            forbiddenDynamicInvocations = analyzerForbiddenDynamicInvocations,
            isSafeStaticFieldRead = analyzerIsSafeStaticFieldRead,
        )
        methodNode.accept(analyzer)

        // Resolve deferred method invocations: recurse with reduced call depth,
        // or emit `MaxCallDepthExceeded` when the budget is exhausted.
        val deferredViolations = analyzer.methodInvocations.mapNotNull { call ->
            if (maxCallDepth == 0) {
                MaxCallDepthExceeded(call.fileName, call.lineNumber, call.owner, call.name)
            } else {
                checkMethodForSideEffects(
                    internalClassName = call.owner,
                    methodName = call.name,
                    methodDescriptor = call.descriptor,
                    bytecodeProvider = bytecodeProvider,
                    isClassLoaded = isClassLoaded,
                    maxCallDepth = maxCallDepth - 1,
                    callerFileName = call.fileName,
                    callerLineNumber = call.lineNumber,
                    allowedFunctionCalls = allowedFunctionCalls,
                )
            }
        }
        val instructionViolations = analyzer.violations + deferredViolations

        // Detect loops via the control-flow graph.
        val loopViolations = checkForLoops(classNode, methodNode)

        // Consolidate violations and sort by line number.
        val allViolations = (instructionViolations + loopViolations).distinct().sortedBy { it.lineNumber }
        if (allViolations.isEmpty()) return null
        return DisallowedMethodCall(
            fileName = callerFileName,
            lineNumber = callerLineNumber,
            owner = internalClassName.toCanonicalClassName(),
            methodName = methodName,
            causes = allViolations,
        )
    }

    /**
     * Returns `true` if the method (by `owner.name` key and opcode) is
     * on the static or final-class instance allowlist of pure, side-effect-free methods.
     */
    private fun isSafeMethod(methodKey: String, opcode: Int): Boolean {
        if (opcode == INVOKESTATIC && methodKey in SAFE_STATIC_METHODS) return true
        if (methodKey in SAFE_FINAL_CLASS_METHODS) return true
        return false
    }

    /**
     * Returns `true` if the internal class name belongs to the standard Java/Kotlin library.
     */
    private fun isStandardLibraryClass(owner: String): Boolean =
        STANDARD_LIBRARY_PREFIXES.any { owner.startsWith(it) }

    /**
     * Checks for loops in a method by detecting back edges in the control flow graph.
     * Back edges indicate loops and can be detected even in irreducible CFGs.
     */
    private fun checkForLoops(
        classNode: ClassNode,
        methodNode: MethodNode
    ): List<SafetyViolation> {
        // Build a control-flow graph and compute back edges.
        val cfg = buildControlFlowGraph(classNode.name, methodNode)
        cfg.computeLoopInformation()
        val backEdges = cfg.backEdges!!
        // For each back edge, find the line number of the loop header.
        val uniqueLoopHeaders = backEdges.map { it.target }.distinct()
        return uniqueLoopHeaders.map { headerIndex ->
            val headerBlock = cfg.basicBlocks[headerIndex]
            // First, try to find a line number in the header block itself.
            var lineNumber = headerBlock.range.firstNotNullOfOrNull {
                cfg.instructions.get(it) as? LineNumberNode
            }?.line
            // If not found, search backward through all instructions before the header.
            if (lineNumber == null) {
                val headerStartIndex = headerBlock.range.first()
                lineNumber = (headerStartIndex - 1 downTo 0).firstNotNullOfOrNull {
                    cfg.instructions.get(it) as? LineNumberNode
                }?.line
            }
            // Construct a violation.
            LoopDetected(classNode.sourceFile, lineNumber ?: -1)
        }
    }

    /**
     * Finds a method in the class hierarchy by traversing parent classes.
     * Returns the method node if found, null otherwise.
     */
    private fun findMethodInHierarchy(
        internalClassName: String,
        methodName: String,
        methodDescriptor: String,
        bytecodeProvider: ClassBytecodeProvider
    ): Pair<ClassNode, MethodNode>? {
        var currentInternalClassName = internalClassName

        while (true) {
            val classNode = ClassNode()
            val bytecode = bytecodeProvider(currentInternalClassName) ?: return null
            ClassReader(bytecode).accept(classNode, ClassReader.SKIP_FRAMES)
            // Check the current class for the method
            val methodNode = classNode.methods.firstOrNull {
                it.name == methodName && it.desc == methodDescriptor
            }
            if (methodNode != null) {
                return classNode to methodNode
            }
            // Move to the parent class
            val superName = classNode.superName
            if (superName == null || superName == "java/lang/Object") break
            currentInternalClassName = superName
        }
        // Not found
        return null
    }
}

/**
 * Method-level bytecode visitor that checks if the visitor is safe (i.e., pure side-effect-free).
 *
 * Marks operations that are always unsafe regardless of caller policy:
 *     - field/array writes;
 *     - monitor enter/exit.
 *
 * Static field reads (`GETSTATIC`) are classified by [isSafeStaticFieldRead];
 * reads not marked safe are reported as `UninitializedClassStaticFieldRead` violations.
 *
 * Method invocations are classified into three buckets:
 *
 * 1. **Allowed method invocations** — method and `invokedynamic` calls
 *    matched by [allowedFunctionCalls] / [allowedDynamicInvocations] are silently dropped.
 *
 * 2. **Direct violations** ([violations]) — method and `invokedynamic` calls
 *    matched by [forbiddenFunctionCalls] / [forbiddenDynamicInvocations] are reported as safety violations.
 *
 * 3. **Deferred invocations** ([methodInvocations], [dynamicInvocations]) — calls matched by neither predicate.
 *    The caller is expected to analyze them further (e.g., by recursing into the callee's bytecode)
 *    and merge the resulting violations into its own report.
 *
 * **Conflict resolution: allow-wins.** If a call matches both the allow and the forbid predicate,
 * it is dropped (the allow branch takes precedence) and a `WARN` log entry is emitted.
 * Overlap between the two predicates typically indicates a policy bug —
 * keeping them disjoint is the caller's responsibility.
 *
 * The visitor is fully policy-free: it consults the supplied predicates only and holds no built-in
 * knowledge of allowlists, stdlib prefixes, classloaders, or the enclosing class —
 * all such context lives at the caller.
 */
internal class SideEffectMethodAnalyzer(
    private val fileName: String?,
    private val allowedFunctionCalls: MethodInvocationPredicate,
    private val forbiddenFunctionCalls: MethodInvocationPredicate,
    private val allowedDynamicInvocations: DynamicInvocationPredicate,
    private val forbiddenDynamicInvocations: DynamicInvocationPredicate,
    private val isSafeStaticFieldRead: StaticFieldReadPredicate,
) : MethodVisitor(ASM_API) {

    private val _violations = mutableListOf<SafetyViolation>()
    val violations: List<SafetyViolation> get() = _violations

    private val _methodInvocations = mutableListOf<MethodInvocationInfo>()
    val methodInvocations: List<MethodInvocationInfo> get() = _methodInvocations

    private val _dynamicInvocations = mutableListOf<DynamicInvocationInfo>()
    val dynamicInvocations: List<DynamicInvocationInfo> get() = _dynamicInvocations

    private var currentLineNumber: Int = -1

    override fun visitLineNumber(line: Int, start: Label) {
        currentLineNumber = line
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        when (opcode) {
            PUTFIELD, PUTSTATIC -> {
                _violations.add(
                    FieldWrite(fileName, currentLineNumber, owner, name)
                )
            }

            GETSTATIC -> {
                val info = StaticFieldReadInfo(owner, name, fileName, currentLineNumber)
                if (!isSafeStaticFieldRead(info)) {
                    _violations.add(
                        UninitializedClassStaticFieldRead(fileName, currentLineNumber, owner, name)
                    )
                }
            }
        }
    }

    override fun visitInsn(opcode: Int) {
        // Reject array writes (reads are OK).
        if (isArrayStoreOpcode(opcode)) {
            _violations.add(
                ArrayWrite(fileName, currentLineNumber)
            )
        }
        // Reject monitor operations (synchronized blocks).
        if (opcode == MONITORENTER || opcode == MONITOREXIT) {
            _violations.add(
                MonitorOperation(fileName, currentLineNumber)
            )
        }
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean
    ) {
        val info = MethodInvocationInfo(opcode, owner, name, descriptor, fileName, currentLineNumber)
        val isAllowed = allowedFunctionCalls(info)
        val isForbidden = forbiddenFunctionCalls(info)
        if (isAllowed && isForbidden) {
            Logger.warn {
                "Method invocation $owner.$name$descriptor matched " +
                "both allow and forbid predicates; allow-wins."
            }
        }
        when {
            isAllowed -> Unit // drop
            isForbidden -> _violations.add(
                DisallowedMethodCall(fileName, currentLineNumber, owner, name)
            )
            else -> _methodInvocations.add(info)
        }
    }

    override fun visitInvokeDynamicInsn(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Handle,
        vararg bootstrapMethodArguments: Any?
    ) {
        val info = DynamicInvocationInfo(
            bootstrapOwner = bootstrapMethodHandle.owner,
            bootstrapName = bootstrapMethodHandle.name,
            fileName = fileName,
            lineNumber = currentLineNumber,
        )
        val isAllowed = allowedDynamicInvocations(info)
        val isForbidden = forbiddenDynamicInvocations(info)
        if (isAllowed && isForbidden) {
            Logger.warn {
                "Dynamic invocation ${info.bootstrapOwner}.${info.bootstrapName} matched " +
                "both allow and forbid predicates; allow-wins."
            }
        }
        when {
            isAllowed -> Unit // drop
            isForbidden -> _violations.add(
                DisallowedDynamicInvocation(fileName, currentLineNumber, info.bootstrapOwner, info.bootstrapName)
            )
            else -> _dynamicInvocations.add(info)
        }
    }
}

// Whitelist of safe static JDK methods (pure functions with no side effects)
internal val SAFE_STATIC_METHODS = setOf(
    // Math operations
    "java/lang/Math.abs",
    "java/lang/Math.max",
    "java/lang/Math.min",
    "java/lang/Math.sqrt",
    "java/lang/Math.floor",
    "java/lang/Math.ceil",
    "java/lang/Math.round",
    "java/lang/StrictMath.abs",
    "java/lang/StrictMath.max",
    "java/lang/StrictMath.min",

    // Primitive wrapper static methods (boxing, parsing)
    "java/lang/Integer.valueOf",
    "java/lang/Integer.parseInt",
    "java/lang/Integer.bitCount",
    "java/lang/Long.valueOf",
    "java/lang/Long.parseLong",
    "java/lang/Boolean.valueOf",
    "java/lang/Double.valueOf",
    "java/lang/Double.parseDouble",
    "java/lang/Float.valueOf",
    "java/lang/Float.parseFloat",
)

// Whitelist of safe instance methods on final classes (cannot be overridden, no side effects)
internal val SAFE_FINAL_CLASS_METHODS = setOf(
    // String read operations (String is final)
    "java/lang/String.length",
    "java/lang/String.charAt",
    "java/lang/String.isEmpty",
    "java/lang/String.substring",
    "java/lang/String.equals",
    "java/lang/String.equalsIgnoreCase",
    "java/lang/String.compareTo",
    "java/lang/String.compareToIgnoreCase",
    "java/lang/String.contains",
    "java/lang/String.startsWith",
    "java/lang/String.endsWith",
    "java/lang/String.indexOf",
    "java/lang/String.lastIndexOf",
    "java/lang/String.toLowerCase",
    "java/lang/String.toUpperCase",
    "java/lang/String.trim",
    "java/lang/String.toCharArray",
    "java/lang/String.hashCode",
    "java/lang/String.toString",

    // Primitive wrapper instance methods (unboxing, inspection)
    "java/lang/Integer.intValue",
    "java/lang/Integer.hashCode",
    "java/lang/Integer.toString",
    "java/lang/Integer.equals",
    "java/lang/Long.longValue",
    "java/lang/Long.hashCode",
    "java/lang/Long.toString",
    "java/lang/Long.equals",
    "java/lang/Boolean.booleanValue",
    "java/lang/Boolean.hashCode",
    "java/lang/Boolean.toString",
    "java/lang/Boolean.equals",
    "java/lang/Double.doubleValue",
    "java/lang/Double.hashCode",
    "java/lang/Double.toString",
    "java/lang/Double.equals",
    "java/lang/Float.floatValue",
    "java/lang/Float.hashCode",
    "java/lang/Float.toString",
    "java/lang/Float.equals",
    "java/lang/Short.shortValue",
    "java/lang/Short.hashCode",
    "java/lang/Short.toString",
    "java/lang/Short.equals",
    "java/lang/Byte.byteValue",
    "java/lang/Byte.hashCode",
    "java/lang/Byte.toString",
    "java/lang/Byte.equals",
    "java/lang/Character.charValue",
    "java/lang/Character.hashCode",
    "java/lang/Character.toString",
    "java/lang/Character.equals",

    // Kotlin stdlib read-only factory methods
    "kotlin/collections/CollectionsKt.listOf",
    "kotlin/collections/CollectionsKt.emptyList",
    "kotlin/collections/CollectionsKt.setOf",
    "kotlin/collections/CollectionsKt.emptySet",
    "kotlin/collections/CollectionsKt.mapOf",
    "kotlin/collections/CollectionsKt.emptyMap",
    "kotlin/ranges/RangesKt.rangeTo",
    "kotlin/ranges/RangesKt.until",
    "kotlin/ranges/RangesKt.downTo",
    "kotlin/ranges/RangesKt.coerceIn",
    "kotlin/ranges/RangesKt.coerceAtLeast",
    "kotlin/ranges/RangesKt.coerceAtMost",

    // Kotlin extension functions for String (compiled to static methods)
    "kotlin/text/StringsKt.contains",
    "kotlin/text/StringsKt.startsWith",
    "kotlin/text/StringsKt.endsWith",

    // Object final methods (cannot be overridden)
    "java/lang/Object.getClass",

    // Class inspection methods (Class is final, all inspection methods are read-only)
    "java/lang/Class.getName",
    "java/lang/Class.getSimpleName",
    "java/lang/Class.getCanonicalName",
    "java/lang/Class.getTypeName",
    "java/lang/Class.isArray",
    "java/lang/Class.isPrimitive",
    "java/lang/Class.isInterface",
    "java/lang/Class.isAnnotation",
    "java/lang/Class.isEnum",
    "java/lang/Class.isAnonymousClass",
    "java/lang/Class.isLocalClass",
    "java/lang/Class.isMemberClass",
    "java/lang/Class.getSuperclass",
    "java/lang/Class.getInterfaces",
    "java/lang/Class.getComponentType",
    "java/lang/Class.getEnclosingClass",
    "java/lang/Class.toString",
    "java/lang/Class.toGenericString",

    // Java reflection read-only inspection methods (Field, Method, Constructor are final classes)
    "java/lang/reflect/Field.getName",
    "java/lang/reflect/Field.getType",
    "java/lang/reflect/Field.getGenericType",
    "java/lang/reflect/Field.getDeclaringClass",
    "java/lang/reflect/Field.getModifiers",
    "java/lang/reflect/Field.isSynthetic",
    "java/lang/reflect/Field.isEnumConstant",
    "java/lang/reflect/Field.toString",
    "java/lang/reflect/Method.getName",
    "java/lang/reflect/Method.getReturnType",
    "java/lang/reflect/Method.getGenericReturnType",
    "java/lang/reflect/Method.getParameterTypes",
    "java/lang/reflect/Method.getGenericParameterTypes",
    "java/lang/reflect/Method.getParameterCount",
    "java/lang/reflect/Method.getDeclaringClass",
    "java/lang/reflect/Method.getModifiers",
    "java/lang/reflect/Method.isSynthetic",
    "java/lang/reflect/Method.isBridge",
    "java/lang/reflect/Method.isDefault",
    "java/lang/reflect/Method.isVarArgs",
    "java/lang/reflect/Method.toString",
    "java/lang/reflect/Constructor.getName",
    "java/lang/reflect/Constructor.getParameterTypes",
    "java/lang/reflect/Constructor.getGenericParameterTypes",
    "java/lang/reflect/Constructor.getParameterCount",
    "java/lang/reflect/Constructor.getDeclaringClass",
    "java/lang/reflect/Constructor.getModifiers",
    "java/lang/reflect/Constructor.isSynthetic",
    "java/lang/reflect/Constructor.isVarArgs",
    "java/lang/reflect/Constructor.toString",
    "java/lang/reflect/Modifier.isPublic",
    "java/lang/reflect/Modifier.isPrivate",
    "java/lang/reflect/Modifier.isProtected",
    "java/lang/reflect/Modifier.isStatic",
    "java/lang/reflect/Modifier.isFinal",
    "java/lang/reflect/Modifier.isSynchronized",
    "java/lang/reflect/Modifier.isVolatile",
    "java/lang/reflect/Modifier.isTransient",
    "java/lang/reflect/Modifier.isNative",
    "java/lang/reflect/Modifier.isInterface",
    "java/lang/reflect/Modifier.isAbstract",
    "java/lang/reflect/Modifier.isStrict",
    "java/lang/reflect/Modifier.toString",

    // Kotlin reflection read-only methods
    "kotlin/reflect/KClass.getSimpleName",
    "kotlin/reflect/KClass.getQualifiedName",
    "kotlin/reflect/KCallable.getName",

    // Constable interface (Java 12+) - returns an Optional describing this instance
    "java/lang/constant/Constable.describeConstable",

    // ConstantDesc and related classes (Java 12+, java.lang.constant package)
    // These are value-based descriptor classes used for constant pool entries
    // Note: resolveConstantDesc is intentionally NOT allowed as it has side effects
    "java/lang/constant/ConstantDesc.describeConstable",
    "java/lang/constant/ConstantDesc.equals",
    "java/lang/constant/ConstantDesc.hashCode",
    "java/lang/constant/ConstantDesc.toString",
    "java/lang/constant/ClassDesc.of",
    "java/lang/constant/ClassDesc.ofDescriptor",
    "java/lang/constant/ClassDesc.ofInternalName",
    "java/lang/constant/ClassDesc.packageName",
    "java/lang/constant/ClassDesc.displayName",
    "java/lang/constant/ClassDesc.descriptorString",
    "java/lang/constant/ClassDesc.arrayType",
    "java/lang/constant/ClassDesc.nested",
    "java/lang/constant/ClassDesc.isArray",
    "java/lang/constant/ClassDesc.isPrimitive",
    "java/lang/constant/ClassDesc.isClassOrInterface",
    "java/lang/constant/ClassDesc.componentType",
    "java/lang/constant/ClassDesc.equals",
    "java/lang/constant/ClassDesc.hashCode",
    "java/lang/constant/ClassDesc.toString",
    "java/lang/constant/MethodTypeDesc.of",
    "java/lang/constant/MethodTypeDesc.ofDescriptor",
    "java/lang/constant/MethodTypeDesc.returnType",
    "java/lang/constant/MethodTypeDesc.parameterCount",
    "java/lang/constant/MethodTypeDesc.parameterType",
    "java/lang/constant/MethodTypeDesc.parameterList",
    "java/lang/constant/MethodTypeDesc.parameterArray",
    "java/lang/constant/MethodTypeDesc.changeReturnType",
    "java/lang/constant/MethodTypeDesc.changeParameterType",
    "java/lang/constant/MethodTypeDesc.dropParameterTypes",
    "java/lang/constant/MethodTypeDesc.insertParameterTypes",
    "java/lang/constant/MethodTypeDesc.descriptorString",
    "java/lang/constant/MethodTypeDesc.displayDescriptor",
    "java/lang/constant/MethodTypeDesc.equals",
    "java/lang/constant/MethodTypeDesc.hashCode",
    "java/lang/constant/MethodTypeDesc.toString",
    "java/lang/constant/MethodHandleDesc.of",
    "java/lang/constant/MethodHandleDesc.ofMethod",
    "java/lang/constant/MethodHandleDesc.ofField",
    "java/lang/constant/MethodHandleDesc.ofConstructor",
    "java/lang/constant/MethodHandleDesc.asType",
    "java/lang/constant/MethodHandleDesc.invocationType",
    "java/lang/constant/MethodHandleDesc.equals",
    "java/lang/constant/MethodHandleDesc.hashCode",
    "java/lang/constant/MethodHandleDesc.toString",
    "java/lang/constant/DirectMethodHandleDesc.kind",
    "java/lang/constant/DirectMethodHandleDesc.refKind",
    "java/lang/constant/DirectMethodHandleDesc.isOwnerInterface",
    "java/lang/constant/DirectMethodHandleDesc.owner",
    "java/lang/constant/DirectMethodHandleDesc.methodName",
    "java/lang/constant/DirectMethodHandleDesc.lookupDescriptor",
    "java/lang/constant/DynamicConstantDesc.ofCanonical",
    "java/lang/constant/DynamicConstantDesc.ofNamed",
    "java/lang/constant/DynamicConstantDesc.of",
    "java/lang/constant/DynamicConstantDesc.constantName",
    "java/lang/constant/DynamicConstantDesc.constantType",
    "java/lang/constant/DynamicConstantDesc.bootstrapMethod",
    "java/lang/constant/DynamicConstantDesc.bootstrapArgs",
    "java/lang/constant/DynamicConstantDesc.bootstrapArgsList",
    "java/lang/constant/DynamicConstantDesc.equals",
    "java/lang/constant/DynamicConstantDesc.hashCode",
    "java/lang/constant/DynamicConstantDesc.toString",
    "java/lang/constant/DynamicCallSiteDesc.of",
    "java/lang/constant/DynamicCallSiteDesc.withArgs",
    "java/lang/constant/DynamicCallSiteDesc.withNameAndType",
    "java/lang/constant/DynamicCallSiteDesc.invocationName",
    "java/lang/constant/DynamicCallSiteDesc.invocationType",
    "java/lang/constant/DynamicCallSiteDesc.bootstrapMethod",
    "java/lang/constant/DynamicCallSiteDesc.bootstrapArgs",
    "java/lang/constant/DynamicCallSiteDesc.equals",
    "java/lang/constant/DynamicCallSiteDesc.hashCode",
    "java/lang/constant/DynamicCallSiteDesc.toString",
    // ConstantDescs utility class with constant descriptors
    "java/lang/constant/ConstantDescs.ofCallsiteBootstrap",
    "java/lang/constant/ConstantDescs.ofConstantBootstrap",

    // Safe Thread methods (read-only inspection methods)
    "java/lang/Thread.getName",
    "java/lang/Thread.getId",
    "java/lang/Thread.getState",
    "java/lang/Thread.getPriority",
    "java/lang/Thread.isDaemon",
    "java/lang/Thread.isAlive",
    "java/lang/Thread.isInterrupted",
    "java/lang/Thread.currentThread",
)

// Whitelist of safe dynamic invocation bootstrap methods (invokedynamic operations)
internal val SAFE_DYNAMIC_INVOCATIONS = setOf(
    // String concatenation (Java 9+)
    "java/lang/invoke/StringConcatFactory.makeConcatWithConstants",
    "java/lang/invoke/StringConcatFactory.makeConcat",
)

private val STANDARD_LIBRARY_PREFIXES = listOf(
    "java/", "javax/", "kotlin/", "sun/", "jdk/"
)