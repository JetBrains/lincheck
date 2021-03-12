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
import java.util.concurrent.ThreadLocalRandom

class Probability(private val testCfg: DistributedCTestConfiguration<*, *>, val numberOfNodes: Int) {
    companion object {
        const val MESSAGE_SENT_PROBABILITY = 0.95
        const val MESSAGE_DUPLICATION_PROBABILITY = 0.9
        const val NODE_FAIL_PROBABILITY = 0.05
    }

    private val rand = ThreadLocalRandom.current()

    fun duplicationRate(): Int {
        if (!messageIsSent()) {
            return 0
        }
        if (!testCfg.messageDuplication) {
            return 1
        }
        return if (rand.nextDouble(1.0) < MESSAGE_DUPLICATION_PROBABILITY) 1 else 2
    }

    private fun messageIsSent(): Boolean {
        if (testCfg.isNetworkReliable) {
            return true
        }
        return rand.nextDouble(1.0) < MESSAGE_SENT_PROBABILITY
    }

    fun nodeFailed(): Boolean {
        return rand.nextDouble(1.0) < NODE_FAIL_PROBABILITY
    }
}