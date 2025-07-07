/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.generator


import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.forClasses
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.junit.Test
import java.lang.AssertionError

/**
 * Checks that if method has an atomic guarantee, then it won't fail
 */
class CustomAtomicGuaranteeTest {

    private var value = NonAtomicCounter()

    @Operation
    fun increment(): Int = value.increment()

    @Test(expected = AssertionError::class)
    fun `test without guarantees`() =  modelCheckingConfiguration().check(this::class.java)
    @Test
    fun `test with guarantees`() = modelCheckingConfiguration()
        .addGuarantee(forClasses(NonAtomicCounter::class).allMethods().treatAsAtomic())
        .check(this::class.java)

    private fun modelCheckingConfiguration() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(CustomAtomicGuaranteeTest::increment) }
                thread { actor(CustomAtomicGuaranteeTest::increment) }
            }
        }
        .iterations(0)

    class NonAtomicCounter {
        @Volatile
        private var value: Int = 0
        fun increment(): Int = value++

    }
}


/**
 * Checks that if we mark all methods of the class as atomic/ignored, then no switch points will be added inside,
 * even if the method is declared in a superclass
 */
class CustomAtomicGuaranteeWithInheritanceTest {

    private var value = ChildNonAtomicCounter()
    @Operation
    fun increment(): Int = value.increment()

    @Test(expected = AssertionError::class)
    fun `test without guarantees`() = modelCheckingConfiguration()
        .check(this::class.java)
    @Test
    fun `test with guarantees`() = modelCheckingConfiguration()
        .addGuarantee(forClasses(ChildNonAtomicCounter::class).allMethods().treatAsAtomic())
        .check(this::class.java)

    open class BaseNonAtomicCounter {
        @Volatile
        protected var value: Int = 0

        fun increment(): Int = value++
    }

    class ChildNonAtomicCounter: BaseNonAtomicCounter()

    private fun modelCheckingConfiguration() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(CustomAtomicGuaranteeWithInheritanceTest::increment) }
                thread { actor(CustomAtomicGuaranteeWithInheritanceTest::increment) }
            }
        }
        .iterations(0)
}