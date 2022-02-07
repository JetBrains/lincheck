/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.test.distributed

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.junit.Test

class NodeAddressResolverTest {
    class AClass : Node<Int> {
        override fun onMessage(message: Int, sender: Int) {
            TODO("Not yet implemented")
        }

        @Operation
        fun op() {
        }
    }

    class BClass : Node<Int> {
        override fun onMessage(message: Int, sender: Int) {
            TODO("Not yet implemented")
        }
    }

    class CClass : Node<Int> {
        override fun onMessage(message: Int, sender: Int) {
            TODO("Not yet implemented")
        }
    }

    @Test
    fun testIdToClass() {
        val nodeInfo = mapOf(
            BClass::class.java to NodeTypeInfo(4, 4, CrashMode.NO_CRASH, NetworkPartitionMode.NONE) { 0 },
            CClass::class.java to NodeTypeInfo(5, 5, CrashMode.NO_CRASH, NetworkPartitionMode.NONE) { 0 }
        )
        val addressResolver = NodeAddressResolver(
            AClass::class.java,
            3,
            nodeInfo
        )
        repeat(3) { check(addressResolver[it] == AClass::class.java) }
        repeat(4) { check(addressResolver[it + 3] == BClass::class.java) }
        repeat(5) { check(addressResolver[it + 7] == CClass::class.java) }
        check(addressResolver[AClass::class.java] == listOf(0, 1, 2))
        check(addressResolver[BClass::class.java] == listOf(3, 4, 5, 6))
        check(addressResolver[CClass::class.java] == listOf(7, 8, 9, 10, 11))
        check(addressResolver.nodeCount == 12)
        check(addressResolver.scenarioSize == 3)
    }

    @Test
    fun testIdToClassWithTestClassInfo() {
        val nodeInfo = mapOf(
            AClass::class.java to NodeTypeInfo(4, 5, CrashMode.NO_CRASH, NetworkPartitionMode.NONE) { 0 },
            BClass::class.java to NodeTypeInfo(4, 4, CrashMode.NO_CRASH, NetworkPartitionMode.NONE) { 0 },
            CClass::class.java to NodeTypeInfo(5, 5, CrashMode.NO_CRASH, NetworkPartitionMode.NONE) { 0 }
        )
        val addressResolver = NodeAddressResolver(
            AClass::class.java,
            3,
            nodeInfo
        )
        repeat(5) { check(addressResolver[it] == AClass::class.java) }
        repeat(4) { check(addressResolver[it + 5] == BClass::class.java) }
        repeat(5) { check(addressResolver[it + 9] == CClass::class.java) }
        check(addressResolver[AClass::class.java] == listOf(0, 1, 2, 3, 4))
        check(addressResolver[BClass::class.java] == listOf(5, 6, 7, 8))
        check(addressResolver[CClass::class.java] == listOf(9, 10, 11, 12, 13))
        check(addressResolver.nodeCount == 14)
        check(addressResolver.scenarioSize == 3)
    }

    @Test
    fun testCrashTypes() {
        val nodeInfo = mapOf(
            AClass::class.java to NodeTypeInfo(4, 5, CrashMode.RECOVER_ON_CRASH, NetworkPartitionMode.COMPONENTS) { it },
            BClass::class.java to NodeTypeInfo(4, 4, CrashMode.NO_CRASH, NetworkPartitionMode.NONE) { it },
            CClass::class.java to NodeTypeInfo(5, 5, CrashMode.FINISH_ON_CRASH, NetworkPartitionMode.NONE) { it / 2 }
        )
        val addressResolver = NodeAddressResolver(
            AClass::class.java,
            3,
            nodeInfo
        )
        repeat(5) {
            check(addressResolver.crashTypeForNode(it) == CrashMode.RECOVER_ON_CRASH)
            check(addressResolver.partitionTypeForNode(it) == NetworkPartitionMode.COMPONENTS)
        }
        check(addressResolver.crashType(AClass::class.java) == CrashMode.RECOVER_ON_CRASH)
        check(addressResolver.partitionType(AClass::class.java) == NetworkPartitionMode.COMPONENTS)
        check(addressResolver.maxNumberOfCrashes(AClass::class.java) == 5)
        repeat(4) {
            check(addressResolver.crashTypeForNode(it + 5) == CrashMode.NO_CRASH)
            check(addressResolver.partitionTypeForNode(it + 5) == NetworkPartitionMode.NONE)
        }
        check(addressResolver.crashType(BClass::class.java) == CrashMode.NO_CRASH)
        check(addressResolver.partitionType(BClass::class.java) == NetworkPartitionMode.NONE)
        check(addressResolver.maxNumberOfCrashes(BClass::class.java) == 0)
        repeat(5) {
            check(addressResolver.crashTypeForNode(it + 9) == CrashMode.FINISH_ON_CRASH)
            check(addressResolver.partitionTypeForNode(it + 5) == NetworkPartitionMode.NONE)
        }
        check(addressResolver.crashType(CClass::class.java) == CrashMode.FINISH_ON_CRASH)
        check(addressResolver.partitionType(CClass::class.java) == NetworkPartitionMode.NONE)
        check(addressResolver.maxNumberOfCrashes(CClass::class.java) == 2)
    }
}