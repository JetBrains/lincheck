/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.jetbrains.lincheck.jvm.agent.findClassBytecode
import org.jetbrains.lincheck.jvm.agent.isClassAlreadyLoaded
import org.jetbrains.lincheck.jvm.agent.analysis.SideEffectChecker
import java.lang.reflect.Modifier

/**
 * Captures a textual rendering of an object via `toString()` — but only when the corresponding
 * `toString()` is provably side-effect-free. Two paths produce a safe verdict:
 *
 * 1. A class-level whitelist of well-known final JDK types (UUID and `java.time.*` value classes)
 *    bypasses bytecode analysis. A defensive runtime check still requires the class to be `final`
 *    OR its `toString` method to be `final`, so a surprising subclass override cannot break the
 *    safety guarantee. The set lives in [WHITELISTED_TO_STRING_CLASSES].
 * 2. Anything else falls back to [SideEffectChecker.checkMethodForSideEffects] against
 *    `<class>.toString()Ljava/lang/String;` — the same analyzer entry point used to validate
 *    watches and conditions in [org.jetbrains.lincheck.jvm.agent.transformers.SnapshotBreakpointTransformer].
 *
 * Verdicts are memoized per `Class<*>` (not per class name)
 * so that two same-named classes loaded by different classloaders don't alias.
 *
 * Even for classes considered safe the actual `toString()` call is wrapped in
 * `runCatching { ... }` — any unexpected exception (including [StackOverflowError]) yields `null`
 * rather than propagating into the tracer hot path. The rendered text is truncated to
 * [MAX_TOSTRING_LENGTH] characters.
 */
object SafeToStringCapturer {

    private val safetyCache = object : ClassValue<Boolean>() {
        override fun computeValue(type: Class<*>): Boolean = isToStringSafe(type)
    }

    fun captureToString(obj: Any): String? {
        if (!safetyCache.get(obj.javaClass)) return null
        return runCatching { obj.toString()?.truncate() }.getOrNull()
    }

    private fun isToStringSafe(clazz: Class<*>): Boolean {
        if (clazz.name in WHITELISTED_TO_STRING_CLASSES && isClassOrToStringFinal(clazz)) {
            return true
        }
        // A bootstrap-loaded class reports a `null` loader, and the bootstrap loader has no
        // `ClassLoader` object to read bytecode through: any loader delegating down to it serves
        // the same `java/**.class` resources.
        // For bootstrap-loaded classes prefer the agent's isolated javaagent payload loader
        // (JDK-only visibility, no application classpath);
        // when it is unavailable, fall back to the application class loader.
        val classLoader = clazz.classLoader
            ?: LincheckInstrumentation.agentClassLoader
            ?: ClassLoader.getSystemClassLoader()
        return SideEffectChecker.checkMethodForSideEffects(
            className = clazz.name,
            methodName = "toString",
            methodDescriptor = "()Ljava/lang/String;",
            bytecodeProvider = classLoader::findClassBytecode,
            isClassLoaded = { internalName -> isClassAlreadyLoaded(internalName, classLoader) },
        ) == null
    }

    private fun isClassOrToStringFinal(clazz: Class<*>): Boolean {
        if (Modifier.isFinal(clazz.modifiers)) return true
        return runCatching { Modifier.isFinal(clazz.getMethod("toString").modifiers) }
            .getOrDefault(false)
    }

    private fun String.truncate(): String =
        if (length > MAX_TOSTRING_LENGTH) "${take(MAX_TOSTRING_LENGTH)}..." else this

    const val MAX_TOSTRING_LENGTH: Int = 100

    /**
     * Final JDK classes whose `toString` is known to be side-effect-free. Bypasses bytecode
     * analysis. The runtime defensive check [isClassOrToStringFinal] still applies, so a
     * non-final class accidentally added here cannot loosen the safety guarantee at runtime.
     */
    private val WHITELISTED_TO_STRING_CLASSES: Set<String> = setOf(
        "java.util.UUID",
        "java.time.LocalDate",
        "java.time.LocalTime",
        "java.time.LocalDateTime",
        "java.time.Instant",
        "java.time.Duration",
        "java.time.Period",
        "java.time.ZonedDateTime",
        "java.time.OffsetDateTime",
        "java.time.OffsetTime",
        "java.time.MonthDay",
        "java.time.Year",
        "java.time.YearMonth",
    )
}
