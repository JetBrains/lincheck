package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.*
import kotlin.reflect.*

/**
 * Abstract configuration for different lincheck modes.
 */
expect abstract class CTestConfiguration {
    val iterations: Int
    val threads: Int
    val actorsPerThread: Int
    val actorsBefore: Int
    val actorsAfter: Int
    val generatorClass: KClass<out ExecutionGenerator>
}