/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

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

abstract class AbstractTraceIntegrationTest {
    abstract val fatJarName: String
    abstract val projectPath: String

    // Store the runtime classpath URLs
    private var runtimeClasspathUrls: Array<URL> = emptyArray()

    private fun buildGradleInitScriptToDumpTrace(
        testClassName: String,
        testMethodName: String,
        fileToDump: File,
        extraJvmArgs: List<String>,
        extraAgentArgs: List<String>,
    ): String {
        val pathToFatJar = File(Paths.get("build", "libs", fatJarName).toString())
        return """
            gradle.taskGraph.whenReady {
                val jvmTasks = allTasks.filter { task -> task is JavaForkOptions }
                jvmTasks.forEach { task ->
                    task.doFirst {
                        val options = task as JavaForkOptions
                        val jvmArgs = options.jvmArgs?.toMutableList() ?: mutableListOf()
                        jvmArgs.addAll(listOf(${extraJvmArgs.joinToString(", ") { "\"$it\"" }}))
                        jvmArgs.add("-javaagent:${pathToFatJar.absolutePath.escape()}=$testClassName,$testMethodName,${fileToDump.absolutePath.escape()}${if (extraAgentArgs.isNotEmpty()) ",${extraAgentArgs.joinToString(",")}" else ""}")
                        options.jvmArgs = jvmArgs
                    }
                }
            }
        """.trimIndent()
    }

    private fun getGoldenDataFileFor(testClassName: String, testMethodName: String): File {
        val projectName = File(projectPath).name
        return File(Paths.get("src", "main", "resources", "integrationTestData", projectName, "${testClassName}_$testMethodName.txt").toString())
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
        checkRepresentation: Boolean = true,
    )

    fun runGradleTests(
        testClassNamePrefix: String,
        extraJvmArgs: List<String> = emptyList(),
        extraAgentArgs: List<String> = emptyList(),
        gradleBuildCommands: List<String>,
        gradleTestCommands: List<String>,
        checkRepresentation: Boolean = true,
    ) {
        buildTests(gradleBuildCommands)
        collectTestClasses(testClassNamePrefix)
            .asTestSuite()
            .forEach { (testClass, testMethods) ->
                testMethods.forEach { testMethod ->
                    println("Running test: ${testClass.name}::${testMethod.name}(${testMethod.parameters.joinToString(", ") { it.type.simpleName }})")
                    runGradleTest(
                        testClass.name,
                        testMethod.name,
                        extraJvmArgs,
                        extraAgentArgs,
                        gradleTestCommands,
                        checkRepresentation
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
        checkRepresentation: Boolean = true,
    ) {
        val tmpFile = File.createTempFile(testClassName + "_" + testMethodName, "")

        createGradleConnection().use { connection ->
            connection
                .newBuild()
                .setStandardError(System.err)
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
        if (checkRepresentation) { // otherwise we just want to make sure that tests do not fail
            val expectedOutput = getGoldenDataFileFor(testClassName, testMethodName)
            if (expectedOutput.exists()) {
                Assert.assertEquals(expectedOutput.readText(), tmpFile.readText())
            } else {
                if (OVERWRITE_REPRESENTATION_TESTS_OUTPUT) {
                    expectedOutput.parentFile.mkdirs()
                    copy(tmpFile, expectedOutput)
                    Assert.fail("The gold data file was created. Please rerun the test.")
                } else {
                    Assert.fail(
                        "The gold data file was not found at '${expectedOutput.absolutePath}'. " +
                        "Please rerun the test with \"overwriteRepresentationTestsOutput\" option enabled."
                    )
                }
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
                System.err.println("Error analyzing method for @Test annotation: ${clazz.name}.${method.name}: ${e.message}")
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
    private fun buildTests(gradleBuildCommands: List<String>) {
        // Create a temporary file to store the classpath
        val classpathFile = File.createTempFile("classpath", ".txt")
        classpathFile.deleteOnExit()

        // Create an init script to output the classpath to the file
        val initScript = createInitScriptToOutputClasspath(classpathFile, gradleBuildCommands)
        val initScriptFile = createInitScriptAsTempFile(initScript)

        createGradleConnection().use { connection ->
            // Build the test classes and extract the classpath
            connection.newBuild()
                .setStandardError(System.err)
                .forTasks(*gradleBuildCommands.toTypedArray())
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
                } catch (e: Exception) {
                    System.err.println("Error reading classpath from file: ${e.message}")
                    e.printStackTrace()
                }
            } else {
                System.err.println("Warning: Classpath file is empty or does not exist")
            }
        }
    }

    /**
     * Creates an init script that outputs the test runtime classpath to a file.
     */
    private fun createInitScriptToOutputClasspath(outputFile: File, gradleBuildCommands: List<String>): String {
        return """
            gradle.taskGraph.whenReady {
                val buildTasks = listOf(${gradleBuildCommands.joinToString(",") { "\"$it\"" }})
                    .map { if (it.startsWith(":")) it.drop(1) else it }
    
                allTasks.forEach { task ->
                    if (task.name in buildTasks) {
                        task.doLast {
                            val project = task.project
                            val classpath = project.configurations.findByName("jvmTestRuntimeClasspath") ?:
                                            project.configurations.findByName("testRuntimeClasspath")

                            if (classpath != null) {
                                val classpathString = classpath.files.joinToString(File.pathSeparator)
                                val outputFile = File("${outputFile.absolutePath}")
                                outputFile.writeText(classpathString)
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
                (testClassDirUrls.toList() + runtimeClasspathUrls.toList()).distinct().toTypedArray()
            } else {
                testClassDirUrls
            }

        val classLoader = URLClassLoader(combinedUrls, this.javaClass.classLoader)
        val testClasses = testClassesPaths
            .map {
                val testClassName: String = it.systemIndependentPath.let { path ->
                    classFilesPatterns.firstOrNull { pattern -> path.contains(pattern) }
                        ?.let { pattern -> path.substringAfterLast(pattern) }
                        ?: path
                }.removeSuffix(".class").replace('/', '.')
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
        return testClasses.sortedBy { it.name }
    }

}

private val File.systemIndependentPath: String
    get() = this.absolutePath.replace(File.separatorChar, '/')

private fun String.escape(): String = this.replace("\\", "\\\\")