/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.trace

    
/**
 * Remove `access$get` and `access$set`, which is used when a lambda argument accesses a private field for example.
 * This is different from `fun$access`, which is addressed in [compressCallStackTrace].
 */
internal fun Trace.removeSyntheticFieldAccessTracePoints(): Trace = apply {
    trace
        .filter { it is ReadTracePoint || it is WriteTracePoint }
        .forEach { point ->
            val lastCall = point.callStackTrace.lastOrNull() ?: return@forEach
            if (isSyntheticFieldAccess(lastCall.tracePoint.methodName)) {
                if (point is ReadTracePoint) point.codeLocation = lastCall.tracePoint.codeLocation
                if (point is WriteTracePoint) point.codeLocation = lastCall.tracePoint.codeLocation
                point.callStackTrace = point.callStackTrace.dropLast(1)
            }
        }
}



/**
 * When `thread() { ... }` is called it is represented as
 * ```
 * thread creation line: Thread#2 at A.fun(location)
 *     Thread#2.start()
 * ```
 * this function gets rid of the second line.
 * But only if it has been created with `thread(start = true)`
 */
internal fun Trace.removeNestedThreadStartPoints(): Trace = apply { 
    trace
        .filter { it is ThreadStartTracePoint }
        .forEach { tracePoint ->
            val threadCreationCall = tracePoint.callStackTrace.dropLast(1).lastOrNull()
            if(threadCreationCall?.tracePoint?.isThreadCreation() == true) {
                tracePoint.callStackTrace = tracePoint.callStackTrace.dropLast(1)
            }
        }
}

internal fun Trace.compressTrace(): Trace = apply {
    HashSet<Int>().let { removed ->
        trace.apply { forEach { it.callStackTrace = compressCallStackTrace(it.callStackTrace, removed) } }
    }
}

/**
 * Merges two consecutive calls in the stack trace into one call if they form a compressible pair,
 * see [isCompressiblePair] for details.
 *
 * Since each tracePoint itself contains a [callStackTrace] of its own,
 * we need to recursively traverse each point.
 * 
 * (This can probably be done simpler..)
 */
private fun compressCallStackTrace(
    callStackTrace: List<CallStackTraceElement>,
    removed: HashSet<Int>,
    seen: HashSet<Int> = HashSet(),
): List<CallStackTraceElement> {
    val oldStacktrace = callStackTrace.toMutableList()
    val compressedStackTrace = mutableListOf<CallStackTraceElement>()
    while (oldStacktrace.isNotEmpty()) {
        val currentElement = oldStacktrace.removeFirst()

        // if element was removed (or seen) by previous iteration continue
        if (removed.contains(currentElement.id)) continue
        if (seen.contains(currentElement.id)) {
            compressedStackTrace.add(currentElement)
            continue
        }
        seen.add(currentElement.id)

        // if next element is null, we reached end of list
        val nextElement = oldStacktrace.firstOrNull()
        if (nextElement == null) {
            currentElement.tracePoint.callStackTrace =
                compressCallStackTrace(currentElement.tracePoint.callStackTrace, removed, seen)
            compressedStackTrace.add(currentElement)
            break
        }

        // Check if current and next are custom thread start
        if (isUserThreadStart(currentElement, nextElement)) {
            // we do not mark currentElement as removed, since that is a unique call from Thread.kt
            // marking it prevents starts of other threads from being detected.
            removed.add(nextElement.id)
            continue
        }

        // Check if current and next are compressible
        if (isCompressiblePair(currentElement.tracePoint.methodName, nextElement.tracePoint.methodName)) {
            // Combine fields of next and current, and store in current
            currentElement.tracePoint.methodName = nextElement.tracePoint.methodName
            currentElement.tracePoint.parameters = nextElement.tracePoint.parameters
            currentElement.tracePoint.callStackTrace =
                compressCallStackTrace(currentElement.tracePoint.callStackTrace, removed, seen)

            check(currentElement.tracePoint.returnedValue == nextElement.tracePoint.returnedValue)
            check(currentElement.tracePoint.thrownException == nextElement.tracePoint.thrownException)

            // Mark next as removed
            removed.add(nextElement.id)
            compressedStackTrace.add(currentElement)
            continue
        }
        currentElement.tracePoint.callStackTrace =
            compressCallStackTrace(currentElement.tracePoint.callStackTrace, removed, seen)
        compressedStackTrace.add(currentElement)
    }
    return compressedStackTrace
}

private fun isSyntheticFieldAccess(methodName: String): Boolean =
    methodName.contains("access\$get") || methodName.contains("access\$set")

private fun isCompressiblePair(currentName: String, nextName: String): Boolean =
    isDefaultPair(currentName, nextName) || isAccessPair(currentName, nextName)

/**
 * Used by [compressCallStackTrace] to merge `fun$default(...)` calls.
 *
 * Kotlin functions with default values are represented as two nested calls in the stack trace.
 *
 * For example:
 *
 * ```
 * A.calLMe$default(A#1, 3, null, 2, null) at A.operation(A.kt:23)
 *   A.callMe(3, "Hey") at A.callMe$default(A.kt:27)
 * ```
 *
 * will be collapsed into:
 *
 * ```
 * A.callMe(3, "Hey") at A.operation(A.kt:23)
 * ```
 *
 */
private fun isDefaultPair(currentName: String, nextName: String): Boolean =
    currentName == "${nextName}\$default"

/**
 * Used by [compressCallStackTrace] to merge `.access$` calls.
 *
 * The `.access$` methods are generated by the Kotlin compiler to access otherwise inaccessible members
 * (e.g., private) from lambdas, inner classes, etc.
 *
 * For example:
 *
 * ```
 * A.access$callMe() at A.operation(A.kt:N)
 *  A.callMe() at A.access$callMe(A.kt:N)
 * ```
 *
 * will be collapsed into:
 *
 * ```
 * A.callMe() at A.operation(A.kt:N)
 * ```
 *
 */
private fun isAccessPair(currentName: String, nextName: String): Boolean =
    currentName == "access$${nextName}"

/**
 * Used by [compressCallStackTrace] to remove the two `invoke()` lines at the beginning of
 * a user-defined thread trace.
 */
private fun isUserThreadStart(currentElement: CallStackTraceElement, nextElement: CallStackTraceElement): Boolean =
    currentElement.tracePoint.stackTraceElement.methodName == "run"
            && currentElement.tracePoint.stackTraceElement.fileName == "Thread.kt"
            && currentElement.tracePoint.methodName == "invoke"
            && nextElement.tracePoint.methodName == "invoke"