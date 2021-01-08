package org.jetbrains.kotlinx.lincheck.execution

import kotlin.random.*

expect class ActorGenerator {
    override fun toString(): String
    val isSuspendable: Boolean
}

internal val DETERMINISTIC_RANDOM = Random(42)