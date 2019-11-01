package org.jetbrains.kotlinx.lincheck.util

sealed class Either<out ErrorType, out ValueType> {
    data class Error<out ErrorType>(val error: ErrorType) : Either<ErrorType, Nothing>()
    data class Value<out ValueType>(val value: ValueType) : Either<Nothing, ValueType>()
}

fun <ValueType> Either<Any, ValueType>.valueOrNull(): ValueType? = if (this is Either.Value) value else null
fun <ErrorType> Either<ErrorType, Any>.errorOrNull(): ErrorType? = if (this is Either.Error) error else null
