/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import net.bytebuddy.agent.ByteBuddyAgent
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode.*
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.install
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.instrumentation
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.instrumentationMode
import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassFileTransformer.transformedClassesStress
import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassFileTransformer.isEagerlyInstrumentedClass
import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassFileTransformer.shouldTransform
import org.jetbrains.kotlinx.lincheck.transformation.transformers.coroutineCallingClasses
import org.jetbrains.lincheck.util.Logger
import org.jetbrains.lincheck.util.*
import java.lang.instrument.Instrumentation
import java.io.File
import java.util.jar.JarFile
import java.util.*

/**
 * Executes [block] with the Lincheck java agent for byte-code instrumentation.
 */
inline fun withLincheckJavaAgent(instrumentationMode: InstrumentationMode, block: () -> Unit) {
    if (isTraceJavaAgentAttached) {
        // In case if trace agent is attached we don't do anything
        block()
    } else {
        // Otherwise we perform instrumentation via ByteBuddy dynamic agent.
        // Initialize instrumentation with dynamic ByteBuddy agent
        // if no agent is attached yet.
        if (!isInstrumentationInitialized) {
            /**
             * Dynamically attaches byte buddy instrumentation to this JVM instance.
             * Please note that the dynamic attach feature will be disabled in future JVM releases,
             * but at the moment of implementing this logic (March 2024), it was the smoothest way
             * to inject code in the user codebase when the `java.base` module also needs to be instrumented.
             */
            instrumentation = ByteBuddyAgent.install()
            isInstrumentationInitialized = true
        }

        // run the testing code with instrumentation
        install(instrumentationMode)
        return try {
            block()
        } finally {
            LincheckJavaAgent.uninstall()
        }
    }
}

enum class InstrumentationMode {
    /**
     * In this mode, Lincheck transforms bytecode
     * only to track coroutine suspensions.
     */
    STRESS,

    /**
     * In this mode, Lincheck tracks
     * all shared memory manipulations.
     */
    MODEL_CHECKING,

    /**
     * In this mode, lincheck tracks and records events in configured method
     * but don't enforce determinism or does any analysis.
     */
    TRACE_RECORDING
}

/**
 * LincheckJavaAgent represents the Lincheck Java agent responsible for instrumenting bytecode.
 *
 * @property instrumentation The ByteBuddy instrumentation instance.
 * @property instrumentationMode The instrumentation mode to determine which classes to transform.
 */
object LincheckJavaAgent {
    /**
     * The [Instrumentation] instance is used to perform bytecode transformations during runtime.
     *
     * It is set either by `TraceDebuggerAgent`/`TraceRecorderAgent` static agents, or on the first call to
     * [withLincheckJavaAgent] which will use [ByteBuddyAgent] dynamic agent instead.
     */
    lateinit var instrumentation: Instrumentation

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
     * Adds [LincheckClassFileTransformer] to this JVM instance.
     * Also, retransforms already loaded classes.
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
                retransformClasses(getLoadedClassesToInstrument())
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
                retransformClasses(classes)
                instrumentedClasses.addAll(classes.map { it.name })
            }

            // In the model checking mode, Lincheck processes classes lazily, only when they are used.
            instrumentationMode == MODEL_CHECKING || instrumentationMode == TRACE_RECORDING -> {
                // Clear the set of instrumented classes in case something get wrong during `uninstall`.
                // For instance, it is possible that Lincheck detects a deadlock, `uninstall` is called,
                // but one of the "deadlocked" thread calls `ensureClassHierarchyIsTransformed` after that,
                // adding a new class to `instrumentedClasses`.
                // TODO: distinguish different runs by associating `instrumentedClasses` with `EventTracker`.
                instrumentedClasses.clear()
                // Transform some predefined classes eagerly on start,
                // because often it's the only place when we can do it
                val eagerlyTransformedClasses = getLoadedClassesToInstrument()
                    .filter { isEagerlyInstrumentedClass(it.name) }
                    .toTypedArray()

                retransformClasses(eagerlyTransformedClasses.asList())
                instrumentedClasses.addAll(eagerlyTransformedClasses.map { it.name })
            }
        }
    }

    private fun retransformClasses(classes: List<Class<*>>) {
        // for some reason, trying to call `retransformClasses` on an empty list can throw NPE on JVM 8
        if (classes.isEmpty()) return

        // failsafe guardrails:
        // 1. first try to retransform all classes in one bulk
        // 2. if transformation fails for some class => retransform classes one by one,
        //    thus skipping and logging failing classes
        try {
            instrumentation.retransformClasses(*classes.toTypedArray())
        } catch (_: Throwable) {
            classes.forEach { retransformClass(it) }
        }
    }

    private fun retransformClass(clazz: Class<*>) {
        try {
            instrumentation.retransformClasses(clazz)
        } catch (t: Throwable) {
            Logger.error { "Failed to retransform class ${clazz.name}" }
            Logger.error(t)
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
        instrumentation.allLoadedClasses.filter { shouldTransform(it, instrumentationMode) }

    private fun canRetransformClass(clazz: Class<*>): Boolean =
        instrumentation.isModifiableClass(clazz) &&
        // java lambda classes are special case --- they are not retransformed themselves,
        // rather their enclosing class is retransformed, see below
        !isJavaLambdaClass(clazz.name)

    private fun shouldTransform(clazz: Class<*>, instrumentationMode: InstrumentationMode): Boolean =
        // Filtering is done in the following order to hide lincheck source classes from
        // the `canRetransform` method which uses `TransformationUtilsKt::isJavaLambdaClass` internally.
        // The other order causes a class linkage error on double definition of `TransformationUtilsKt`
        // when it itself is passed as an argument to `canRetransformClass`.
        shouldTransform(clazz.name, instrumentationMode) && canRetransformClass(clazz)

    /**
     * Detaches [LincheckClassFileTransformer] from this JVM instance and re-transforms
     * the transformed classes to remove the Lincheck injections.
     */
    fun uninstall() {
        // Remove the Lincheck transformer.
        instrumentation.removeTransformer(LincheckClassFileTransformer)
        // Collect the set of instrumented classes.
        val classes = if (INSTRUMENT_ALL_CLASSES)
            getLoadedClassesToInstrument()
        else
            getLoadedClassesToInstrument()
            // Skip classes not transformed by Lincheck.
            .filter { clazz ->
                val canonicalClassName = clazz.name
                canonicalClassName in instrumentedClasses
            }
        // `retransformClasses` uses initial (loaded in VM from disk) class bytecode and reapplies
        // transformations of all agents that did not remove their transformers to this moment;
        retransformClasses(classes)
        // Clear the set of instrumented classes.
        instrumentedClasses.clear()
    }

    /**
     * This function ensures that the whole hierarchy of the given class is transformed for Lincheck analysis.
     *
     * For this function, under the term class hierarchy we assume the following set of classes:
     * - class itself;
     * - its superclasses;
     * - its interfaces;
     * - its enclosing class;
     * - transitive closure of the above.
     *
     * Notably, some of these classes may not be loaded yet, and invoking the `<clinit>`
     * during the analysis could cause non-deterministic behavior
     * (the class initialization is invoked only once, while Lincheck relies on the events reproducibility).
     * To eliminate the issue, this function also loads the class before transformation,
     * thus, initializing it and re-transforming the class after that.
     *
     * Typically, this function should be called:
     * - before creating a new instance of the specified class;
     * - before reading a static field of the specified class;
     * - before calling a static method of the specified class.
     * As such, the invocation pattern of this function mimics the JVMS initialization semantics:
     *   https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.5
     * Also, naturally, this function should be called for the top-level class under analysis.
     *
     *
     * The function should be called from an ignored section of the analysis.
     * If the INSTRUMENT_ALL_CLASSES flag is set to true, no transformation is performed.
     *
     * @param className The name of the class to be transformed.
     */
    fun ensureClassHierarchyIsTransformed(className: String) {
        if (INSTRUMENT_ALL_CLASSES) {
            ClassCache.forName(className)
            return
        }
        if (className in instrumentedClasses) return // already instrumented

        val clazz = ClassCache.forName(className)
        ensureClassHierarchyIsTransformed(clazz)
    }

    /**
     * Ensures that the given object and all objects reachable from it are transformed for Lincheck analysis.
     * The function is called upon a test instance creation to ensure that
     * all the classes related to it are transformed.
     *
     * The function should be called from an ignored section of the analysis.
     * If the INSTRUMENT_ALL_CLASSES flag is set to true, no transformation is performed.
     *
     * @param obj the object to be transformed.
     */
    fun ensureObjectIsTransformed(obj: Any) {
        if (INSTRUMENT_ALL_CLASSES) return
        ensureObjectIsTransformed(obj, identityHashSetOf())
    }

    /**
     * Ensures that the given object and all objects reachable from it are transformed for Lincheck analysis.
     *
     * @param obj The object to be ensured for transformation.
     * @param processedObjects A set of already processed objects.
     */
    private fun ensureObjectIsTransformed(obj: Any, processedObjects: MutableSet<Any>) {
        traverseObjectGraph(obj, processedObjects,
            config = ObjectGraphTraversalConfig(
                traverseStaticFields = true,
            )
        ) { obj ->
            val shouldTransform = shouldTransform(obj.javaClass.name, instrumentationMode)
            val shouldTraverse =
                // optimization and safety net: do not traverse low-level
                // class instances from the standard Java library
                !isLowLevelJavaClass(obj.javaClass.name) ||
                // unless the low-level class needs to be transformed
                shouldTransform

            if (shouldTransform) {
                ensureClassHierarchyIsTransformed(obj.javaClass)
            }
            return@traverseObjectGraph shouldTraverse
        }
    }

    /**
     * Ensures that the whole hierarchy of the given class is transformed for Lincheck analysis.
     *
     * @param clazz The class to be transformed.
     */
    fun ensureClassHierarchyIsTransformed(clazz: Class<*>) {
        if (INSTRUMENT_ALL_CLASSES) return
        if (clazz.name in instrumentedClasses) return // already instrumented

        if (shouldTransform(clazz, instrumentationMode)) {
            instrumentedClasses += clazz.name
            retransformClass(clazz)
        } else if (isJavaLambdaClass(clazz.name)) {
            val enclosingClassName = getJavaLambdaEnclosingClass(clazz.name)
            ensureClassHierarchyIsTransformed(enclosingClassName)
        }

        // Traverse super classes, interfaces, and enclosing class
        val classesToTransform =
            listOfNotNull(clazz.superclass) +
            listOfNotNull(clazz.enclosingClass) +
            clazz.interfaces.asList()

        classesToTransform.forEach {
            ensureClassHierarchyIsTransformed(it)
        }
    }

    private fun isLowLevelJavaClass(className: String) =
        className.startsWith("jdk.") ||
        className.startsWith("sun.misc.") ||
        className.startsWith("java.lang.")

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

var isTraceJavaAgentAttached: Boolean = false
var isInstrumentationInitialized: Boolean = false

internal val dumpTransformedSources by lazy {
    System.getProperty(DUMP_TRANSFORMED_SOURCES_PROPERTY, "false").toBoolean()
}
private const val DUMP_TRANSFORMED_SOURCES_PROPERTY = "lincheck.dumpTransformedSources"