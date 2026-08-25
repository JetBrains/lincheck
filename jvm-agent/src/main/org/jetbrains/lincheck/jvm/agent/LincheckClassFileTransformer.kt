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
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation.instrumentationStrategy
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation.instrumentationMode
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation.instrumentedClasses
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation.transformationProfile
import org.jetbrains.lincheck.jvm.agent.blocklist.BlocklistEngine
import org.jetbrains.lincheck.jvm.agent.blocklist.DynamicExtentChecker
import org.jetbrains.lincheck.settings.LiveDebuggerSettings
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

object LincheckClassFileTransformer : ClassFileTransformer {
    /*
     * In order not to transform the same class several times,
     * Lincheck caches the transformed bytes in this object.
     * Notice that the transformation depends on the [InstrumentationMode].
     * Additionally, this object caches bytes of non-transformed classes.
     */
    private val transformedClassesCachesByMode =
        ConcurrentHashMap<InstrumentationMode, ConcurrentHashMap<String, ByteArray>>()

    val transformedClassesCache: MutableMap<String, ByteArray>
        get() = transformedClassesCachesByMode.computeIfAbsent(instrumentationMode) { ConcurrentHashMap() }

    private val statsTracker: TransformationStatisticsTracker? =
        if (collectTransformationStatistics) TransformationStatisticsTracker() else null
    
    val liveDebuggerSettings = LiveDebuggerSettings()

    /** Authoritative sensitive-area blocklist matcher, backed by the settings' blocklist registry. */
    val blocklistEngine = BlocklistEngine(liveDebuggerSettings.blocklistRegistry)

    /** Dynamic-extent (Stage 3) checker over [blocklistEngine]; consulted on the capture hot path. */
    val dynamicExtentChecker = DynamicExtentChecker(blocklistEngine)

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
        if (LincheckInstrumentation.instrumentationState == InstrumentationState.UNINSTALLING) {
            return if (classBeingRedefined != null) classBytes else null
        }

        // If the class should not be transformed, return immediately.
        if (!shouldTransform(internalClassName.toCanonicalClassName(), instrumentationMode)) {
            return null
        }

        // If lazy mode is used, transform classes lazily,
        // once they are used in the testing code.
        if (instrumentationStrategy == InstrumentationStrategy.LAZY &&
            // do not re-transform already instrumented classes
            internalClassName.toCanonicalClassName() !in instrumentedClasses &&
            // always transform eagerly instrumented classes
            !isEagerlyInstrumentedClass(internalClassName.toCanonicalClassName())
        ) {
            return null
        }

        if (!instrumentationMode.useBytecodeCache) {
            return transformImpl(loader, internalClassName, classBytes)
        }
        return transformedClassesCache.computeIfAbsent(internalClassName.toCanonicalClassName()) {
            transformImpl(loader, internalClassName, classBytes)
        }
    }

    fun transformImpl(
        loader: ClassLoader?,
        internalClassName: String,
        classBytes: ByteArray
    ): ByteArray {
        Logger.debug { "Transforming $internalClassName" }

        val reader = ClassReader(classBytes)

        // the following code is required for local variables access tracking
        val classNode = ClassNode()
        reader.accept(classNode, ClassReader.EXPAND_FRAMES)

        val profile = transformationProfile
        val writer = SafeClassWriter(reader, loader, ClassWriter.COMPUTE_FRAMES)

        try {
            val classInfo = buildClassInformation(classNode, reader, profile, blocklistEngine, liveDebuggerSettings)
            val visitor = LincheckClassVisitor(writer, classInfo, instrumentationMode, profile, statsTracker, LincheckInstrumentation.context)
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

    @Suppress("SpellCheckingInspection")
    fun shouldTransform(className: String, instrumentationMode: InstrumentationMode): Boolean {
        // NEVER instrument the Lincheck classes.
        // Perform these checks FIRST to avoid potential class loading circularity errors.
        if (isInLincheckPackage(className)) return false

        // Under lazy strategy instrument eagerly instrumented classes early-on.
        if (instrumentationStrategy == InstrumentationStrategy.LAZY && isEagerlyInstrumentedClass(className))
            return true

        return transformationProfile.shouldTransform(className)
    }

    // We should always eagerly transform the following classes.
    internal fun isEagerlyInstrumentedClass(className: String): Boolean =
        // `ClassLoader` classes, to wrap `loadClass` methods in the ignored section.
        isClassLoaderClassName(className) ||
        // `MethodHandle` class, to wrap its methods (except `invoke` methods) in the ignored section.
        isMethodHandleRelatedClass(className) ||
        // `StackTraceElement` class, to wrap all its methods into the ignored section.
        isStackTraceElementClass(className) ||
        // IntelliJ runtime agents, to wrap all their methods into the ignored section.
        isIntellijRuntimeAgentClass(className) ||
        // `ThreadContainer` classes, to detect threads started in the thread containers.
        isThreadContainerClass(className) ||
        // TODO: instead of eagerly instrumenting `DispatchedContinuation`
        //  we should try to fix lazy class re-transformation logic
        isCoroutineDispatcherInternalClass(className) ||
        isCoroutineConcurrentKtInternalClass(className)
}

private val InstrumentationMode.useBytecodeCache: Boolean get() = when (this) {
    LIVE_DEBUGGING -> false
    else -> true
}
