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

import org.objectweb.asm.*
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.*
import sun.nio.ch.lincheck.*
import java.io.*
import kotlin.reflect.*
import kotlin.reflect.jvm.*

/**
 * This method is responsible for storing arguments of the method.
 *
 * Before execution:
 * STACK: param_1 param_2 ... param_n
 *
 * After execution:
 * STACK: (empty)
 *
 * @param methodDescriptor String representation of method's descriptor
 * @return Array of local variables containing arguments.
 */
internal fun GeneratorAdapter.storeArguments(methodDescriptor: String): IntArray {
    val argumentTypes = getArgumentTypes(methodDescriptor)
    val locals = IntArray(argumentTypes.size)

    // Store all arguments in reverse order
    for (i in argumentTypes.indices.reversed()) {
        locals[i] = newLocal(argumentTypes[i])
        storeLocal(locals[i], argumentTypes[i])
    }

    return locals
}

/**
 * Loads all local variables into the stack.
 *
 * @param locals Array of local variables.
 */
internal fun GeneratorAdapter.loadLocals(locals: IntArray) {
    for (local in locals)
        loadLocal(local)
}

/**
 * Stores the top value of the stack to a local variable and reloads it onto the stack.
 *
 * @param local Index of the local variable.
 */
internal fun GeneratorAdapter.storeTopToLocal(local: Int) {
    storeLocal(local)
    loadLocal(local)
}

// Map for storing the declaring class and method of each function.
internal val functionToDeclaringClassMap = HashMap<KFunction<*>, Pair<Type, Method>>()

/**
 * Invokes a static method represented by a KFunction.
 */
internal fun GeneratorAdapter.invokeStatic(function: KFunction<*>) {
    val (clazz, method) = functionToDeclaringClassMap.computeIfAbsent(function) {
        function.javaMethod!!.let {
            getType(it.declaringClass) to Method.getMethod(it)
        }
    }
    invokeStatic(clazz, method)
}

/**
 * Generates an if-statement in bytecode.
 *
 * @param condition the condition code.
 * @param ifClause the if-clause code.
 * @param elseClause the else-clause code.
 */
internal inline fun GeneratorAdapter.ifStatement(
    condition: GeneratorAdapter.() -> Unit,
    ifClause: GeneratorAdapter.() -> Unit,
    elseClause: GeneratorAdapter.() -> Unit
) {
    val ifClauseStart = newLabel()
    val end = newLabel()
    condition()
    ifZCmp(GeneratorAdapter.GT, ifClauseStart)
    elseClause()
    goTo(end)
    visitLabel(ifClauseStart)
    ifClause()
    visitLabel(end)
}

/**
 * Executes a piece of code if the current code is being executed in a testing context.
 *
 * @param original the original code.
 * @param code the code to execute if in a testing context.
 */
internal inline fun GeneratorAdapter.invokeIfInTestingCode(
    original: GeneratorAdapter.() -> Unit,
    code: GeneratorAdapter.() -> Unit
) = ifStatement(
    condition = { invokeStatic(Injections::inTestingCode) },
    ifClause = code,
    elseClause = original
)

internal inline fun GeneratorAdapter.invokeInIgnoredSection(
    code: GeneratorAdapter.() -> Unit
) {
    invokeStatic(Injections::enterIgnoredSection)
    val enteredIgnoredSection = newLocal(BOOLEAN_TYPE)
    storeLocal(enteredIgnoredSection)
    code()
    ifStatement(
        condition = {
            loadLocal(enteredIgnoredSection)
        },
        ifClause = {
            invokeStatic(Injections::leaveIgnoredSection)
        },
        elseClause = {}
    )
}

internal inline fun GeneratorAdapter.invokeInIgnoredSectionWithTryCatch(
    code: GeneratorAdapter.() -> Unit
) {
    invokeStatic(Injections::enterIgnoredSection)
    val enteredIgnoredSection = newLocal(BOOLEAN_TYPE)
    storeLocal(enteredIgnoredSection)

    val endLabel = newLabel()
    val methodCallStartLabel = newLabel()
    val methodCallEndLabel = newLabel()
    val handlerExceptionStartLabel = newLabel()
    val handlerExceptionEndLabel = newLabel()
    visitTryCatchBlock(methodCallStartLabel, methodCallEndLabel, handlerExceptionStartLabel, null)

    visitLabel(methodCallStartLabel)
    code()
    ifStatement(
        condition = {
            loadLocal(enteredIgnoredSection)
        },
        ifClause = {
            invokeStatic(Injections::leaveIgnoredSection)
        },
        elseClause = {}
    )
    visitLabel(methodCallEndLabel)
    visitLabel(handlerExceptionEndLabel)


    visitLabel(endLabel)
}

internal fun isCoroutineStateMachineClass(internalClassName: String) =
    getSuperclassName(internalClassName) == "kotlin/coroutines/jvm/internal/ContinuationImpl"

private fun getSuperclassName(internalClassName: String): String? {
    class SuperclassClassVisitor : ClassVisitor(ASM_API) {
        var internalSuperclassName: String? = null
            private set

        override fun visit(version: Int, access: Int, name: String?, signature: String?, superName: String?, interfaces: Array<out String>?) {
            internalSuperclassName = superName
        }
    }
    // Try to find the
    try {
        val classStream: InputStream = ClassLoader.getSystemClassLoader().getResourceAsStream("$internalClassName.class")
            ?: return null
        val classReader = ClassReader(classStream)
        val superclassVisitor = SuperclassClassVisitor()
        classReader.accept(superclassVisitor, 0)
        return superclassVisitor.internalSuperclassName
    } catch (t: Throwable) {
        // Failed to read or process the class.
        return null
    }
}

internal const val ASM_API = Opcodes.ASM9

// Converts the internal JVM name to a canonical one
internal val String.canonicalClassName get() = this.replace('/', '.')

internal val STRING_TYPE = getType(String::class.java)
internal val CLASS_TYPE = getType(Class::class.java)
internal val CLASS_FOR_NAME_METHOD = Method("forName", CLASS_TYPE, arrayOf(STRING_TYPE))

internal val NOT_TRANSFORMED_JAVA_UTIL_CLASSES = setOf(
    "java/util/ServiceLoader", // can not be transformed because of access to `SecurityManager`
    "java/util/concurrent/TimeUnit", // many not transformed interfaces such as `java.util.concurrent.BlockingQueue` use it
    "java/util/OptionalDouble", // used by `java.util.stream.DoubleStream`. Is an immutable collection
    "java/util/OptionalLong",
    "java/util/OptionalInt",
    "java/util/Optional",
    "java/util/Locale", // is an immutable class too
    "java/util/Locale\$Category",
    "java/util/Locale\$FilteringMode",
    "java/util/Currency",
    "java/util/Date",
    "java/util/Calendar",
    "java/util/TimeZone",
    "java/util/DoubleSummaryStatistics", // this class is mutable, but `java.util.stream.DoubleStream` interface better be not transformed
    "java/util/LongSummaryStatistics",
    "java/util/IntSummaryStatistics",
    "java/util/Formatter",
    "java/util/stream/PipelineHelper",
    "java/util/Random", // will be thread safe after `RandomTransformer` transformation
    "java/util/concurrent/ThreadLocalRandom"
)
internal val TRANSFORMED_JAVA_UTIL_INTERFACES = setOf(
    "java/util/concurrent/CompletionStage", // because it uses `java.util.concurrent.CompletableFuture`
    "java/util/Observer", // uses `java.util.Observable`
    "java/util/concurrent/RejectedExecutionHandler",
    "java/util/concurrent/ForkJoinPool\$ForkJoinWorkerThreadFactory",
    "java/util/jar/Pack200\$Packer",
    "java/util/jar/Pack200\$Unpacker",
    "java/util/prefs/PreferencesFactory",
    "java/util/ResourceBundle\$CacheKeyReference",
    "java/util/prefs/PreferenceChangeListener",
    "java/util/prefs/NodeChangeListener",
    "java/util/logging/Filter",
    "java/util/spi/ResourceBundleControlProvider"
)