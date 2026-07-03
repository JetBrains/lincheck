/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.generator

import org.jetbrains.kotlinx.lincheck.CTestStructure
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the "Generator for parameter Continuation should be specified"
 * failure reported for suspend operations that override a generic supertype method.
 *
 * Since Kotlin 2.4 the compiler copies method/parameter annotations (including `@Operation`/`@Param`)
 * onto the synthetic bridge generated for a covariant/generic override — e.g. the bridge
 * `send(Object, Continuation)` for `send(Int, Continuation)`.
 * Such a bridge carries no Kotlin metadata, so it must not be mistaken for a non-suspend operation
 * whose trailing `Continuation` parameter demands a generator.
 * [CTestStructure] must read operations only from the real declared methods and ignore bridge methods.
 */
class GenericSuspendOperationStructureTest {

    private interface AsyncChannel<T> {
        suspend fun send(element: T)
        suspend fun receive(): T
    }

    @Param(name = "element", gen = IntGen::class)
    class GenericSuspendOperations : AsyncChannel<Int> {
        @Operation
        override suspend fun send(@Param(name = "element") element: Int) = Unit

        @Operation
        override suspend fun receive(): Int = 0
    }

    @Test
    fun `generic suspend operations do not require a generator for the continuation parameter`() {
        // Fails with `IllegalStateException` on Kotlin 2.4+ compiled test classes without the bridge-method fix.
        val structure = CTestStructure.getFromTestClass(GenericSuspendOperations::class.java)
        // Exactly the two logical operations (`send`, `receive`), no duplicate from the bridge methods.
        assertEquals(2, structure.actorGenerators.size)
        assertTrue("suspend operations must be detected as suspendable",
            structure.actorGenerators.all { it.isSuspendable })
    }
}
