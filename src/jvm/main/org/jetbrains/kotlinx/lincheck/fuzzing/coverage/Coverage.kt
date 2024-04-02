/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.fuzzing.coverage

import Hashing
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData
import kotlin.math.min

class Coverage {
    /**
     * The way we map branch ids to coverage map:
     * - coverage report already contains some ids for every line, but they are sequential, so we have to hash them first.
     * - line ids between classes are the same (meaning that they start from 0 and sequentially grow),
     *   so we have to apply hash of the classname to differentiate same ids from different classes.
     * - since we cannot implement AFL-like coverage map, because our coverage does not differentiate trace order,
     *   then we will use JQF approach: use branches (instruction id, selected arm) and function calls (instruction id), this does not depend on trace order:
     *     1. Branches extraction: we just look at the `myJumpsAndSwitches` variable of a particular line.
     *     2. Method calls extraction: we want to find first line in which new method signature occurred, that would represent the first line of this method body, the hit count for this line is the number for this method calls.
     * - also, for now we always put hit count as 1 or 0, I will allow to use counting later (need to update `CoverageFilter`).
     *
     * - later, I need to utilize `Trace` from each scenario invocation (this will be more like the concurrent guidance)
     */

    companion object {
        const val COVERAGE_MAP_SIZE = (1 shl 16) - 1 // 64Kb, -1 to match the JQF implementation
    }

    /**
     * Each element resembles the mask, which represents the number of hits
     * for current tuple (branch edge, though we don't have the same branch edges as AFL): 1, 2, 3, 4-7, 8-15, 16-31, 32-127, 128+.
     *
     * For now, we always have hits = 1, but I will allow coverage to calculate the exact number of executions.
     */
    private val hits: IntArray = IntArray(COVERAGE_MAP_SIZE)

    fun size(): Int = hits.size

    fun increment(key: Int, hitCount: Int) {
        hits[key] += hitCount
    }

    fun isCovered(key: Int): Boolean =
        if (key >= 0 && key < hits.size)
            hits[key] != 0
        else
            throw IndexOutOfBoundsException("Key '$key' is out of bounds for hits array of length '${hits.size}'")

    fun coveredBranchesCount(): Int = hits.count { it != 0 }

    fun coveredBranchesKeys(): List<Int> = hits.indices.filter { hits[it] != 0 }

    fun clear() = hits.fill(0)

    fun merge(other: Coverage): Boolean {
        if (hits.size != other.hits.size)
            throw RuntimeException("Hits arrays size mismatch: found in `other` ${other.hits.size}, expected ${hits.size}")
        var changed = false

        other.hits.forEachIndexed { index, hitCount ->
            val mask = getBucketMask(hitCount)
            val updated = hits[index] or mask

            if (updated != hits[index]) {
                changed = true
            }

            hits[index] = updated
        }

        return changed
    }

    /**
     * @param hitCount number to get the highest order bit from.
     * @return mask with the highest order bit set from `hitCount` (mask is not greater than 128).
     * */
    private fun getBucketMask(hitCount: Int): Int {
        if (hitCount == 0) return 0

        var bits = min(128, hitCount)
        var mask = 1

        while (bits != 0) {
            bits = bits shr 1
            mask = mask shl 1
        }

        return mask
    }
}

/**
 * Creates `Coverage` instance which resembles the actual coverage information collected during execution.
 */
fun ProjectData.toCoverage(): Coverage {
    val coverage = Coverage()

    classesCollection.filterNotNull().forEach {
        val className = it.name
        val lines = it.lines
        val classNameHash = className.toCharArray().contentHashCode().toLong()

        // We are counting hit {branch, arm} pairs and {lineId} lines
        lines.filterNotNull().filterIsInstance<LineData>().forEach linesForEach@{ lineData ->
            lineData.run {
                if (hits == 0) return@linesForEach
                // bake the classNameHash into lineKey, since lineIds are not unique among all covered classes
                val lineKey = Hashing.hash1(classNameHash, id.toLong(), coverage.size())

                // apply line coverage
                coverage.increment(lineKey, hits)

                // apply branch coverage (if statements)
                if (!jumps.isNullOrEmpty()) {
                    jumps.forEach { jmp ->
                        // for arms, unlike JQF, we are not using static constants,
                        // since there are might be multiple if statement on a single line, but their arms are different
                        registerBranchCoverage(coverage, lineKey, jmp.getId(false), jmp.falseHits)
                        registerBranchCoverage(coverage, lineKey, jmp.getId(true), jmp.trueHits)
                    }
                }


                // apply branch coverage (switch cases)
                if (!switches.isNullOrEmpty()) {
                    switches.forEach { swtch ->
                        // switch case branches
                        swtch.keys.indices.forEach { index ->
                            val id = swtch.getId(index)
                            val hits = swtch.hits[index]
                            registerBranchCoverage(coverage, lineKey, id, hits)
                        }

                        // default branch
                        registerBranchCoverage(coverage, lineKey, swtch.getId(swtch.hits.size), swtch.defaultHits)
                    }
                }
            }
        }
    }

    return coverage
}


/**
 * Adds `hitCount` to the covered branch inside `coverage`.
 *
 * Branch key is calculated using `lineKey` and `armId`.
 * */
private fun registerBranchCoverage(coverage: Coverage, lineKey: Int, armId: Int, hitCount: Int) {
    if (hitCount != 0) {
        val branchKey = Hashing.hash1(lineKey.toLong(), armId.toLong(), coverage.size())
        coverage.increment(branchKey, hitCount)
    }
}