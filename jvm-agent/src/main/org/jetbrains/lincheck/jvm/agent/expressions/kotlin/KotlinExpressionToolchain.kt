/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions.kotlin

import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionClasspath
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionCompilationException
import org.jetbrains.lincheck.jvm.agent.expressions.expressionClasspath
import org.jetbrains.lincheck.util.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files

/**
 * Compiles generated Kotlin wrapper sources with `kotlin-compiler-embeddable`,
 * loaded in an **isolated** class loader so the compiler and its bundled runtime never touch
 * the application's class path (the instrumented app may carry its own Kotlin — or even its
 * own copy of the Kotlin compiler).
 *
 * The compiler jar comes from the `lincheck.kotlinCompilerJar` system property when set
 * (tests, custom setups) or from the agent's embedded `kotlin-compiler-embeddable.jar`
 * resource, extracted to a temporary file on first use. Both the extracted jar and the
 * loader are cached for the JVM's lifetime — the first Kotlin expression pays the compiler
 * bootstrap, subsequent ones reuse it.
 */
internal object KotlinExpressionToolchain {

    private const val COMPILER_JAR_PROPERTY = "lincheck.kotlinCompilerJar"
    private const val COMPILER_JAR_RESOURCE = "/kotlin-expression-compiler.jar"
    private const val COMPILER_CLASS = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
    private const val EXPRESSION_ANALYZER_CLASS =
        "org.jetbrains.lincheck.jvm.agent.expressions.kotlin.compiler.KotlinExpressionAnalyzer"

    private val compiler: Result<CompilerHandle> by lazy { runCatching { loadCompiler() } }

    val isAvailable: Boolean get() = compiler.isSuccess

    /**
     * A `K2JVMCompiler` *class* handle, not an instance: the CLI compiler object is single-use
     * (its performance-measurement state finalizes after one `exec`, and a second call dies with
     * an internal `AssertionError`), so each compilation instantiates a fresh one.
     */
    private class CompilerHandle(
        val jars: List<File>,
        val loader: ClassLoader,
        val compilerClass: Class<*>,
        val exec: java.lang.reflect.Method,
        val analyzeExpressions: java.lang.reflect.Method,
    ) {
        fun newCompiler(): Any = compilerClass.getDeclaredConstructor().newInstance()
    }

    /**
     * Compiles [sources] as a single Kotlin module and returns all produced classes by binary name.
     *
     * @param sources the generated Kotlin files, keyed by module-relative path
     *   (`com/example/Counter$Wrapper.kt`), compiled in one `kotlinc` invocation,
     *   so they may reference one another.
     * @param classpath the compile classpath resolving the application's own types,
     *   prepared once for the whole compilation request by [expressionClasspath]
     *   and reused across the calls that make it up.
     *   The Kotlin runtime is not part of it — the compiler's own jars provide that.
     * @throws ExpressionCompilationException on any compilation problem,
     *   including an unavailable compiler.
     */
    fun compile(sources: Map<String, String>, classpath: ExpressionClasspath): Map<String, ByteArray> {
        val handle = compiler.getOrElse {
            throw ExpressionCompilationException(
                "Agent-side Kotlin expression compilation is unavailable: " +
                    (it.message ?: it.toString()) + (it.cause?.let { cause -> " (cause: $cause)" } ?: ""),
            )
        }
        val workDir = Files.createTempDirectory("lincheck-kotlin-expr").toFile()
        try {
            val sourceFiles = sources.map { (fileName, source) ->
                File(workDir, fileName).apply {
                    parentFile?.mkdirs()
                    writeText(source)
                }
            }
            val outDir = File(workDir, "out").apply { mkdirs() }
            // The compiler's own jars also serve as the Kotlin runtime on the compile classpath.
            val compileClasspath = (classpath.entries + handle.jars).joinToString(File.pathSeparator) { it.path }
            val messages = ByteArrayOutputStream()
            val exitCode = PrintStream(messages, true, "UTF-8").use { stream ->
                handle.exec.invoke(
                    handle.newCompiler(),
                    stream,
                    arrayOf(
                        *sourceFiles.map(File::getPath).toTypedArray(),
                        "-d", outDir.path,
                        "-classpath", compileClasspath,
                        // The embeddable jar itself provides the Kotlin runtime on the classpath.
                        "-no-stdlib", "-no-reflect",
                        "-nowarn",
                    ),
                ).toString()
            }
            if (exitCode != "OK") {
                val output = messages.toString("UTF-8")
                val errors = output.lineSequence()
                    .filter { it.startsWith("error:") || ": error:" in it }
                    .joinToString("; ")
                // No `error:` lines (e.g. an internal compiler error): surface the raw tail instead.
                    .ifEmpty { "kotlinc exit code $exitCode: ${output.trim().take(900)}" }
                throw ExpressionCompilationException("Expression failed to compile: $errors")
            }
            return outDir.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .associate { file ->
                    val binaryName = file.relativeTo(outDir).path
                        .removeSuffix(".class")
                        .replace(File.separatorChar, '.')
                    binaryName to file.readBytes()
                }
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** Returns referenced names and the subset used as call-expression callees. */
    fun expressionNames(expressions: List<String>): ExpressionNames {
        val handle = compiler.getOrElse {
            throw ExpressionCompilationException(
                "Agent-side Kotlin expression analysis is unavailable: ${it.message ?: it}",
            )
        }
        return synchronized(handle) {
            try {
                @Suppress("UNCHECKED_CAST")
                val names = handle.analyzeExpressions.invoke(null, expressions.toTypedArray()) as Array<Array<String>>
                ExpressionNames(names[0].toSet(), names[1].toSet())
            } catch (failure: ReflectiveOperationException) {
                throw ExpressionCompilationException(
                    "Kotlin expression AST analysis failed: ${failure.cause?.message ?: failure.message}",
                )
            }
        }
    }

    data class ExpressionNames(val referenced: Set<String>, val called: Set<String>)

    private fun loadCompiler(): CompilerHandle {
        // The property may carry several path-separated jars (the embeddable compiler does not
        // bundle the Kotlin runtime); the packaged agent ships them pre-merged as one resource.
        val jars = System.getProperty(COMPILER_JAR_PROPERTY)
            ?.split(File.pathSeparator)
            ?.map { File(it) }
            ?.takeIf { files -> files.isNotEmpty() && files.all { it.isFile } }
            ?: listOfNotNull(extractEmbeddedCompilerJar())
        check(jars.isNotEmpty()) {
            "no Kotlin compiler found — neither the $COMPILER_JAR_PROPERTY system property " +
                "nor an embedded $COMPILER_JAR_RESOURCE resource"
        }
        // Parent = the platform loader (JDK 9+) for the JDK APIs the compiler uses; the
        // application and agent classes stay invisible to it, and it to them.
        val parent = runCatching {
            ClassLoader::class.java.getMethod("getPlatformClassLoader").invoke(null) as ClassLoader
        }.getOrNull()
        val loader = URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), parent)
        val compilerClass = Class.forName(COMPILER_CLASS, true, loader)
        val exec = compilerClass.getMethod("exec", PrintStream::class.java, Array<String>::class.java)
        val analyzeExpressions = loader.loadClass(EXPRESSION_ANALYZER_CLASS)
            .getMethod("analyze", Array<String>::class.java)
        Logger.info { "Loaded the agent-side Kotlin expression compiler from ${jars.joinToString { it.path }}" }
        return CompilerHandle(jars, loader, compilerClass, exec, analyzeExpressions)
    }

    private fun extractEmbeddedCompilerJar(): File? {
        val resource = KotlinExpressionToolchain::class.java
            .getResourceAsStream(COMPILER_JAR_RESOURCE) ?: return null
        val jar = File.createTempFile("lincheck-kotlin-compiler", ".jar").apply { deleteOnExit() }
        resource.use { input -> jar.outputStream().use { input.copyTo(it) } }
        return jar
    }
}
