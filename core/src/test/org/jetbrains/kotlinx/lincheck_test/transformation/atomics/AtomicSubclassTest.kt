/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.transformation.atomics

import org.jetbrains.lincheck.datastructures.Options
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Checks that classes inheriting from Atomic* classes are properly tracked.
 */
class AtomicSubclassTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    val atomic = AtomicIntegerSubclass()

    @Operation
    fun reads() {
        val a = atomic.get()
        val b = atomic.get()
        // if atomic subclasses are handled properly,
        // Lincheck should detect this check failure
        check(a == b)
    }

    @Operation
    fun write() {
        atomic.set(42)
    }

    override fun <O : Options<O, *>> O.customize() {
        addCustomScenario {
            parallel {
                thread {
                    actor(::reads)
                }
                thread {
                    actor(::write)
                }
            }
        }
    }

}

class AtomicIntegerSubclass : AtomicInteger() {
    override fun toByte(): Byte {
        return get().toByte()
    }

    override fun toShort(): Short {
        return get().toShort()
    }
}