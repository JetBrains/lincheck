package org.jetbrains.kotlinx.lincheck.execution

import org.jetbrains.kotlinx.lincheck.*
import kotlin.random.*

expect class ActorGenerator {
    override fun toString(): String
    val isSuspendable: Boolean
    val useOnce: Boolean
    fun generate(threadId: Int): Actor
}

internal val DETERMINISTIC_RANDOM = Random(42)