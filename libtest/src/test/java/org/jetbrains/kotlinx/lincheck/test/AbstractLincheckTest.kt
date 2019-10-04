package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.strategy.randomsearch.RandomSearchOptions
import org.jetbrains.kotlinx.lincheck.strategy.randomswitch.RandomSwitchOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.lang.AssertionError

/**
 * An abstraction for testing all lincheck strategies
 */
abstract class AbstractLincheckTest(val shouldFail: Boolean, val checkObstructionFreedom: Boolean = false) : VerifierState(){
    @Test
    fun test() {
        OptionsCreator.values().map {
            it.name to it.create(checkObstructionFreedom)
        }.forEach {
            if (!checkObstructionFreedom || it !is StressOptions) {
                println(it.first)
                val options = it.second
                try {
                    LinChecker.check(this.javaClass, options)
                } catch (e: AssertionError) {
                    if (!shouldFail) throw e
                }
            }
        }
    }
}

private enum class OptionsCreator(val create: (checkObstructionFreedom: Boolean) -> Options<*, *>) {
    STRESS_OPTIONS({ StressOptions() }),
    RANDOM_SEARCH_OPTIONS({
        RandomSearchOptions()
                .checkObstructionFreedom(it)
                .iterations(20) // TODO: use default values after coroutine implementation
                .invocationsPerIteration(2000)
    }),
    RANDOM_SWITCH_OPTIONS({
        RandomSwitchOptions()
                .checkObstructionFreedom(it)
                .iterations(20) // TODO: use default values after coroutine implementation
                .invocationsPerIteration(2000)
    })
}