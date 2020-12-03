/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.test

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.*

class IncorrectOnCancellationTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    @Volatile
    var canEnterForbiddenSection = false

    @InternalCoroutinesApi
    @Operation(cancellableOnSuspension = true)
    suspend fun cancelledOp(): Int {
        if (canEnterForbiddenSection)
            return 42
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation {
                canEnterForbiddenSection = true
                canEnterForbiddenSection = false
            }
        }
        return 0
    }

    override fun extractState(): Any = 0 // constant state
}