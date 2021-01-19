package org.jetbrains.kotlinx.lincheck

/**
 * Type of result used if the actor invocation returns any value.
 */
expect class ValueResult {
    override fun equals(other: Any?): Boolean
    override fun hashCode(): Int
    val value: Any?
}