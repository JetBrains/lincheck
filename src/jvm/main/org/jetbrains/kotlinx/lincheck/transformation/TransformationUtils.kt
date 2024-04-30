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

import sun.nio.ch.lincheck.Injections
import org.objectweb.asm.*
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.*
import java.io.*
import java.util.*
import java.util.concurrent.*
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

/**
 * Adds invocation of [beforeEvent] method.
 * This method **must** be called from the user code, as [beforeEvent] must be called from the user code due to the contract
 * between the Lincheck IDEA plugin and Lincheck.
 *
 * @param setMethodEventId a flag that identifies that method call event id set is required
 */
internal fun GeneratorAdapter.invokeBeforeEvent(debugMessage: String, setMethodEventId: Boolean) = invokeInIgnoredSection {
    ifStatement(
        condition = {
            invokeStatic(Injections::shouldInvokeBeforeEvent)
        },
        ifClause = {
            if (setMethodEventId) {
                invokeStatic(Injections::setLastMethodCallEventId)
            }
            push(debugMessage)
            invokeStatic(Injections::getNextEventId)
            push(debugMessage)
            invokeStatic(Injections::beforeEvent)
        },
        elseClause = {}
    )
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

private val isCoroutineStateMachineClassMap = ConcurrentHashMap<String, Boolean>()
internal fun isCoroutineStateMachineClass(internalClassName: String): Boolean {
    if (internalClassName.startsWith("java/")) return false
    if (internalClassName.startsWith("kotlin/") && !internalClassName.startsWith("kotlin/coroutines/")) return false
    return isCoroutineStateMachineClassMap.computeIfAbsent(internalClassName) {
        getSuperclassName(internalClassName) == "kotlin/coroutines/jvm/internal/ContinuationImpl"
    }
}

private fun getSuperclassName(internalClassName: String): String? {
    class SuperclassClassVisitor : ClassVisitor(ASM_API) {
        var internalSuperclassName: String? = null
            private set

        override fun visit(version: Int, access: Int, name: String?, signature: String?, superName: String?, interfaces: Array<out String>?) {
            internalSuperclassName = superName
        }
    }
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

internal val STRING_TYPE = getType(String::class.java)
internal val CLASS_TYPE = getType(Class::class.java)
internal val CLASS_FOR_NAME_METHOD = Method("forName", CLASS_TYPE, arrayOf(STRING_TYPE))
