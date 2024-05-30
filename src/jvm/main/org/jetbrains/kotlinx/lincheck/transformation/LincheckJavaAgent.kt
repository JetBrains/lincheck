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

import net.bytebuddy.agent.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode.*
import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassFileTransformer.shouldTransform
import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassFileTransformer.transformedClassesStress
import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassFileTransformer.nonTransformedClasses
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.instrumentation
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.instrumentationMode
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.instrumentedClasses
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.INSTRUMENT_ALL_CLASSES
import org.jetbrains.kotlinx.lincheck.util.readFieldViaUnsafe
import sun.misc.Unsafe
import org.objectweb.asm.*
import java.io.*
import java.lang.instrument.*
import java.lang.reflect.*
import java.security.*
import java.util.*
import java.util.concurrent.*
import java.util.jar.*

/**
 * Executes [block] with the Lincheck java agent for byte-code instrumentation.
 */
internal inline fun withLincheckJavaAgent(instrumentationMode: InstrumentationMode, block: () -> Unit) {
    LincheckJavaAgent.install(instrumentationMode)
    return try {
        block()
    } finally {
        LincheckJavaAgent.uninstall()
    }
}

internal enum class InstrumentationMode {
    /**
     * In this mode, Lincheck transforms bytecode
     * only to track coroutine suspensions.
     */
    STRESS,

    /**
     * In this mode, Lincheck tracks
     * all shared memory manipulations.
     */
    MODEL_CHECKING
}

/**
 * LincheckJavaAgent represents the Lincheck Java agent responsible for instrumenting bytecode.
 *
 * @property instrumentation The ByteBuddy instrumentation instance.
 * @property instrumentationMode The instrumentation mode to determine which classes to transform.
 */
internal object LincheckJavaAgent {
    /**
     * The [Instrumentation] instance is used to perform bytecode transformations during runtime.
     */
    private val instrumentation = ByteBuddyAgent.install()

    /**
     * Determines how to transform classes;
     * see [InstrumentationMode.STRESS] and [InstrumentationMode.MODEL_CHECKING].
     */
    lateinit var instrumentationMode: InstrumentationMode

    /**
     * Indicates whether the "bootstrap.jar" (see the "bootstrap" project module)
     * is added to the bootstrap class loader classpath.
     * See [install] for details.
     */
    private var isBootstrapJarAddedToClasspath = false

    /**
     * Names (canonical) of the classes that were instrumented since the last agent installation.
     */
    val instrumentedClasses = HashSet<String>()

    /**
     * Dynamically attaches [LincheckClassFileTransformer] to this JVM instance.
     * Please note that the dynamic attach feature will be disabled in future JVM releases,
     * but at the moment of implementing this logic (March 2024), it was the smoothest way
     * to inject code in the user codebase when the `java.base` module also needs to be instrumented.
     */
    fun install(instrumentationMode: InstrumentationMode) {
        this.instrumentationMode = instrumentationMode
        // The bytecode injections must be loaded with the bootstrap class loader,
        // as the `java.base` module is loaded with it. To achieve that, we pack the
        // classes related to the bytecode injections in a separate JAR (see the
        // "bootstrap" project module), and add it to the bootstrap classpath.
        if (!isBootstrapJarAddedToClasspath) { // don't do this twice.
            appendBootstrapJarToClassLoaderSearch()
            isBootstrapJarAddedToClasspath = true
        }
        // Add the Lincheck bytecode transformer to this JVM instance,
        // allowing already loaded classes re-transformation.
        instrumentation.addTransformer(LincheckClassFileTransformer, true)
        // The transformation logic depends on the testing strategy.
        when {
            // If an option to enable transformation of all classes is explicitly set,
            // then we re-transform all the classes
            // (this option is used for testing purposes).
            INSTRUMENT_ALL_CLASSES -> {
                // Re-transform the already loaded classes.
                // New classes will be transformed automatically.
                instrumentation.retransformClasses(*getLoadedClassesToInstrument().toTypedArray())
            }

            // In the stress testing mode, Lincheck needs to track coroutine suspensions.
            // As an optimization, we remember the set of loaded classes that actually
            // have suspension points, so later we can re-transform only those classes.
            instrumentationMode == STRESS -> {
                check(instrumentedClasses.isEmpty())
                val classes = getLoadedClassesToInstrument().filter {
                    val canonicalClassName = it.name
                    // new classes that were loaded after the latest STRESS mode re-transformation
                    !transformedClassesStress.containsKey(canonicalClassName) ||
                    // old classes that were already loaded before and have coroutine method calls inside
                    canonicalClassName in coroutineCallingClasses
                }
                instrumentation.retransformClasses(*classes.toTypedArray())
                instrumentedClasses.addAll(classes.map { it.name })
            }

            // In the model checking mode, Lincheck processes classes lazily, only when they are used.
            instrumentationMode == MODEL_CHECKING -> {
                check(instrumentedClasses.isEmpty())
            }
        }
    }

    private fun appendBootstrapJarToClassLoaderSearch() {
        // The "bootstrap" module is packed to "bootstrap.jar",
        // which is in this JAR resources. We need to append this
        // "bootstrap.jar" to the bootstrap class loader classpath.
        // However, it is impossible to instantiate a `File` instance
        // that leads to a file inside a JAR archive. Therefore,
        // we copy "bootstrap.jar" to a temporary file, adding this
        // temporary file to the bootstrap class loader classpath later on.
        val bootstrapJarAsStream = this.javaClass.getResourceAsStream("/bootstrap.jar")
        val tempBootstrapJarFile = File.createTempFile("lincheck-bootstrap", ".jar")
        bootstrapJarAsStream.use { input ->
            tempBootstrapJarFile.outputStream().use { fileOut ->
                input!!.copyTo(fileOut)
            }
        }
        instrumentation.appendToBootstrapClassLoaderSearch(JarFile(tempBootstrapJarFile))
    }

    private fun getLoadedClassesToInstrument(): List<Class<*>> =
        instrumentation.allLoadedClasses
            .filter(instrumentation::isModifiableClass)
            .filter { shouldTransform(it.name, instrumentationMode) }

    /**
     * Detaches [LincheckClassFileTransformer] from this JVM instance and re-transforms
     * the transformed classes to remove the Lincheck injections.
     */
    fun uninstall() {
        // Remove the Lincheck transformer.
        instrumentation.removeTransformer(LincheckClassFileTransformer)
        // Collect the original bytecode of the instrumented classes.
        val classDefinitions = getLoadedClassesToInstrument()
            .mapNotNull { clazz ->
                val canonicalClassName = clazz.name
                // Skip classes not transformed by Lincheck.
                if (!INSTRUMENT_ALL_CLASSES && canonicalClassName !in instrumentedClasses) {
                    return@mapNotNull null
                }
                // For each class, retrieve its original bytecode.
                val bytes = nonTransformedClasses[clazz.name]
                check(bytes != null) {
                    "Original bytecode for the transformed class ${clazz.name} is missing!"
                }
                ClassDefinition(clazz, bytes)
            }
        // Redefine the instrumented classes back to their original state
        // using the original bytecodes collected previously.
        instrumentation.redefineClasses(*classDefinitions.toTypedArray())
        // Clear the set of classes instrumented in the model checking mode.
        instrumentedClasses.clear()
    }

    /**
     * Ensures that the specified class and all its superclasses are transformed.
     *
     * This function is called before creating a new instance of the specified class
     * or reading a static field of it. It ensures that the whole hierarchy of this class
     * and the classes of all the static fields (this process is recursive) is transformed.
     * Notably, some of these classes may not be loaded yet, and invoking the `<cinit>`
     * during the analysis could cause non-deterministic behaviour (the class initialization
     * is invoked only once, while Lincheck relies on the events reproducibility).
     * To eliminate the issue, this function also loads the class before transformation,
     * thus, initializing it here, in an ignored section of the analysis, re-transforming
     * the class after that.
     *
     * @param canonicalClassName The name of the class to be transformed.
     */
    fun ensureClassHierarchyIsTransformed(canonicalClassName: String) {
        if (INSTRUMENT_ALL_CLASSES) {
            Class.forName(canonicalClassName)
            return
        }
        if (canonicalClassName in instrumentedClasses) return // already instrumented
        ensureClassHierarchyIsTransformed(Class.forName(canonicalClassName), Collections.newSetFromMap(IdentityHashMap()))
    }


    /**
     * Ensures that the given object and all its referenced objects are transformed for Lincheck analysis.
     * If the INSTRUMENT_ALL_CLASSES_IN_MODEL_CHECKING_MODE flag is set to true, no transformation is performed.
     *
     * The function is called upon a test instance creation, to ensure that all the classes related to it are transformed.
     *
     * @param testInstance the object to be transformed
     */
    fun ensureObjectIsTransformed(testInstance: Any) {
        if (INSTRUMENT_ALL_CLASSES) {
            return
        }
        ensureObjectIsTransformed(testInstance, Collections.newSetFromMap(IdentityHashMap()))
    }

    /**
     * Ensures that the given class and all its superclasses are transformed if necessary.
     *
     * @param clazz the class to transform
     */
    private fun ensureClassHierarchyIsTransformed(clazz: Class<*>) {
        if (INSTRUMENT_ALL_CLASSES) {
            return
        }
        if (clazz.name in instrumentedClasses) return // already instrumented
        ensureClassHierarchyIsTransformed(clazz, Collections.newSetFromMap(IdentityHashMap()))
    }

    /**
     * Ensures that the given object and all its referenced objects are transformed according to the provided rules.
     * The transformation is performed recursively, starting from the given object.
     *
     * @param obj The object to be ensured for transformation.
     * @param processedObjects A set of processed objects to avoid infinite recursion.
     */
    private fun ensureObjectIsTransformed(obj: Any, processedObjects: MutableSet<Any>) {
        if (!instrumentation.isModifiableClass(obj.javaClass) || !shouldTransform(obj.javaClass.name, instrumentationMode)) {
            return
        }

        if (processedObjects.contains(obj)) return
        processedObjects += obj

        var clazz: Class<*> = obj.javaClass

        ensureClassHierarchyIsTransformed(clazz)

        while (true) {
            clazz.declaredFields
                .filter { !it.type.isPrimitive }
                .filter { !Modifier.isStatic(it.modifiers) }
                .mapNotNull { readFieldViaUnsafe(obj, it, Unsafe::getObject) }
                .forEach {
                    ensureObjectIsTransformed(it, processedObjects)
                }
            clazz = clazz.superclass ?: break
        }
    }

    /**
     * Ensures that the given class and all its superclasses are transformed.
     *
     * @param clazz The class to be transformed.
     * @param processedObjects Set of objects that have already been processed to prevent duplicate transformation.
     */
    private fun ensureClassHierarchyIsTransformed(clazz: Class<*>, processedObjects: MutableSet<Any>) {
        if (instrumentation.isModifiableClass(clazz) && shouldTransform(clazz.name, instrumentationMode)) {
            instrumentedClasses += clazz.name
            instrumentation.retransformClasses(clazz)
        } else {
            return
        }
        // Traverse static fields.
        clazz.declaredFields
            .filter { !it.type.isPrimitive }
            .filter { Modifier.isStatic(it.modifiers) }
            .mapNotNull { readFieldViaUnsafe(null, it, Unsafe::getObject) }
            .forEach {
                ensureObjectIsTransformed(it, processedObjects)
            }
        clazz.superclass?.let {
            if (it.name in instrumentedClasses) return // already instrumented
            ensureClassHierarchyIsTransformed(it, processedObjects)
        }
    }

    /**
     * FOR TEST PURPOSE ONLY!
     * To test the byte-code transformation correctness, we can transform all classes.
     *
     * Both stress and model checking modes implement some optimizations
     * to avoid re-transforming all loaded into VM classes on each run of a Lincheck test.
     * When this flag is set, these optimizations are disabled, and so
     * the Lincheck agent re-transforms all the loaded classes on each run.
     */
    internal val INSTRUMENT_ALL_CLASSES =
        System.getProperty("lincheck.instrumentAllClasses")?.toBoolean() ?: false
}

internal object LincheckClassFileTransformer : ClassFileTransformer {
    /*
     * In order not to transform the same class several times,
     * Lincheck caches the transformed bytes in this object.
     * Notice that the transformation depends on the [InstrumentationMode].
     * Additionally, this object caches bytes of non-transformed classes.
     */
    val transformedClassesModelChecking = ConcurrentHashMap<String, ByteArray>()
    val transformedClassesStress = ConcurrentHashMap<String, ByteArray>()
    val nonTransformedClasses = ConcurrentHashMap<String, ByteArray>()

    private val transformedClassesCache
        get() = when (instrumentationMode) {
            STRESS -> transformedClassesStress
            MODEL_CHECKING -> transformedClassesModelChecking
        }

    override fun transform(
        loader: ClassLoader?,
        internalClassName: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classBytes: ByteArray
    ): ByteArray? = runInIgnoredSection {
        // If the class should not be transformed, return immediately.
        if (!shouldTransform(internalClassName.canonicalClassName, instrumentationMode)) {
            return null
        }
        // In the model checking mode, we transform classes lazily,
        // once they are used in the testing code.
        if (!INSTRUMENT_ALL_CLASSES &&
            instrumentationMode == MODEL_CHECKING &&
            internalClassName.canonicalClassName !in instrumentedClasses) {
            return null
        }
        return transformImpl(loader, internalClassName, classBytes)
    }

    private fun transformImpl(
        loader: ClassLoader?,
        internalClassName: String,
        classBytes: ByteArray
    ): ByteArray = transformedClassesCache.computeIfAbsent(internalClassName.canonicalClassName) {
        nonTransformedClasses.putIfAbsent(internalClassName.canonicalClassName, classBytes)
        val reader = ClassReader(classBytes)
        val writer = SafeClassWriter(reader, loader, ClassWriter.COMPUTE_FRAMES)
        try {
            reader.accept(LincheckClassVisitor(instrumentationMode, writer), ClassReader.SKIP_FRAMES)
            writer.toByteArray()
        } catch (e: Throwable) {
            System.err.println("Unable to transform $internalClassName")
            e.printStackTrace()
            classBytes
        }
    }

    @Suppress("SpellCheckingInspection")
    fun shouldTransform(className: String, instrumentationMode: InstrumentationMode): Boolean {
        // In the stress testing mode, we can simply skip the standard
        // Java and Kotlin classes -- they do not have coroutine suspension points.
        if (instrumentationMode == STRESS) {
            if (className.startsWith("java.") || className.startsWith("kotlin.")) return false
        }
        // We do not need to instrument most standard Java classes.
        // It is fine to inject the Lincheck analysis only into the
        // `java.util.*` ones, ignored the known atomic constructs.
        if (className.startsWith("java.")) {
            if (className.startsWith("java.util.concurrent.") && className.contains("Atomic")) return false
            if (className.startsWith("java.util.")) return true
            if (className.startsWith("com.sun.")) return false
            return false
        }
        if (className.startsWith("sun.")) return false
        if (className.startsWith("javax.")) return false
        if (className.startsWith("jdk.")) return false
        // We do not need to instrument most standard Kotlin classes.
        // However, we need to inject the Lincheck analysis into the classes
        // related to collections, iterators, random and coroutines.
        if (className.startsWith("kotlin.")) {
            if (className.startsWith("kotlin.collections.")) return true
            if (className.startsWith("kotlin.jvm.internal.Array") && className.contains("Iterator")) return true
            if (className.startsWith("kotlin.ranges.")) return true
            if (className.startsWith("kotlin.random.")) return true
            if (className.startsWith("kotlin.coroutines.jvm.internal.")) return false
            if (className.startsWith("kotlin.coroutines.")) return true
            return false
        }
        if (className.startsWith("kotlinx.atomicfu.")) return false
        // We need to skip the classes related to the debugger support in Kotlin coroutines.
        if (className.startsWith("kotlinx.coroutines.debug.")) return false
        if (className == "kotlinx.coroutines.DebugKt") return false
        // We should never transform the coverage-related classes.
        if (className.startsWith("com.intellij.rt.coverage.")) return false
        // We can also safely do not instrument some libraries for performance reasons.
        if (className.startsWith("com.esotericsoftware.kryo.")) return false
        if (className.startsWith("net.bytebuddy.")) return false
        if (className.startsWith("net.rubygrapefruit.platform.")) return false
        if (className.startsWith("io.mockk.")) return false
        if (className.startsWith("it.unimi.dsi.fastutil.")) return false
        if (className.startsWith("worker.org.gradle.")) return false
        if (className.startsWith("org.objectweb.asm.")) return false
        if (className.startsWith("org.gradle.")) return false
        if (className.startsWith("org.slf4j.")) return false
        if (className.startsWith("org.apache.commons.lang.")) return false
        if (className.startsWith("org.junit.")) return false
        if (className.startsWith("junit.framework.")) return false
        // Finally, we should never instrument the Lincheck classes.
        if (className.startsWith("org.jetbrains.kotlinx.lincheck.")) return false
        if (className.startsWith("sun.nio.ch.lincheck.")) return false
        // All the classes that were not filtered out are eligible for transformation.
        return true
    }
}
