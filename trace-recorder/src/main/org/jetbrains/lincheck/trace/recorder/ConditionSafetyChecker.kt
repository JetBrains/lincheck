/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.recorder

import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.trace.recorder.SideEffectViolation.*
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import java.util.concurrent.*

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
        override fun toString() = "Field write: $owner.$fieldName at $fileNameOrUnknown:$lineNumber"
    }

    data class LocalVariableWrite(
        override val fileName: String?,
        override val lineNumber: Int,
        val varIndex: Int
    ) : SideEffectViolation() {
        override fun toString() = "Local variable write at index $varIndex at $fileNameOrUnknown:$lineNumber"
    }

    data class ArrayWrite(
        override val fileName: String?,
        override val lineNumber: Int
    ) : SideEffectViolation() {
        override fun toString() = "Array write operation at $fileNameOrUnknown:$lineNumber"
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
        val methodName: String
    ) : SideEffectViolation() {
        override fun toString() = "Disallowed method call: $owner.$methodName at $fileNameOrUnknown:$lineNumber"
    }

    data class MaxCallDepthExceeded(
        override val fileName: String?,
        override val lineNumber: Int,
        val owner: String,
        val methodName: String
    ) : SideEffectViolation() {
        override fun toString() = "Maximum call depth exceeded: $owner.$methodName at $fileNameOrUnknown:$lineNumber"
    }

    data class DisallowedDynamicInvocation(
        override val fileName: String?,
        override val lineNumber: Int,
        val bootstrapOwner: String,
        val bootstrapName: String
    ) : SideEffectViolation() {
        override fun toString() = "Disallowed dynamic invocation: $bootstrapOwner.$bootstrapName at $fileNameOrUnknown:$lineNumber"
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
     * Cache key for method safety checks.
     */
    private data class MethodKey(
        val className: String,
        val methodName: String,
        val methodDescriptor: String
    )

    /**
     * Cache for storing safety check results to avoid redundant bytecode analysis.
     */
    private val cache = ConcurrentHashMap<MethodKey, List<SideEffectViolation>>()

    /**
     * Checks if a method's bytecode is free of side effects by analyzing its instructions.
     *
     * @param className The fully qualified class name
     * @param methodName The method name to check
     * @param methodDescriptor The method descriptor (e.g., "()V")
     * @param classLoader The ClassLoader to use for loading class bytecode
     * @return list of side-effect violations found, empty if the method is safe
     */
    fun checkForSideEffectAbsence(
        className: String,
        methodName: String,
        methodDescriptor: String,
        classLoader: ClassLoader
    ): List<SideEffectViolation> {
        // Perform the safety check.
        val key = MethodKey(className, methodName, methodDescriptor)
        return cache.computeIfAbsent(key) {
            checkMethodForSideEffects(
                className = className,
                methodName = methodName,
                methodDescriptor = methodDescriptor,
                classLoader = classLoader,
                maxCallDepth = 5
            )
        }
    }

    /**
     * Analyzes method bytecode for side effects (writes, disallowed calls).
     */
    private fun checkMethodForSideEffects(
        className: String,
        methodName: String,
        methodDescriptor: String,
        classLoader: ClassLoader,
        maxCallDepth: Int
    ): List<SideEffectViolation> {
        // Store violations here.
        val violations = mutableListOf<SideEffectViolation>()
        // Load class bytecode.
        val classBytes = loadClassBytes(className, classLoader)
        if (classBytes == null) {
            violations.add(ClassNotAccessible(null, -1, className))
            return violations
        }
        // Analyze the method's bytecode for violations.
        val reader = ClassReader(classBytes)
        val classVisitor = MethodSafetyCheckingClassVisitor(
            targetMethodName = methodName,
            targetMethodDescriptor = methodDescriptor,
            violations = violations,
            classLoader = classLoader,
            maxCallDepth = maxCallDepth
        )
        reader.accept(classVisitor, ClassReader.SKIP_FRAMES)
        return violations
    }


    /**
     * Loads class bytecode from the classloader.
     */
    private fun loadClassBytes(className: String, classLoader: ClassLoader): ByteArray? {
        val resourceName = className.replace('.', '/') + ".class"
        return classLoader.getResourceAsStream(resourceName)?.use { it.readBytes() }
    }

    /**
     * Class visitor that locates a specific method and delegates to SafetyCheckingMethodVisitor for analysis.
     */
    private class MethodSafetyCheckingClassVisitor(
        private val targetMethodName: String,
        private val targetMethodDescriptor: String,
        private val violations: MutableList<SideEffectViolation>,
        private val classLoader: ClassLoader,
        private val maxCallDepth: Int
    ) : ClassVisitor(ASM_API) {

        // We need it to construct violation objects.
        private var fileName: String? = null

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
                violations.add(
                    MethodNotFinal(fileName, -1, name)
                )
                return null
            }
            // Analyze this method's instructions.
            return SafetyCheckingMethodVisitor(fileName, violations, classLoader, maxCallDepth)
        }
    }


    /**
     * Method visitor that examines each instruction and detects unsafe operations.
     */
    private class SafetyCheckingMethodVisitor(
        private val fileName: String?,
        private val violations: MutableList<SideEffectViolation>,
        private val classLoader: ClassLoader,
        private val maxCallDepth: Int
    ) : MethodVisitor(ASM_API) {

        private var currentLineNumber: Int = -1

        override fun visitLineNumber(line: Int, start: Label) {
            currentLineNumber = line
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
            // Reject field writes (reads are OK)
            if (opcode == PUTFIELD || opcode == PUTSTATIC) {
                violations.add(
                    FieldWrite(fileName, currentLineNumber, owner, name)
                )
            }
        }

        override fun visitVarInsn(opcode: Int, varIndex: Int) {
            // Reject local variable writes (reads are OK)
            if (opcode in STORE_OPCODES) {
                violations.add(
                    LocalVariableWrite(fileName, currentLineNumber, varIndex)
                )
            }
        }

        override fun visitInsn(opcode: Int) {
            // Reject array writes (reads are OK).
            if (opcode in ARRAY_STORE_OPCODES) {
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
            if (owner.startsWith("java/")
                || owner.startsWith("javax/")
                || owner.startsWith("kotlin/")
                || owner.startsWith("sun/")
                || owner.startsWith("jdk/")
            ) {
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
            // Allow safe method calls.
            val methodViolations = checkMethodForSideEffects(
                className = owner.replace('/', '.'),
                methodName = name,
                methodDescriptor = descriptor,
                classLoader = classLoader,
                maxCallDepth = maxCallDepth - 1
            )
            if (methodViolations.isEmpty()) {
                return
            }
            // Add violations from the called method.
            violations.addAll(methodViolations)
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: org.objectweb.asm.Handle,
            vararg bootstrapMethodArguments: Any?
        ) {
            // Allow string concatenation.
            if (bootstrapMethodHandle.owner == "java/lang/invoke/StringConcatFactory" &&
                (bootstrapMethodHandle.name == "makeConcatWithConstants" ||
                        bootstrapMethodHandle.name == "makeConcat")
            ) {
                return
            }
            // Allow Java record methods (toString, equals, hashCode).
            if (bootstrapMethodHandle.owner == "java/lang/runtime/ObjectMethods" &&
                bootstrapMethodHandle.name == "bootstrap"
            ) {
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
    }

    // Opcodes for local variable store instructions
    private val STORE_OPCODES = setOf(
        ISTORE, LSTORE, FSTORE, DSTORE, ASTORE
    )

    // Opcodes for array store instructions
    private val ARRAY_STORE_OPCODES = setOf(
        IASTORE, LASTORE, FASTORE, DASTORE,
        AASTORE, BASTORE, CASTORE, SASTORE
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
}
