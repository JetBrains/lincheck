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
import org.jetbrains.lincheck.jvm.agent.analysis.SideEffectViolation.*
import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.*
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.*

/**
 * Represents a side-effect violation found during bytecode analysis.
 */
sealed class SideEffectViolation {
    abstract val fileName: String?
    abstract val lineNumber: Int

    protected val fileNameOrUnknown: String get() = fileName ?: "<unknown_source>"

    data class FieldWrite(
        override val fileName: String?,
        override val lineNumber: Int,
        val owner: String,
        val fieldName: String
    ) : SideEffectViolation() {
        override fun toString() = "Field write: $fieldName at $fileNameOrUnknown:$lineNumber"
    }

    data class UninitializedClassStaticFieldRead(
        override val fileName: String?,
        override val lineNumber: Int,
        val owner: String,
        val fieldName: String
    ) : SideEffectViolation() {
        override fun toString() = "Field read of uninitialized class: ${owner.toCanonicalClassName().toSimpleClassName()}.$fieldName at $fileNameOrUnknown:$lineNumber"
    }

    data class ArrayWrite(
        override val fileName: String?,
        override val lineNumber: Int
    ) : SideEffectViolation() {
        override fun toString() = "Array write: at $fileNameOrUnknown:$lineNumber"
    }

    data class MonitorOperation(
        override val fileName: String?,
        override val lineNumber: Int
    ) : SideEffectViolation() {
        override fun toString() = "Monitor operation (synchronized block) at $fileNameOrUnknown:$lineNumber"
    }

    data class DisallowedMethodCall(
        override val fileName: String?,
        override val lineNumber: Int,
        val owner: String,
        val methodName: String,
        val causes: List<SideEffectViolation> = emptyList()
    ) : SideEffectViolation() {
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
    ) : SideEffectViolation() {
        override fun toString(): String {
            return "Maximum call depth exceeded: $methodName at $fileNameOrUnknown:$lineNumber"
        }
    }

    data class DisallowedDynamicInvocation(
        override val fileName: String?,
        override val lineNumber: Int,
        val bootstrapOwner: String,
        val bootstrapName: String
    ) : SideEffectViolation() {
        override fun toString(): String {
            return "Disallowed dynamic invocation: $bootstrapName at $fileNameOrUnknown:$lineNumber"
        }
    }

    data class ClassNotAccessible(
        override val fileName: String?,
        override val lineNumber: Int,
        val className: String
    ) : SideEffectViolation() {
        override fun toString() = "Class $className is not accessible or its bytecode cannot be loaded"
    }

    data class MethodNotFinal(
        override val fileName: String?,
        override val lineNumber: Int,
        val methodName: String
    ) : SideEffectViolation() {
        override fun toString() = "Method $methodName is not final and can be overridden at runtime at $fileNameOrUnknown:$lineNumber"
    }

    data class LoopDetected(
        override val fileName: String?,
        override val lineNumber: Int
    ) : SideEffectViolation() {
        override fun toString() = "Loop detected at $fileNameOrUnknown:$lineNumber"
    }
}

/**
 * Verifies that user conditions in injected bytecode are "safe" and do not cause side effects.
 *
 * A condition function is considered safe if it:
 * - Does NOT modify ANY variables (no PUTFIELD, PUTSTATIC, *STORE, *ASTORE operations)
 * - Only reads fields/arrays (GETFIELD, GETSTATIC, *ALOAD operations are allowed)
 * - Only performs pure computations (arithmetic, comparisons, stack operations)
 * - Only calls whitelisted static JDK methods (e.g., Math.*, Integer.valueOf)
 * - Only calls whitelisted instance methods on final classes (e.g., String.length, Integer.intValue)
 */
object ConditionSafetyChecker {

    /**
     * Checks if a method's bytecode is free of side effects by analyzing its instructions.
     *
     * @param className The fully qualified class name
     * @param methodName The method name to check
     * @param methodDescriptor The method descriptor (e.g., "()V")
     * @param classLoader The ClassLoader to use for loading class bytecode
     * @return DisallowedMethodCall with a tree of violations if side effects found, `null` if the method is safe
     */
    fun checkMethodForSideEffects(
        className: String,
        methodName: String,
        methodDescriptor: String,
        classLoader: ClassLoader,
    ): DisallowedMethodCall? = checkMethodForSideEffectsInternal(
        className = className,
        methodName = methodName,
        methodDescriptor = methodDescriptor,
        classLoader = classLoader,
        maxCallDepth = 5,
    )

    /**
     * Analyzes method bytecode for side effects (writes, disallowed calls).
     * @return DisallowedMethodCall if violations found, `null` otherwise.
     */
    private fun checkMethodForSideEffectsInternal(
        className: String,
        methodName: String,
        methodDescriptor: String,
        classLoader: ClassLoader,
        maxCallDepth: Int
    ): DisallowedMethodCall? {
        // Load class bytecode.
        val classBytes = loadClassBytes(className, classLoader)
        if (classBytes == null) {
            val causes = listOf(ClassNotAccessible(null, -1, className))
            return DisallowedMethodCall(null, -1, className.toInternalClassName(), methodName, causes)
        }

        // Parse class to check for loops
        val classNode = ClassNode()
        ClassReader(classBytes).accept(classNode, ClassReader.SKIP_FRAMES)
        val loopViolations = checkForLoops(className.toInternalClassName(), classNode, methodName, methodDescriptor)

        // Analyze the method's bytecode for other violations
        val safetyAnalyzer = SafetyClassAnalyzer(
            targetMethodName = methodName,
            targetMethodDescriptor = methodDescriptor,
            classLoader = classLoader,
            maxCallDepth = maxCallDepth
        )
        ClassReader(classBytes).accept(safetyAnalyzer, ClassReader.SKIP_FRAMES)

        val allViolations = loopViolations + safetyAnalyzer.violations
        return if (allViolations.isEmpty()) {
            null
        } else {
            DisallowedMethodCall(
                fileName = null,
                lineNumber = -1,
                owner = className.toCanonicalClassName(),
                methodName = methodName,
                causes = allViolations
            )
        }
    }

    /**
     * Checks for loops in a method by detecting back edges in the control flow graph.
     * Back edges indicate loops and can be detected even in irreducible CFGs.
     */
    private fun checkForLoops(
        owner: String,
        classNode: ClassNode,
        methodName: String,
        methodDescriptor: String
    ): List<SideEffectViolation> {
        val methodNode = classNode.methods.firstOrNull {
            it.name == methodName && (methodDescriptor.isEmpty() || it.desc == methodDescriptor)
        } ?: return emptyList() // todo failed to analyze method violation (introduce a new violation type)

        val cfg = try {
            buildControlFlowGraph(owner, methodNode)
        } catch (_: Throwable) {
            return emptyList() // todo failed to analyze method violation (introduce a new violation type) and wrap the whole method with try-catch block
        }

        cfg.computeLoopInformation()
        val backEdges = cfg.backEdges ?: emptySet()

        if (backEdges.isEmpty()) return emptyList()

        // For each back edge, find the line number of the loop header
        // Use a set to track unique loop headers to avoid duplicates
        val loopHeaders = cfg.loopInfo!!.loops.map { it.header }.toSet()
        return loopHeaders.map { headerIndex ->
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

            LoopDetected(classNode.sourceFile, lineNumber ?: -1)
        }
    }


    /**
     * Loads class bytecode from the classloader.
     */
    private fun loadClassBytes(className: String, classLoader: ClassLoader): ByteArray? {
        val resourceName = className.toInternalClassName() + ".class"
        return classLoader.getResourceAsStream(resourceName)?.use { it.readBytes() }
    }

    private class SafetyClassAnalyzer(
        private val targetMethodName: String,
        private val targetMethodDescriptor: String,
        private val classLoader: ClassLoader,
        private val maxCallDepth: Int
    ) : ClassVisitor(ASM_API) {

        // We need it to construct violation objects.
        private var fileName: String? = null
        private var currentClassName: String? = null

        private var _violations = mutableListOf<SideEffectViolation>()
        val violations: List<SideEffectViolation> get() = _violations.distinct()

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?
        ) {
            currentClassName = name
        }

        override fun visitSource(source: String?, debug: String?) {
            if (source != null) {
                fileName = source
            }
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor? {
            // Only analyze the target method (skip others).
            if (name != targetMethodName) return null
            if (targetMethodDescriptor.isNotEmpty() && descriptor != targetMethodDescriptor) return null
            // Method must be final (or in a final class) to be safe.
            val isFinal = (access and ACC_FINAL) != 0
            val isPrivate = (access and ACC_PRIVATE) != 0
            val isStatic = (access and ACC_STATIC) != 0
            if (!isFinal && !isPrivate && !isStatic) {
                _violations.add(
                    MethodNotFinal(fileName, -1, name)
                )
                return null
            }
            // Analyze this method's instructions.
            return SafetyMethodAnalyzer(fileName, currentClassName, _violations, classLoader, maxCallDepth)
        }
    }


    /**
     * Method visitor that examines each instruction and detects unsafe operations.
     */
    private class SafetyMethodAnalyzer(
        private val fileName: String?,
        private val currentClassName: String?,
        private val violations: MutableList<SideEffectViolation>,
        private val classLoader: ClassLoader,
        private val maxCallDepth: Int
    ) : MethodVisitor(ASM_API) {

        private var currentLineNumber: Int = -1

        override fun visitLineNumber(line: Int, start: Label) {
            currentLineNumber = line
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
            when (opcode) {
                PUTFIELD, PUTSTATIC -> {
                    violations.add(
                        FieldWrite(fileName, currentLineNumber, owner, name)
                    )
                }
                GETSTATIC -> {
                    // Detect static field reads of uninitialized classes.
                    // Allow reading from the same class (it will be initialized by the time the method is called)
                    val isSameClass = currentClassName != null && owner == currentClassName
                    if (!isSameClass && !isStandardLibraryClass(owner) && !isClassAlreadyLoaded(owner, classLoader)) {
                        violations.add(
                            UninitializedClassStaticFieldRead(fileName, currentLineNumber, owner, name)
                        )
                    }
                }
            }
        }

        override fun visitInsn(opcode: Int) {
            // Reject array writes (reads are OK).
            if (isArrayStoreOpcode(opcode)) {
                violations.add(
                    ArrayWrite(fileName, currentLineNumber)
                )
            }
            // Reject monitor operations (synchronized blocks).
            if (opcode == MONITORENTER || opcode == MONITOREXIT) {
                violations.add(
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
            // Allow whitelisted methods.
            val methodKey = "$owner.$name"
            if (isWhitelistedMethod(methodKey, opcode)) {
                return
            }
            // Disallow methods from the standard Java library
            // that are not whitelisted.
            if (isStandardLibraryClass(owner)) {
                violations.add(
                    DisallowedMethodCall(fileName, currentLineNumber, owner, name)
                )
                return
            }
            // Check that the maximum call depth has not been exceeded.
            if (maxCallDepth == 0) {
                violations.add(
                    MaxCallDepthExceeded(fileName, currentLineNumber, owner, name)
                )
                return
            }
            // Allow only safe method calls.
            val methodCallViolationTree = checkMethodForSideEffectsInternal(
                className = owner.toCanonicalClassName(),
                methodName = name,
                methodDescriptor = descriptor,
                classLoader = classLoader,
                maxCallDepth = maxCallDepth - 1
            )
            methodCallViolationTree?.let { violations.add(it) }
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any?
        ) {
            // Allow whitelisted dynamic invocations.
            if (isWhitelistedDynamicInvocation(bootstrapMethodHandle)) {
                return
            }
            // Reject all other invokedynamic calls (lambdas, method references, etc.)
            violations.add(
                DisallowedDynamicInvocation(
                    fileName,
                    currentLineNumber,
                    bootstrapMethodHandle.owner,
                    bootstrapMethodHandle.name
                )
            )
        }

        /**
         * Checks if a method is in the whitelist (safe static or instance methods).
         */
        private fun isWhitelistedMethod(methodKey: String, opcode: Int): Boolean {
            // Allow whitelisted static methods
            if (opcode == INVOKESTATIC && methodKey in WHITELISTED_STATIC_METHODS) {
                return true
            }
            // Allow whitelisted instance methods on final classes
            if (methodKey in WHITELISTED_FINAL_CLASS_METHODS) {
                return true
            }
            return false
        }

        /**
         * Checks if an opcode is an array store instruction.
         */
        private fun isArrayStoreOpcode(opcode: Int) =
            opcode == IASTORE || opcode == LASTORE || opcode == FASTORE || opcode == DASTORE ||
            opcode == AASTORE || opcode == BASTORE || opcode == CASTORE || opcode == SASTORE

        /**
         * Checks if a class belongs to the standard Java/Kotlin library.
         */
        private fun isStandardLibraryClass(owner: String) =
            STANDARD_LIBRARY_PREFIXES.any { owner.startsWith(it) }

        /**
         * Checks if a dynamic invocation is whitelisted (safe invokedynamic operations).
         */
        private fun isWhitelistedDynamicInvocation(bootstrapMethodHandle: Handle): Boolean {
            val bootstrapKey = "${bootstrapMethodHandle.owner}.${bootstrapMethodHandle.name}"
            return bootstrapKey in WHITELISTED_DYNAMIC_INVOCATIONS
        }

        /**
         * Checks if a class has already been loaded by the JVM.
         * Reading fields from unloaded classes can trigger class initialization,
         * which may have side effects.
         */
        private fun isClassAlreadyLoaded(owner: String, classLoader: ClassLoader): Boolean {
            val className = owner.toCanonicalClassName()
            // If the class is not loaded, accessing its fields will trigger class initialization.
            return LincheckInstrumentation.isClassLoaded(className, classLoader)
        }
    }

    private val STANDARD_LIBRARY_PREFIXES = listOf(
        "java/", "javax/", "kotlin/", "sun/", "jdk/"
    )

    // Whitelist of safe static JDK methods (pure functions with no side effects)
    private val WHITELISTED_STATIC_METHODS = setOf(
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
    private val WHITELISTED_FINAL_CLASS_METHODS = setOf(
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
    private val WHITELISTED_DYNAMIC_INVOCATIONS = setOf(
        // String concatenation (Java 9+)
        "java/lang/invoke/StringConcatFactory.makeConcatWithConstants",
        "java/lang/invoke/StringConcatFactory.makeConcat",
    )
}
