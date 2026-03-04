/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.diff

/**
 * Options for trace diff.
 */
@ConsistentCopyVisibility
data class TraceDiffOptions private constructor (
    val threadsMatchingStrategy: ThreadMatchingStrategy,
    val customThreadNameMap: Map<String, String>?,
    val customThreadIdxMap: Map<Int, Int>?,
    val unmatchedThreadsBehavior: UnmatchedThreadsBehavior,
    val forceMatchStartThreads: Boolean,
    val diffOnlyStartThreads: Boolean,
    val forceDiff: Boolean
) {
    /**
     * Strategy to match threads for diff.
     */
    enum class ThreadMatchingStrategy {
        /**
         * Match threads with same names.
         * This is default.
         */
        AUTO_BY_NAME,

        /**
         * Match threads with same index.
         */
        AUTO_BY_INDEX,

        /**
         * Use custom provided map from thread name to thread name.
         */
        CUSTOM_BY_NAME,

        /**
         * Use custom provided map from thread index to thread index.
         */
        CUSTOM_BY_INDEX;

        fun isAuto(): Boolean = this == AUTO_BY_NAME || this == AUTO_BY_INDEX
        fun isCustom(): Boolean = !isAuto()
    }

    /**
     * What to do if there is left unmatched threads in left or right trace.
     */
    enum class UnmatchedThreadsBehavior {
        /**
         * Skip such threads and don'te their traces.
         */
        SKIP,

        /**
         * Diff these threads with "empty" trace, make them all-remove or all-add.
         * This is default.
         */
        DIFF,

        /**
         * Throw error if there are unmatched threads.
         */
        ERROR
    }

    class DiffOptionsBuilder internal constructor(
        val threadsMatchingStrategy: ThreadMatchingStrategy,
        val customThreadIdMap: Map<Int, Int>?,
        val customThreadNameMap: Map<String, String>?
    ) {
        private var unmatchedThreadsBehavior = UnmatchedThreadsBehavior.DIFF
        private var forceMatchStartThreads= false
        private var diffOnlyStartThreads = false
        private var forceDiff = false

        /**
         * Set option to match two threads winch contain trace point with event id = 0, no matter what are
         * their names or indices.
         *
         * It will throw error if selected matching strategy is [ThreadMatchingStrategy.CUSTOM_BY_INDEX] or
         * [ThreadMatchingStrategy.CUSTOM_BY_NAME].
         */
        fun matchStartThreads(): DiffOptionsBuilder {
            check(threadsMatchingStrategy.isAuto()) { "Cannot match start threads when match strategy is custom." }
            forceMatchStartThreads = true
            return this
        }

        /**
         * Set option to diff only two threads winch contain trace point with event id = 0 and skip mall others.
         *
         * It implies [matchStartThreads]
         *
         * It will throw error if selected matching strategy is [ThreadMatchingStrategy.CUSTOM_BY_INDEX] or
         * [ThreadMatchingStrategy.CUSTOM_BY_NAME].
         */
        fun onlyDiffStartThreads(): DiffOptionsBuilder {
            check(threadsMatchingStrategy.isAuto()) { "Cannot diff only start threads when match strategy is custom." }
            forceMatchStartThreads = true
            diffOnlyStartThreads = true
            return this
        }

        /**
         * If there is threads without pairs, diff them with "emptiness".
         */
        fun diffUnmatchedThreads(): DiffOptionsBuilder {
            unmatchedThreadsBehavior = UnmatchedThreadsBehavior.DIFF
            return this
        }

        /**
         * If there is threads without pairs, skip them.
         */
        fun skipUnmatchedThreads(): DiffOptionsBuilder {
            unmatchedThreadsBehavior = UnmatchedThreadsBehavior.SKIP
            return this
        }

        /**
         * If there is threads without pairs, throw error.
         */
        fun errorOnUnmatchedThreads(): DiffOptionsBuilder {
            unmatchedThreadsBehavior = UnmatchedThreadsBehavior.ERROR
            return this
        }

        /**
         * Force diff even if left and/or right are diffs themselves.
         */
        fun forceDiff(): DiffOptionsBuilder {
            forceDiff = true
            return this
        }

        /**
         * Build options.
         */
        fun build(): TraceDiffOptions = TraceDiffOptions(
            threadsMatchingStrategy = threadsMatchingStrategy,
            customThreadNameMap = customThreadNameMap,
            customThreadIdxMap = customThreadIdMap,
            unmatchedThreadsBehavior = unmatchedThreadsBehavior,
            forceMatchStartThreads = forceMatchStartThreads,
            diffOnlyStartThreads = diffOnlyStartThreads,
            forceDiff = forceDiff
        )
    }

    companion object {
        val DEFAULT = TraceDiffOptions(
            threadsMatchingStrategy = ThreadMatchingStrategy.AUTO_BY_NAME,
            customThreadNameMap = null,
            customThreadIdxMap = null,
            unmatchedThreadsBehavior = UnmatchedThreadsBehavior.DIFF,
            forceMatchStartThreads = false,
            diffOnlyStartThreads = false,
            forceDiff = false
        )

        /**
         * Start to build options which will match threads by name.
         */
        fun matchThreadsByName(): DiffOptionsBuilder = DiffOptionsBuilder(ThreadMatchingStrategy.AUTO_BY_NAME, null, null)

        /**
         * Start to build options which will match threads by index.
         */
        fun matchThreadsByIndex(): DiffOptionsBuilder = DiffOptionsBuilder(ThreadMatchingStrategy.AUTO_BY_INDEX, null, null)

        /**
         * Start to build options which will match threads with custom index mapping.
         *
         * @param map Map to pair threads. Keys are threads' indices in the "left" trace and values are threads' indices
         * in the "right" trace. If some indices are not valid, error will be thrown.
         */
        fun customThreadIndexMap(map: Map<Int, Int>) = DiffOptionsBuilder(ThreadMatchingStrategy.CUSTOM_BY_INDEX, map, null)

        /**
         * Start to build options which will match threads with custom name mapping.
         *
         * @param map Map to pair threads. Keys are threads' names in the "left" trace and values are threads' names
         * in the "right" trace. If some indices are not valid, error will be thrown.
         */
        fun customThreadNameMap(map: Map<String, String>) = DiffOptionsBuilder(ThreadMatchingStrategy.CUSTOM_BY_NAME, null, map)
    }
}
