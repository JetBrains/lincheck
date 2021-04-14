package org.jetbrains.kotlinx.lincheck.execution

import org.jetbrains.kotlinx.lincheck.*
import kotlin.random.*
import kotlin.reflect.KClass

expect class ActorGenerator {
    override fun toString(): String
    val isSuspendable: Boolean
    val useOnce: Boolean
    val handledExceptions: List<KClass<out Throwable>>

    fun generate(threadId: Int): Actor
}

internal val DETERMINISTIC_RANDOM = Random(42)