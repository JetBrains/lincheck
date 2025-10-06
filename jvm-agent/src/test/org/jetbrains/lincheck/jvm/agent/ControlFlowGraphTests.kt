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

import org.jetbrains.lincheck.jvm.agent.analysis.buildControlFlowGraph
import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.BasicBlockControlFlowGraph
import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.prettyPrint
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.IOException
import java.io.File
import java.net.URI
import javax.tools.*
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JavaCfgTester provides a set of test utilities to:
 *   - compile Java sources from test resources,
 *   - build a basic-block control-flow graph for a selected method,
 *   - compare a textual representation of control-flow graph with a golden file from resources.
 */
class JavaControlFlowGraphTester {

    private val compiler: JavaCompiler = ToolProvider.getSystemJavaCompiler()
        ?: error("No system Java compiler available. Ensure tests run on a JDK, not a JRE.")

    /**
     * Compiles a Java source file from test resources and returns a map of internal class names to their bytes.
     */
    private fun compileJavaFromResource(sourceFilePath: String): Map<String, ByteArray> {
        val sourceText = loadResourceText(sourceFilePath)

        // Test source files are stored with `.java.txt` extension,
        // because otherwise, with `.java` extension only, gradle tries to compile them to `.class` files.
        // Here we remove the `.txt` suffix to make the java compiler happy.
        val fileObject = InMemoryJavaSource.fromResourcePath(sourceFilePath.removeSuffix(".txt"), sourceText)

        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val fileManager = InMemoryJavaClassFileManager(compiler.getStandardFileManager(diagnostics, null, null))

        val options = mutableListOf<String>()
        // Ensure the compiler sees the current classpath
        System.getProperty("java.class.path")?.let { cp ->
            options += listOf("-classpath", cp)
        }

        val task = compiler.getTask(
            /* out = */ null,
            /* fileManager = */ fileManager,
            /* diagnosticListener = */ diagnostics,
            /* options = */ options,
            /* classes = */ null,
            /* compilationUnits = */ listOf(fileObject)
        )

        val success = task.call()
        if (!success) {
            val message = buildString {
                appendLine("Java compilation failed for resource: $sourceFilePath")
                diagnostics.diagnostics.forEach { d ->
                    appendLine("[$sourceFilePath:${d.lineNumber}] ${d.kind}: ${d.getMessage(null)}")
                }
            }
            error(message)
        }

        return fileManager.classes.mapValues { (_, classFile) -> classFile.getBytes() }
    }

    /**
     * Builds a basic-block CFG for a method in the given class bytes.
     */
    private fun buildCfg(
        classBytes: ByteArray,
        internalClassName: String,
        methodName: String,
        descriptor: String,
    ): BasicBlockControlFlowGraph {
        val classNode = ClassNode().apply {
            ClassReader(classBytes).accept(this, 0)
        }
        val methodNode = selectMethod(classNode.methods, methodName, descriptor)
        return buildControlFlowGraph(internalClassName, methodNode)
    }

    /**
     * Selects a single method from a list of methods with the given name and descriptor.
     */
    private fun selectMethod(methods: List<MethodNode>, methodName: String, descriptor: String): MethodNode {
        val candidates = methods.filter { it.name == methodName && it.desc == descriptor }
        if (candidates.size == 0) {
            error("Method not found: $methodName${descriptor.let { " $it" }}")
        }
        if (candidates.size > 1) {
            error("Multiple methods matched: $methodName${descriptor.let { " $it" }}")
        }
        return candidates.single()
    }

    /**
     * Entry point for tests:
     *   - compiles [javaResourcePath],
     *   - from class [internalClassName] extracts a single method [methodName] with given [descriptor],
     *   - builds CFG of this method,
     *   - pretty-prints CFG,
     *   - compares the result with golden data loaded from [goldenResourcePath].
     */
    fun testMethodCfg(
        javaResourcePath: String,
        goldenResourcePath: String,
        internalClassName: String,
        methodName: String,
        descriptor: String,
    ) {
        val classes = compileJavaFromResource(javaResourcePath)
        val bytes = classes[internalClassName]
            ?: error("Class not found: $internalClassName")
        val cfg = buildCfg(bytes, internalClassName, methodName, descriptor)
        val cfgText = cfg.prettyPrint()
        val golden = loadResourceText(goldenResourcePath)

        try {
            assertEquals(golden, cfgText)
        } catch (e: AssertionError) {
            if (OVERWRITE_REPRESENTATION_TESTS_OUTPUT) {
                overwriteGolden(goldenResourcePath, cfgText)
            } else {
                throw e
            }
        }
    }

    /**
     * Loads the content of a text resource file located at the specified path.
     */
    private fun loadResourceText(path: String): String {
        val url = ClassLoader.getSystemResource(path)
            ?: error("Resource not found: $path")
        url.openStream().use { input ->
            return InputStreamReader(input, Charsets.UTF_8).readText()
        }
    }

    /**
     * Overwrites the content of a golden file at the specified resource path with the provided content.
     */
    private fun overwriteGolden(resourcePath: String, content: String) {
        try {
            val file = File("src/test/resources/$resourcePath")
            file.parentFile?.mkdirs()
            file.writeText(content)
            println("Golden file updated: $resourcePath")
        } catch (e: Exception) {
            throw IOException("Failed to overwrite golden file $resourcePath", e)
        }
    }
}

/**
 * In-memory Java source file object representing a single test resource.
 */
private class InMemoryJavaSource private constructor(
    uri: URI,
    private val content: String,
) : SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {

    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = content

    companion object {
        fun fromResourcePath(resourcePath: String, content: String): InMemoryJavaSource {
            // Use a synthetic URI; the path part helps error messages.
            val uri = URI.create("mem:///" + resourcePath.removePrefix("/") )
            return InMemoryJavaSource(uri, content)
        }
    }
}

/**
 * Stores compiled class bytes in memory
 */
private class InMemoryClassFile(
    val internalClassName: String,
    kind: JavaFileObject.Kind
) : SimpleJavaFileObject(URI.create("mem:///" + internalClassName + kind.extension), kind) {
    private val outputStream = ByteArrayOutputStream()

    override fun openOutputStream() = outputStream

    fun getBytes(): ByteArray = outputStream.toByteArray()
}

private class InMemoryJavaClassFileManager(
    fileManager: JavaFileManager
) : ForwardingJavaFileManager<JavaFileManager>(fileManager) {

    /**
     * Map from an internal class name to its corresponding class file.
     */
    val classes: Map<String, InMemoryClassFile> get() = _outputs
    private val _outputs: MutableMap<String, InMemoryClassFile> = mutableMapOf()

    override fun getJavaFileForOutput(
        location: JavaFileManager.Location,
        className: String,
        kind: JavaFileObject.Kind,
        sibling: FileObject?
    ): JavaFileObject {
        val internalClassName = className.toInternalClassName()
        val classFile = InMemoryClassFile(internalClassName, kind)
        _outputs[internalClassName] = classFile
        return classFile
    }
}


class JavaControlFlowGraphTest {
    private val tester = JavaControlFlowGraphTester()
    
    private val javaPath = "analysis/controlflow/JavaControlFlowGraphCases.java.txt"
    private val className = "JavaControlFlowGraphCases"
    
    private fun golden(name: String) = "analysis/controlflow/golden/$name.txt"
    
    private fun test(name: String, desc: String) = 
        tester.testMethodCfg(javaPath, golden(name), className, name, desc)

    @Test
    fun straightLine() = test("straightLine", "()I")

    @Test
    fun ifStmt() = test("ifStmt", "(I)I")

    @Test
    fun ifElseStmt() = test("ifElseStmt", "(I)I")

    @Test 
    fun ifNull() = test("ifNull", "(Ljava/lang/Object;)I")

    @Test
    fun ifRefCompare() = test("ifRefCompare", "(Ljava/lang/Object;Ljava/lang/Object;)I")

    @Test
    fun ifElseNested() = test("ifElseNested", "(II)I")

    @Test
    fun whileLoop() = test("whileLoop", "(I)I")

    @Test
    fun whileLoopContinue() = test("whileLoopContinue", "(I)I")

    @Test
    fun whileLoopBreak() = test("whileLoopBreak", "(I)I")

    @Test
    fun doWhileLoop() = test("doWhileLoop", "(I)I")

    @Test
    fun forLoop() = test("forLoop", "(I)I")

    @Test
    fun forLoopContinueBreak() = test("forLoopContinueBreak", "(I)I")
}
