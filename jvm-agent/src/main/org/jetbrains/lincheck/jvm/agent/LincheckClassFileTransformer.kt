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

import org.jetbrains.lincheck.jvm.agent.InstrumentationMode.*
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation.instrumentedClasses
import org.jetbrains.lincheck.settings.liveDebuggerSettings
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.util.*
import org.objectweb.asm.*
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.util.TraceClassVisitor
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import java.util.concurrent.ConcurrentHashMap

/**
 * The Lincheck bytecode transformer, registered with the JVM for the duration of one
 * [LincheckInstrumentation.install] / [LincheckInstrumentation.uninstall] cycle.
 *
 * @property instrumentationMode The instrumentation mode, see [InstrumentationMode].
 * @property instrumentationStrategy Whether classes are transformed lazily or eagerly, see [InstrumentationStrategy].
 * @property transformationProfile Controls which classes and methods are transformed and how.
 * @property transformedClassesCache Transformed class bytes by canonical class name;
 *   shared between installations in the same [instrumentationMode].
 * @property context Trace context the injected code reports to.
 */
class LincheckClassFileTransformer(
    val instrumentationMode: InstrumentationMode,
    val instrumentationStrategy: InstrumentationStrategy,
    val transformationProfile: TransformationProfile,
    val transformedClassesCache: MutableMap<String, ByteArray>,
    val context: TraceContext,
) : ClassFileTransformer {

    @Volatile
    private var instrumentationState = InstrumentationState.ACTIVE

    /**
     * A thread-safe set that holds the names of classes that were scheduled for transformation
     * but have not yet been processed.
     */
    private val pendingLazilyTransformedClasses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val statsTracker: TransformationStatisticsTracker? =
        if (collectTransformationStatistics) TransformationStatisticsTracker() else null

    override fun transform(
        loader: ClassLoader?,
        internalClassName: String?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classBytes: ByteArray
    ): ByteArray? = runInsideIgnoredSection {
        if (classBeingRedefined != null) {
            require(internalClassName != null) {
                "Internal class name of redefined class ${classBeingRedefined.name} must not be null"
            }
        }

        // Internal class name could be `null` in some cases (can be witnessed on JDK-8),
        // this can be related to the Kotlin compiler bug:
        // - https://youtrack.jetbrains.com/issue/KT-16727/
        if (internalClassName == null) return null

        // While `uninstall` reverts the instrumentation, hand the class bytes back unchanged
        // instead of detaching the transformer and letting the re-transformation see no transformer at all.
        //
        // When no attached agent modifies the bytes, the JVM treats the re-transformation as
        // "not modified by any agent" and, since JDK 20 (JDK-7124710), drops its cached copy of the
        // original class file. The next re-transformation then reconstitutes the class file from the
        // already redefined class, whose constant pool is the result of the previous constant pool merge.
        // Feeding that pool back into the next merge doubles the number of duplicated entries on every
        // install/uninstall cycle, until the merged pool exceeds the u2 limit of 65535 entries and
        // `Instrumentation.retransformClasses` starts failing --- with every merge in between
        // quadratic in the pool size and performed at a safepoint.
        //
        // Returning the bytes explicitly marks the class as modified by a re-transformation capable agent,
        // so the JVM keeps caching the original class file, as it does on JDK < 20.
        if (instrumentationState == InstrumentationState.UNINSTALLING) {
            return if (classBeingRedefined != null) classBytes else null
        }

        val canonicalClassName = internalClassName.toCanonicalClassName()

        // If requested by the instrumentation mode, index every class the transformer sees by its source file —
        // including classes that are not instrumented now: a breakpoint in their source file may
        // arrive later, and the index is how their `Class` objects are found for re-transformation.
        if (instrumentationMode.maintainsSourceFileIndex &&
            LincheckInstrumentation.isIndexedClassName(canonicalClassName)
        ) {
            SourceFileClassIndex.registerClass(
                loader = loader,
                className = canonicalClassName,
                classBytes = classBytes,
            )
        }

        // If the class should not be transformed, return immediately.
        if (!LincheckInstrumentation.shouldTransform(canonicalClassName, loader, transformationProfile)) {
            return null
        }

        // If lazy mode is enabled (and the class is not in the list of eagerly instrumented classes),
        // transform it lazily, only once it was used in the code.
        // If the class was reached at runtime, the event tracker should have
        // called `ensureClassHierarchyIsTransformed(...)` on it, which, in turn,
        // should have added it into the `lazyTransformationClassQueue`.
        if (instrumentationStrategy == InstrumentationStrategy.LAZY &&
            !LincheckInstrumentation.isEagerlyInstrumentedClass(canonicalClassName)
        ) {
            // If the class was not requested for transformation, return immediately,
            // otherwise it will be transformed by the code below.
            val wasRequested = pendingLazilyTransformedClasses.remove(canonicalClassName)
            if (!wasRequested) return null
        }

        val transformedBytes = if (!instrumentationMode.useBytecodeCache) {
            doTransform(loader, internalClassName, classBytes)
        } else {
            transformedClassesCache.computeIfAbsent(canonicalClassName) {
                doTransform(loader, internalClassName, classBytes)
            }
        }

        // Remember every class handed back modified,
        // whether re-transformed on `install` or transformed on loading while the agent is active,
        // so `uninstall` reverts exactly these classes.
        instrumentedClasses += canonicalClassName

        return transformedBytes
    }

    fun doTransform(
        loader: ClassLoader?,
        internalClassName: String,
        classBytes: ByteArray
    ): ByteArray {
        Logger.debug { "Transforming $internalClassName" }

        val reader = ClassReader(classBytes)

        // the following code is required for local variables access tracking
        val classNode = ClassNode()
        reader.accept(classNode, ClassReader.EXPAND_FRAMES)

        val writer = SafeClassWriter(reader, loader, ClassWriter.COMPUTE_FRAMES)

        try {
            val classInfo = buildClassInformation(classNode, reader, transformationProfile, liveDebuggerSettings, loader)
            val visitor = LincheckClassVisitor(writer, classInfo, instrumentationMode, transformationProfile, statsTracker, context)
            val timeNano = measureTimeNano {
                classNode.accept(visitor)
            }
            return writer.toByteArray().also { transformedBytes ->
                if (dumpTransformedSources) {
                    dumpClassBytecode(classNode.name, transformedBytes)
                }
                statsTracker?.saveStatistics(
                    originalClassNode = classNode,
                    originalClassBytes = classBytes,
                    transformedClassBytes = transformedBytes,
                    transformationTimeNanos = timeNano,
                )
            }
        } catch (e: Throwable) {
            Logger.warn(e) { "Unable to transform $internalClassName, proceeding without instrumentation" }
            return classBytes
        }
    }

    /**
     * Switches the transformer to handing the original class bytes back unchanged,
     * so that re-transforming the instrumented classes reverts them,
     * see [transform] for why the transformer stays attached meanwhile.
     */
    fun startUninstall() {
        instrumentationState = InstrumentationState.UNINSTALLING
    }

    /**
     * Queues a class for lazy transformation.
     * Has no effect if the current instrumentation strategy is not lazy.
     *
     * @param className The fully qualified name of the class to be queued for lazy transformation.
     */
    fun requestLazyTransformation(className: String) {
        if (instrumentationStrategy != InstrumentationStrategy.LAZY) return
        pendingLazilyTransformedClasses += className
    }

    private fun dumpClassBytecode(className: String, bytes: ByteArray?) {
        val cr = ClassReader(bytes)
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        cr.accept(TraceClassVisitor(pw), 0)
        File("build/transformedBytecode/$className.txt")
            .apply { parentFile.mkdirs() }
            .writeText(sw.toString())
    }

    fun computeStatistics(): TransformationStatistics? =
        statsTracker?.computeStatistics()

    fun resetStatistics() {
        statsTracker?.reset()
    }

    fun reportStatistics() {
        if (collectTransformationStatistics) {
            val writer = StringWriter()
            computeStatistics()?.writeTo(writer)
            resetStatistics()

            Logger.info { "Transformation statistics:\n" +
                writer.toString().lines().joinToString("\n") { "\t$it" }
            }
        }
    }
}

/**
 * The state of a [LincheckClassFileTransformer] lifecycle.
 *
 * Transitions: [ACTIVE] --uninstall--> [UNINSTALLING].
 */
enum class InstrumentationState {
    /**
     * The instrumentation is installed; the transformer instruments matching classes.
     */
    ACTIVE,

    /**
     * [LincheckInstrumentation.uninstall] is reverting the instrumentation:
     * the transformer stays attached but hands the original class bytes back unchanged.
     */
    UNINSTALLING,
}

private val InstrumentationMode.useBytecodeCache: Boolean get() = when (this) {
    LIVE_DEBUGGING -> false
    else -> true
}

private val collectTransformationStatistics by lazy {
    System.getProperty(COLLECT_TRANSFORMATION_STATISTICS_PROPERTY, "false").toBoolean()
}
private const val COLLECT_TRANSFORMATION_STATISTICS_PROPERTY = "lincheck.collectTransformationStatistics"

internal val dumpTransformedSources by lazy {
    System.getProperty(DUMP_TRANSFORMED_SOURCES_PROPERTY, "false").toBoolean()
}
private const val DUMP_TRANSFORMED_SOURCES_PROPERTY = "lincheck.dumpTransformedSources"
