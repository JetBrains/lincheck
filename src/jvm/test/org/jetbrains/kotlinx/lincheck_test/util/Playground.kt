/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.util

import net.bytebuddy.dynamic.loading.ByteArrayClassLoader
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode
import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassFileTransformer
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import org.objectweb.asm.commons.Method
import org.objectweb.asm.util.TraceClassVisitor
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Renders the human-readable representation of the bytecode for a class with a generated method.
 *
 * @param className The name of the class to be generated. Defaults to "GeneratedClass".
 * @param methodName The name of the method to be included in the generated class. Defaults to "f".
 * @param returnType The return type of the generated method. Defaults to "void".
 * @param instrumentationMode Specifies the mode of bytecode instrumentation, if any, to apply during bytecode generation. Can be null.
 * @param functionContent A lambda defining the custom content of the generated method.
 * @return A string representing the human-readable version of the generated class's bytecode.
 */
internal fun renderBytecode(
    className: String = "GeneratedClass",
    methodName: String = "f",
    returnType: String = "void",
    instrumentationMode: InstrumentationMode? = null,
    functionContent: GeneratorAdapter.() -> Unit,
): String {
    val bytecode = generateClassBytecode(className, methodName, returnType, instrumentationMode, functionContent)

    // Convert the bytecode to a readable format
    val cr = ClassReader(bytecode)
    val sw = StringWriter()
    val pw = PrintWriter(sw)
    cr.accept(TraceClassVisitor(pw), 0)

    return sw.toString()
}

/**
 * Generates bytecode for a single class with a default or custom method.
 *
 * @param className The name of the class to be generated. Defaults to "GeneratedClass".
 * @param methodName The name of the method to be added in the generated class. Defaults to "f".
 * @param returnType The return type of the method specified. Defaults to "void".
 * @param instrumentationMode The mode of bytecode transformation applied during generation. This can be null if no instrumentation is required.
 * @param functionContent A lambda defining the custom content of the generated method.
 * @return A byte array representing the generated class bytecode.
 */
private fun generateClassBytecode(
    className: String = "GeneratedClass",
    methodName: String = "f",
    returnType: String = "void",
    instrumentationMode: InstrumentationMode? = null,
    functionContent: GeneratorAdapter.() -> Unit,
): ByteArray {
    val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)

    // Define a dummy class
    cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)

    // Define constructor
    val constructor = GeneratorAdapter(Opcodes.ACC_PUBLIC, Method.getMethod("void <init>()"), null, null, cw)
    constructor.loadThis()
    constructor.invokeConstructor(OBJECT_TYPE, Method.getMethod("void <init>()"))
    constructor.returnValue()
    constructor.endMethod()

    // Define a method `f()`
    val mv = GeneratorAdapter(Opcodes.ACC_PUBLIC, Method.getMethod("$returnType $methodName()"), null, null, cw)
    mv.visitCode()

    // Apply user-provided function content
    mv.functionContent()

    mv.returnValue()
    mv.endMethod()

    cw.visitEnd()

    return cw.toByteArray().let {
        if (instrumentationMode != null) {
            LincheckJavaAgent.instrumentationMode = instrumentationMode
            LincheckClassFileTransformer.transformImpl((object {})::class.java.classLoader, className, it)
        } else {
            it
        }
    }
}

/**
 * Dynamically generates a class with bytecode, loads it, and invokes its method.
 *
 * @param className The name of the generated class. Defaults to "GeneratedClass".
 * @param methodName The name of the method to be invoked within the generated class. Defaults to "f".
 * @param returnType The return type of the method specified. Defaults to "void".
 * @param instrumentationMode The mode of bytecode transformation to be applied, if any. Can be null if no instrumentation is required.
 * @param functionContent A lambda defining the body of the generated method using a `GeneratorAdapter`.
 * @return The result of invoking the generated method at runtime, or null for methods with a void return type.
 */
internal fun invokeBytecode(
    className: String = "GeneratedClass",
    methodName: String = "f",
    returnType: String = "void",
    instrumentationMode: InstrumentationMode? = null,
    functionContent: GeneratorAdapter.() -> Unit,
): Any? {
    val bytecode = generateClassBytecode(className, methodName, returnType, instrumentationMode, functionContent)
    val classLoader = ByteArrayClassLoader(null, mapOf(className to bytecode))
    val generatedClass = classLoader.loadClass(className)

    // Invoke the method dynamically
    val instance = generatedClass.getDeclaredConstructor().newInstance()
    val method: java.lang.reflect.Method = generatedClass.getMethod(methodName)
    return method.invoke(instance)
}

/**
 * Generates bytecode for a class and its method, renders the bytecode in a human-readable format,
 * and attempts to invoke the generated method at runtime.
 *
 * @param className The name of the generated class. Defaults to "GeneratedClass".
 * @param methodName The name of the method to be included and invoked within the generated class. Defaults to "f".
 * @param returnType The return type of the method specified. Defaults to "void".
 * @param instrumentationMode The mode of bytecode transformation to be applied, if any. Can be null if no instrumentation is needed.
 * @param functionContent A lambda defining the content of the generated method using a `GeneratorAdapter`.
 * @return A pair consisting of the human-readable bytecode representation and a result from attempting to invoke it.
 */
internal fun renderAndInvokeBytecode(
    className: String = "GeneratedClass",
    methodName: String = "f",
    returnType: String = "void",
    instrumentationMode: InstrumentationMode? = null,
    functionContent: GeneratorAdapter.() -> Unit,
): Pair<String, Result<Any?>> {
    val output = renderBytecode(className, methodName, returnType, instrumentationMode, functionContent)
    val result = runCatching {
        invokeBytecode(className, methodName, returnType, instrumentationMode, functionContent)
    }
    return output to result
}

/**
 * The main function serves as the entry point for a Kotlin playground. In this implementation,
 * it demonstrates how to dynamically generate bytecode for a class and its method, render the bytecode
 * in a human-readable format, and invoke the generated method at runtime.
 *
 * The bytecode generation and invocation process leverages the [renderAndInvokeBytecode] function,
 * which accepts the following optional parameters besides the obligatory generated function content:
 * * The name of the generated class. Defaults to "GeneratedClass".
 * * The name of the method to be included and invoked within the generated class. Defaults to "f".
 * * The return type of the method specified. Defaults to "void".
 * * The mode of bytecode transformation to be applied, if any. Can be null if no instrumentation is needed.
 */
fun main() {
    println("Bytecode:")
    val (bytecode, result) = renderAndInvokeBytecode(returnType = "java.lang.Object") {
        // Here goes a sample, you can replace it with anything you want to check.
        push("Hello, world!")
        // End of the sample
    }

    println(bytecode)
    println("Result:")
    println(result.getOrThrow())
}
