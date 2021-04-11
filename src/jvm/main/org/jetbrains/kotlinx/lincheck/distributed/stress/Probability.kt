/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.distributed.stress

import org.jetbrains.kotlinx.lincheck.distributed.DistributedCTestConfiguration
import org.jetbrains.kotlinx.lincheck.distributed.RecoveryMode
import kotlin.random.Random

class Probability(
    private val testCfg: DistributedCTestConfiguration<*, *>,
    private val numberOfNodes: Int
) {
    companion object {
        val rand: ThreadLocal<Random> = ThreadLocal.withInitial { Random(Thread.currentThread().hashCode()) }
        const val MESSAGE_SENT_PROBABILITY = 0.95
        const val MESSAGE_DUPLICATION_PROBABILITY = 0.9
        const val NODE_FAIL_PROBABILITY = 0.05
        const val NODE_RECOVERY_PROBABILITY = 0.7
        var failedNodesExpectation = 0
    }

    fun duplicationRate(): Int {
        if (!messageIsSent()) {
            return 0
        }
        if (!testCfg.messageDuplication) {
            return 1
        }
        return if (rand.get().nextDouble(1.0) < MESSAGE_DUPLICATION_PROBABILITY) 1 else 2
    }

    private fun messageIsSent(): Boolean {
        if (testCfg.isNetworkReliable) {
            return true
        }
        return rand.get().nextDouble(1.0) < MESSAGE_SENT_PROBABILITY
    }

    fun nodeFailed() : Boolean {
        val r = rand.get().nextDouble(1.0)
        val p = nodeFailProbability()
        //println("random $r, prob $p, cur $curMsgCount")
        return r < p
    }

    fun nodeRecovered(): Boolean = rand.get().nextDouble(1.0) < NODE_RECOVERY_PROBABILITY

    var prevMsgCount = 0

    var curMsgCount = 0

    var iterations = 0

    private fun nodeFailProbability() : Double {
        return NODE_FAIL_PROBABILITY
        return if (prevMsgCount == 0) {
            0.0
        } else {
            val q = failedNodesExpectation.toDouble() / numberOfNodes
            //println("q is $q")
            q / (prevMsgCount - (curMsgCount - 1) * q)
        }
    }

    fun setInitial() {
        if (prevMsgCount == 0)  prevMsgCount = (prevMsgCount * iterations + curMsgCount) / (iterations + 1)
        iterations++
        curMsgCount = 0
    }
}