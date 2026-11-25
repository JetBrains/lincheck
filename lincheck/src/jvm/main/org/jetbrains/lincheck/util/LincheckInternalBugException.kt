package org.jetbrains.lincheck.util

/**
 * Thrown in case when `cause` exception is unexpected by Lincheck internal logic.
 */
internal class LincheckInternalBugException(cause: Throwable): Exception(cause)