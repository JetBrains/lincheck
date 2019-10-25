package org.jetbrains.kotlinx.lincheck.util

sealed class Either<out ErrorType, out ValueType> {
    data class Error<out ErrorType>(val error: ErrorType) : Either<ErrorType, Nothing>()
    data class Value<out ValueType>(val value: ValueType) : Either<Nothing, ValueType>()
}
