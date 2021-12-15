/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.distributed

import kotlinx.coroutines.*
import kotlinx.coroutines.internal.*
import kotlinx.coroutines.internal.resumeCancellableWith
import kotlinx.coroutines.intrinsics.*
import java.lang.Runnable
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.intercepted

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
internal fun <U, T : U> setupTimeout(
    coroutine: TimeoutCoroutine<U, T>,
    block: suspend CoroutineScope.() -> T,
    handle: DisposableHandle
): Any? {
    coroutine.disposeOnCompletion(handle)
    // restart the block using a new coroutine with a new job,
    // however, start it undispatched, because we already are in the proper context
    return coroutine.startUndispatchedOrReturnIgnoreTimeout(coroutine, block)
}

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
internal class TimeoutCoroutine<U, in T : U>(
    @JvmField val time: Int,
    uCont: Continuation<U> // unintercepted continuation
) : ScopeCoroutine<T>(uCont.context, uCont), Runnable {
    override fun run() {
        cancelCoroutine(TimeoutCancellationException(time.toLong(), this))
    }
}

