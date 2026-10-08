/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions.java

import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionClasspath
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionCompilationException
import org.jetbrains.lincheck.jvm.agent.expressions.MockedApplicationMembers
import org.jetbrains.lincheck.jvm.agent.expressions.expressionClasspath
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.FileObject
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaCompiler
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider

/**
 * Compiles generated Java sources in memory with the JDK's own compiler when available,
 * falling back to a runtime-compatible embedded Eclipse compiler on JRE-only runtimes:
 * sources come from strings and compiled classes are returned as byte arrays,
 * the application's types resolved through a classpath derived from the running JVM
 * and the target class's loader chain. Non-public application members are exposed to the compiler through
 * [MockApplicationClassFileManager]. [JavaEnclosingClassEvaluator] owns the enclosing-facade compilation,
 * method transplantation, and final conversion to the IDE expression accessor ABI.
 */
internal object JavaExpressionToolchain {

    private const val COMPILER_JAR_PROPERTY_PREFIX = "lincheck.javaCompilerJar."
    private const val COMPILER_CLASS = "org.eclipse.jdt.internal.compiler.tool.EclipseCompiler"

    private val embeddedCompiler: Result<JavaCompiler> by lazy { runCatching { loadEmbeddedCompiler() } }

    val isAvailable: Boolean get() = ToolProvider.getSystemJavaCompiler() != null || embeddedCompiler.isSuccess
    internal val isEmbeddedCompilerAvailable: Boolean get() = embeddedCompiler.isSuccess

    /**
     * Compiles [sources] as one unit and returns the produced classes,
     * together with the non-public application members the compiler had to mock.
     *
     * @param sources the generated Java files, each paired with the binary name it declares;
     *   they are compiled together, so they may reference one another
     *   (the enclosing-class facade and its superclass facades arrive in one call).
     * @param classpath the compile classpath resolving the application's own types,
     *   prepared once for the whole compilation request by [expressionClasspath]
     *   and reused across the calls that make it up.
     * @param systemCompiler the JDK compiler to use, defaulting to the running JVM's own.
     *   `null` — a JRE-only runtime, or a test pinning the fallback —
     *   selects the embedded Eclipse compiler instead.
     * @throws ExpressionCompilationException if no compiler is available, or the sources fail to compile;
     *   the message carries the compiler's error diagnostics and is user-facing.
     */
    internal fun compile(
        sources: List<JavaSource>,
        classpath: ExpressionClasspath,
        systemCompiler: JavaCompiler? = ToolProvider.getSystemJavaCompiler(),
    ): RawJavaCompilation {
        val compiler = systemCompiler ?: embeddedCompiler.getOrElse {
            throw ExpressionCompilationException(
                "Agent-side Java expression compilation is unavailable: ${it.message ?: it}",
            )
        }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val standardFileManager = compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8)
        val outputs = mutableMapOf<String, ByteArrayOutputStream>()
        val outputFileManager = object : ForwardingJavaFileManager<StandardJavaFileManager>(standardFileManager) {
            override fun getJavaFileForOutput(
                location: JavaFileManager.Location?,
                className: String,
                kind: JavaFileObject.Kind,
                sibling: FileObject?,
            ): JavaFileObject {
                // ECJ reports packaged output names in internal (`a/b/C`) form, while javac uses
                // binary (`a.b.C`) names. SnapshotBreakpoint class maps use binary names.
                val binaryClassName = className.replace('/', '.')
                return object : SimpleJavaFileObject(
                    URI.create("mem:///${binaryClassName.replace('.', '/')}${kind.extension}"), kind,
                ) {
                    override fun openOutputStream() =
                        ByteArrayOutputStream().also { outputs[binaryClassName] = it }
                }
            }
        }
        val fileManager = MockApplicationClassFileManager(outputFileManager)
        val workDir = Files.createTempDirectory("lincheck-java-expr").toFile()
        val sourceObjects = sources.map { input ->
            val sourceFile = File(workDir, "${input.binaryName.replace('.', '/')}.java").apply {
                parentFile.mkdirs()
                writeText(input.source)
            }
            object : SimpleJavaFileObject(sourceFile.toURI(), JavaFileObject.Kind.SOURCE) {
                override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = input.source
            }
        }
        val options = mutableListOf("-encoding", "UTF-8", "-proc:none")
        classpath.entries.takeIf { it.isNotEmpty() }?.let {
            options += "-classpath"
            options += it.joinToString(File.pathSeparator) { file -> file.path }
        }

        try {
            val succeeded = fileManager.use {
                compiler.getTask(null, fileManager, diagnostics, options, null, sourceObjects).call()
            }
            if (!succeeded) {
                val errors = diagnostics.diagnostics
                    .filter { it.kind == Diagnostic.Kind.ERROR }
                    .joinToString("; ") { it.getMessage(null) }
                throw ExpressionCompilationException(
                    "Expression failed to compile: ${errors.ifEmpty { "unknown Java compiler error" }}",
                )
            }
            val compiledClasses = outputs.mapValues { (_, bytes) -> bytes.toByteArray() }
            return RawJavaCompilation(compiledClasses, fileManager.mockedMembers)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun loadEmbeddedCompiler(): JavaCompiler {
        val compiler = EmbeddedCompiler.forCurrentRuntime()
        val jar = System.getProperty(COMPILER_JAR_PROPERTY_PREFIX + compiler.minimumRuntime)
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?: extractEmbeddedCompilerJar(compiler)
            ?: error(
                "no Java compiler found — neither the ${COMPILER_JAR_PROPERTY_PREFIX}${compiler.minimumRuntime} " +
                    "system property nor an embedded ${compiler.resource} resource",
            )
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), JavaCompiler::class.java.classLoader)
        return Class.forName(COMPILER_CLASS, true, loader)
            .getDeclaredConstructor()
            .newInstance() as JavaCompiler
    }

    private fun extractEmbeddedCompilerJar(compiler: EmbeddedCompiler): File? {
        val resource = JavaExpressionToolchain::class.java.getResourceAsStream(compiler.resource) ?: return null
        val jar = Files.createTempFile("lincheck-java-compiler", ".jar").toFile().apply { deleteOnExit() }
        resource.use { input -> jar.outputStream().use { input.copyTo(it) } }
        return jar
    }

    private enum class EmbeddedCompiler(val minimumRuntime: Int) {
        JDK_8(8),
        JDK_11(11),
        JDK_17(17);

        val resource: String get() = "/java-expression-compiler-jdk$minimumRuntime.jar"

        companion object {
            fun forCurrentRuntime(): EmbeddedCompiler = when (javaRuntimeFeature()) {
                8 -> JDK_8
                in 11..16 -> JDK_11
                in 17..Int.MAX_VALUE -> JDK_17
                else -> error("Embedded Java expression compilation requires Java 8 or Java 11+")
            }

            private fun javaRuntimeFeature(): Int = System.getProperty("java.specification.version")
                .removePrefix("1.")
                .substringBefore('.')
                .toInt()
        }
    }
}
internal data class JavaSource(val binaryName: String, val source: String)

internal data class RawJavaCompilation(
    val classes: Map<String, ByteArray>,
    val mockedMembers: MockedApplicationMembers,
)
