package org.jetbrains.lincheck_test.project

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import kotlin.test.Test

/**
 *
 */
class TwoSwitchesInOneOperationSyntheticTest {

    @Volatile
    private var firstCount: Int = 0

    @Volatile
    private var secondCount: Int = 0

    @Operation
    fun firstOperation(): Int {
        firstCount = 0
        firstCount++
        if (secondCount == 1) {
            firstCount++
            if (secondCount == 2) {
                return 1
            }
        }
        return 0
    }

    @Operation
    fun secondOperation(): Int {
        secondCount = 0
        if (firstCount == 1) {
            secondCount++
            if (firstCount == 2) {
                secondCount++
            }
        }
        return 0
    }

    @Test
    fun test() = ModelCheckingOptions().check(TwoSwitchesInOneOperationSyntheticTest::class.java)

}