/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.jetbrains.kotlinx.lincheck_test.util.OVERWRITE_REPRESENTATION_TESTS_OUTPUT
import org.junit.Assert
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Paths

abstract class AbstractIntegrationTest {
    abstract val projectPath: String
    protected abstract val testSourcesPath: String

    private fun buildGradleInitScriptToDumpTrace(
        testClassName: String,
        testMethodName: String,
        fileToDump: File,
        extraJvmArgs: List<String>,
        extraAgentArgs: List<String>,
    ): String {
        val pathToFatJar = File(Paths.get("build", "libs", "lincheck-fat.jar").toString())
        return """
            gradle.taskGraph.whenReady {
                val jvmTasks = allTasks.filter { task -> task is JavaForkOptions }
                jvmTasks.forEach { task ->
                    task.doFirst {
                        val options = task as JavaForkOptions
                        val jvmArgs = options.jvmArgs?.toMutableList() ?: mutableListOf()
                        jvmArgs.addAll(listOf(${extraJvmArgs.joinToString(", ") { "\"$it\"" }}))
                        jvmArgs.add("-javaagent:${pathToFatJar.absolutePath}=$testClassName,$testMethodName,${fileToDump.absolutePath}${if (extraAgentArgs.isNotEmpty()) ",${extraAgentArgs.joinToString(",")}" else ""}")
                        options.jvmArgs = jvmArgs
                    }
                }
            }
        """.trimIndent()
    }

    private fun getGolderDataFileFor(testClassName: String, testMethodName: String): File {
        val projectName = File(projectPath).name
        return File(Paths.get(testSourcesPath, "resources", "integrationTestData", projectName, "${testClassName}_$testMethodName.txt").toString())
    }

    private fun createInitScriptAsTempFile(content: String): File {
        val tempFile = File.createTempFile("initScript", ".gradle.kts")
        tempFile.writeText(content)
        return tempFile
    }

    abstract fun runGradleTest(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String> = emptyList(),
        extraAgentArgs: List<String> = emptyList(),
        gradleCommands: List<String>,
    )

    fun runGradleTest(
        testClassNamePrefix: String,
        extraJvmArgs: List<String> = emptyList(),
        extraAgentArgs: List<String> = emptyList(),
        gradleCommands: List<String>,
    ) {
        buildTests()
        collectTestClasses(testClassNamePrefix)
            .asTestSuite()
            .forEach { (testClass, testMethods) ->
                testMethods.forEach { testMethod ->
                    println("Running test: ${testClass.name}.${testMethod.name}")
                    runGradleTest(
                        testClass.name,
                        testMethod.name,
                        extraJvmArgs,
                        extraAgentArgs,
                        gradleCommands,
                    )
                }
            }
    }

    private fun List<Class<*>>.asTestSuite(): Map<Class<*>, List<Method>> = associate { it to collectTestMethodsOfClass(it) }
        .filter { (_, methods) -> methods.isNotEmpty() }

    /**
     * Finds `@Test` methods of the specified [clazz].
     * @return all methods of [clazz] annotated with `@Test`.
     */
    private fun collectTestMethodsOfClass(clazz: Class<*>): List<Method> {
        val testMethods = clazz.declaredMethods.filter { method ->
            try {
                method.annotations.any { annotation ->
                    annotation.annotationClass.qualifiedName?.endsWith(".Test") == true
                }
            } catch (e: Exception) {
                println("Error analyzing method for @Test annotation: ${clazz.name}.${method.name}: ${e.message}")
                false
            }
        }
        return testMethods.sortedBy { it.name }
    }


    /**
     * Builds all tests of the specified project from [projectPath].
     * Uses `testClasses` gradle task for that.
     */
    private fun buildTests() {
        createGradleConnection().use { connection ->
            connection.newBuild().forTasks("testClasses").run()
        }
    }

    /**
     * Creates a new gradle connection to the project from [projectPath].
     */
    private fun createGradleConnection(): ProjectConnection = GradleConnector
        .newConnector()
        .forProjectDirectory(File(projectPath))
        .connect()

    private fun collectTestClasses(testClassNamePrefix: String): List<Class<*>> {
        val projectDir = File(projectPath)
        val classFilesPatterns = listOf("classes/kotlin/jvm/test/", "classes/kotlin/test/", "classes/java/test/")
        val testClassesPaths = projectDir.walk()
            .filter {
                it.isFile &&
                it.extension == "class" &&
                !it.path.contains("$") // Skip inner classes, anonymous classes, etc.
            }
            .filter {
                val filePath = it.systemIndependentPath
                classFilesPatterns.any { pattern -> filePath.contains(pattern) }
            }
            .toList()

        val urls = testClassesPaths.map { file ->
            val path = file.systemIndependentPath
            val containingDirectoryPath = classFilesPatterns
                .firstOrNull { pattern -> path.contains(pattern) }
                ?.let { pattern ->
                    val index = path.lastIndexOf(pattern) + pattern.length
                    path.substring(0, index)
                }
                ?: path
            File(containingDirectoryPath).toURI().toURL()
        }.toTypedArray()
        println("URLS: ${urls.toList()}")
        val classLoader = URLClassLoader(urls, this.javaClass.classLoader)
        val testClasses = testClassesPaths
            .map {
                val testClassName: String = it.systemIndependentPath.let { path ->
                    classFilesPatterns.firstOrNull { pattern -> path.contains(pattern) }
                        ?.let { pattern -> path.substringAfterLast(pattern) }
                        ?: path
                }.removeSuffix(".class").replace('/', '.')

                println("loading class file: $testClassName")
                testClassName
            }
            .filter { it.startsWith(testClassNamePrefix) }
            .mapNotNull { testClassName ->
                try {
                    val clazz = classLoader.loadClass(testClassName)
                    // Don't process abstract classes and interfaces
                    if (!Modifier.isAbstract(clazz.modifiers) && !Modifier.isInterface(clazz.modifiers)) {
                        clazz
                    }
                    else null
                } catch (e: Exception) {
                    System.err.println("Failed to load class: $testClassName")
                    e.printStackTrace(System.err)
                    null
                }
            }
        println("Test classes (prefix=$testClassNamePrefix): $testClasses")
        return testClasses.sortedBy { it.name }
    }

    // TODO: rewrite to accept array of tests (or TestSuite maybe better)
    protected fun runGradleTestImpl(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String> = emptyList(),
        extraAgentArgs: List<String> = emptyList(),
        gradleCommands: List<String>,
    ) {
        val tmpFile = File.createTempFile(testClassName + "_" + testMethodName, "")

        createGradleConnection().use { connection ->
            connection
                .newBuild()
                .addArguments(
                    "--init-script",
                    createInitScriptAsTempFile(buildGradleInitScriptToDumpTrace(testClassName, testMethodName, tmpFile, extraJvmArgs, extraAgentArgs)).absolutePath,
                ).forTasks(
                    *gradleCommands.toTypedArray(),
                    "--tests",
                    "$testClassName.$testMethodName",
                ).run()
        }

        // TODO decide how to test: with gold data or run twice?
        val expectedOutput = getGolderDataFileFor(testClassName, testMethodName)
        if (expectedOutput.exists()) {
            Assert.assertEquals(expectedOutput.readText(), tmpFile.readText())
        } else {
            if (OVERWRITE_REPRESENTATION_TESTS_OUTPUT) {
                expectedOutput.parentFile.mkdirs()
                copy(tmpFile, expectedOutput)
                Assert.fail("The gold data file was created. Please rerun the test.")
            } else {
                Assert.fail("The gold data file was not found. " +
                        "Please rerun the test with \"overwriteRepresentationTestsOutput\" option enabled.")
            }
        }
    }

    private fun copy(srcFile: File, destFile: File) {
        val src = FileInputStream(srcFile).getChannel()
        val dest = FileOutputStream(destFile).getChannel()
        dest.transferFrom(src, 0, src.size())
    }

//    fun collectClassesOfDir(path: String): List<Class<*>> {
//        val testClassesDir = File(path)
//        val testClasses = mutableListOf<Class<*>>()
//
//        // Load all compiled test classes
//        if (testClassesDir.exists()) {
//            // Get the test runtime classpath to include all dependencies
////            val testRuntimeClasspath = project.configurations.getByName("jvmTestRuntimeClasspath").files
//            val urls = arrayOf<URL>()
//                //testRuntimeClasspath.map { it.toURI().toURL() }.toTypedArray() + arrayOf(testClassesDir.toURI().toURL())
//
//            val classLoader = URLClassLoader(urls, this.javaClass.classLoader)
//
//            testClassesDir.walk().filter { it.isFile && it.name.endsWith(".class") }.forEach { file ->
//                val relativePath = file.relativeTo(testClassesDir).path
//                val className = relativePath.removeSuffix(".class").replace('/', '.')
//
//                // Skip inner classes, anonymous classes, etc.
//                if (!className.contains('$')) {
//                    try {
//                        val clazz = classLoader.loadClass(className)
//                        // Don't process abstract classes and interfaces
//                        if (!Modifier.isAbstract(clazz.modifiers) && !Modifier.isInterface(clazz.modifiers)) {
//                            testClasses.add(clazz)
//                        }
//                    } catch (_: Exception) {
//                        // Ignore
//                    }
//                }
//            }
//        }
//
//        return testClasses.sortedBy { it.name }
//    }
}

private val File.systemIndependentPath: String
    get() = this.absolutePath.replace(File.separatorChar, '/')