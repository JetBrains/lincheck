/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.blocklist

import org.jetbrains.lincheck.settings.BlockMatch
import java.util.concurrent.ConcurrentHashMap

/**
 * A stack frame found inside a blocked sensitive area, with the rule that blocked it.
 */
class BlockedFrame(val frame: StackTraceElement, val match: BlockMatch)

/**
 * Dynamic-extent (Stage 3) blocklist checker: decides at capture time whether a hit's call stack
 * passes through a blocked sensitive area.
 *
 * Runs on the hitting application thread against the stack the capture path already materializes
 * for the frames panel, so the per-hit cost is one memo lookup per frame. Verdicts are memoized
 * per class name (plus per `class#method` for classes owning method rules) and swapped wholesale
 * on every policy change.
 */
class DynamicExtentChecker(private val engine: BlocklistEngine) {

    private enum class ClassVerdict { BLOCKED, CLEAR, NEEDS_METHOD_CHECK }

    private class ClassEntry(val verdict: ClassVerdict, val match: BlockMatch?)
    private class MethodEntry(val match: BlockMatch?)

    @Volatile private var classVerdicts = ConcurrentHashMap<String, ClassEntry>()
    @Volatile private var methodVerdicts = ConcurrentHashMap<String, MethodEntry>()

    /**
     * Returns the first blocked frame on [stackTrace] (already filtered of lincheck frames),
     * or `null` when the whole extent is clear.
     */
    fun firstBlockedFrame(stackTrace: List<StackTraceElement>): BlockedFrame? {
        if (engine.isEmpty()) return null
        for (frame in stackTrace) {
            val className = frame.className
            var entry = classVerdicts[className]
            if (entry == null) {
                val match = engine.classBlock(className)
                entry = when {
                    match != null -> ClassEntry(ClassVerdict.BLOCKED, match)
                    engine.classOwnsMethodRules(className) -> ClassEntry(ClassVerdict.NEEDS_METHOD_CHECK, null)
                    else -> ClassEntry(ClassVerdict.CLEAR, null)
                }
                memoize(classVerdicts, className, entry)
            }
            when (entry.verdict) {
                ClassVerdict.BLOCKED -> return BlockedFrame(frame, checkNotNull(entry.match))
                ClassVerdict.CLEAR -> {}
                ClassVerdict.NEEDS_METHOD_CHECK ->
                    methodMatch(className, frame.methodName)?.let { return BlockedFrame(frame, it) }
            }
        }
        return null
    }

    /**
     * Drops all memoized verdicts. Must be called on every blocklist-registry mutation;
     * wholesale map swap so in-flight readers keep a consistent (stale-at-worst) view.
     */
    fun invalidate() {
        classVerdicts = ConcurrentHashMap()
        methodVerdicts = ConcurrentHashMap()
    }

    private fun methodMatch(className: String, methodName: String): BlockMatch? {
        val key = "$className#$methodName"
        methodVerdicts[key]?.let { return it.match }
        // A frame carries no descriptor — descriptor-pinned rules match every overload.
        val match = engine.methodBlock(className, methodName, descriptor = null)
        memoize(methodVerdicts, key, MethodEntry(match))
        return match
    }

    private fun <V> memoize(map: ConcurrentHashMap<String, V>, key: String, value: V) {
        // Defensive bound: a pathological class-generating app must not grow the memo unboundedly.
        // Blowing the whole epoch away is fine — it just re-warms.
        if (map.size >= MAX_MEMO_ENTRIES) invalidate()
        map[key] = value
    }

    private companion object {
        const val MAX_MEMO_ENTRIES = 100_000
    }
}
