/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.representation

import java.util.concurrent.atomic.*

/**
 * This test checks that AFU calls captured in an incorrect interleaving have proper representation.
 * Instead of `compareAndSet(object, 1, 2)` representation should be `fieldName.compareAndSet(1, 2)`,
 * where `fieldName` is the parameter in constructor for the AFU.
 */
class AFUCallRepresentationTest : BaseTraceRepresentationTest("afu_call_representation") {
    @Volatile
    private var counter = 0
    private val afu = AtomicIntegerFieldUpdater.newUpdater(AFUCallRepresentationTest::class.java, "counter")

    override fun operation() {
        afu.get(this)
        afu.set(this, 1)
        afu.compareAndSet(this, 1, 2)
    }
}
