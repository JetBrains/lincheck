/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Comparator
import java.util.function.Function

class UnsafeHolderTest {

    /**
     * Regression test for Lincheck defect L25, sub-case "capturing lambdas inside JDK classes".
     *
     * `Comparator.comparing` returns a capturing lambda whose class is a JDK-internal hidden class
     * (`java.util.Comparator$$Lambda/...`): `Unsafe.objectFieldOffset` rejects hidden classes
     * (so the Unsafe fast path is unavailable), and module `java.base` does not open `java.util`
     * to the unnamed module, so `setAccessible(true)` on the field that holds the captured argument
     * throws `InaccessibleObjectException`. `readFieldSafely` must report this as a failed read
     * instead of letting the exception propagate (it used to crash whatever was walking the object
     * graph, e.g. reflective field traversal reaching such a lambda through tested code).
     *
     * Reproduces independently of the JDK version: lambda classes have been hidden classes since
     * JDK 15, and reflective access to unopened `java.base` packages has been denied by default
     * since JDK 16 -- this is not specific to JDK 21, even though that is the JDK the original
     * crash (in `neo4j`, via `Stream.anyMatch`) was observed on.
     */
    @Test
    fun readFieldSafelyDoesNotThrowForInaccessibleJdkInternalLambdaField() {
        val extractor = Function<String, Int> { it.length }
        val comparator = Comparator.comparing(extractor)
        val lambdaClass = comparator.javaClass
        assertTrue("expected a hidden lambda class", lambdaClass.isHidden)

        val field = lambdaClass.declaredFields.first()
        val result = readFieldSafely(comparator, field)
        assertTrue("expected a failed read, not a propagated exception", result.isFailure)
    }
}
