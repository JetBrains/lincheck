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
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Paths

abstract class AbstractIntegrationTest {
    abstract val projectPath: String
    protected abstract val testSourcesPath: String

    // Store the runtime classpath URLs
    private var runtimeClasspathUrls: Array<URL> = emptyArray()

    private fun buildGradleInitScriptToDumpTrace(
        testClassName: String,
        testMethodName: String,
        fileToDump: File,
        extraJvmArgs: List<String>,
        extraAgentArgs: List<String>,
    ): String {
        println("Building init script to dump trace to file: ${fileToDump.absolutePath}, extraJvmArgs=$extraJvmArgs, extraAgentArgs=$extraAgentArgs")
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

    protected abstract fun runGradleTest(
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
            .asTestSuite().also { println("Test suite:\n$it") }
            .forEach { (testClass, testMethods) ->
                testMethods.forEach { testMethod ->
                    println("Running test: ${testClass.name}::${testMethod.name}")
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

    // TODO: rewrite to accept array of tests (or TestSuite maybe better)
    protected fun runGradleTestImpl(
        testClassName: String,
        testMethodName: String,
        extraJvmArgs: List<String> = emptyList(),
        extraAgentArgs: List<String> = emptyList(),
        gradleCommands: List<String>,
    ) {
        println("Running Gradle test: testClass=$testClassName method=$testMethodName, gradlecommands=$gradleCommands, extraJvmArgs=$extraJvmArgs, extraAgentArgs=$extraAgentArgs")
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
     * Also extracts the runtime classpath for the tests.
     */
    private fun buildTests() {
        // Create a temporary file to store the classpath
        val classpathFile = File.createTempFile("classpath", ".txt")
        classpathFile.deleteOnExit()

        // Create an init script to output the classpath to the file
        val initScript = createInitScriptToOutputClasspath(classpathFile)
        val initScriptFile = createInitScriptAsTempFile(initScript)

        createGradleConnection().use { connection ->
            // Build the test classes and extract the classpath
            connection.newBuild()
                // TODO: there is a common for multiplatform and jvm task: "testClasses", check if it is possible to use that instead
                .forTasks("compileTestKotlinJvm")
                .setStandardOutput(System.out)
                .withArguments("--init-script", initScriptFile.absolutePath)
                .run()

            // Read the classpath from the file
            if (classpathFile.exists() && classpathFile.length() > 0) {
                try {
                    val classpath = classpathFile.readText().trim()
                    val classpathEntries = classpath.split(File.pathSeparator)
                    runtimeClasspathUrls = classpathEntries
                        .filter { it.isNotEmpty() }
                        .map { File(it).toURI().toURL() }
                        .toTypedArray()
                    println("Extracted runtime classpath with ${runtimeClasspathUrls.size} entries: ${runtimeClasspathUrls.toList()}")
                } catch (e: Exception) {
                    println("Error reading classpath from file: ${e.message}")
                    e.printStackTrace()
                }
            } else {
                println("Warning: Classpath file is empty or does not exist")
            }
        }
    }

    /**
     * Creates an init script that outputs the test runtime classpath to a file.
     */
    private fun createInitScriptToOutputClasspath(outputFile: File): String {
        return """
            gradle.taskGraph.whenReady {
                allTasks.forEach { task ->
                    if (task.name in listOf("compileTestKotlinJvm")) {
                        task.doLast {
                            val project = task.project
                            val classpath = project.configurations.findByName("jvmTestRuntimeClasspath")
                                //project.configurations.findByName("testRuntimeClasspath") ?: 
                                //project.configurations.findByName("testCompileClasspath") ?:
                                           
                            if (classpath != null) {
                                val classpathString = classpath.files.joinToString(File.pathSeparator)
                                val outputFile = File("${outputFile.absolutePath}")
                                outputFile.writeText(classpathString)
                                println("Wrote classpath to: ${outputFile.absolutePath}")
                            } else {
                                println("Could not find test runtime classpath configuration")
                            }
                        }
                    }
                }
            }
        """.trimIndent()
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

        // Get the URLs for the test class directories
        val testClassDirUrls = testClassesPaths.map { file ->
            val path = file.systemIndependentPath
            val containingDirectoryPath = classFilesPatterns
                .firstOrNull { pattern -> path.contains(pattern) }
                ?.let { pattern ->
                    val index = path.lastIndexOf(pattern) + pattern.length
                    path.substring(0, index)
                }
                ?: path
            File(containingDirectoryPath).toURI().toURL()
        }.toSet().toTypedArray()

        // Combine the test class directory URLs with the runtime classpath URLs
        val combinedUrls =
            if (runtimeClasspathUrls.isNotEmpty()) {
                println("Using runtime classpath with ${runtimeClasspathUrls.size} entries")
                (testClassDirUrls.toList() + runtimeClasspathUrls.toList()).distinct().toTypedArray()
            } else {
                println("Warning: Using only test class directories, runtime classpath is empty")
                testClassDirUrls
            }

        println("ClassLoader URLs: ${combinedUrls.size} entries: ${combinedUrls.toList()}")
        val classLoader = URLClassLoader(combinedUrls, this.javaClass.classLoader)
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

}

private val File.systemIndependentPath: String
    get() = this.absolutePath.replace(File.separatorChar, '/')
