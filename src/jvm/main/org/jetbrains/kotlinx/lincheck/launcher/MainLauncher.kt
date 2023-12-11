/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.launcher

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking {
    val channel = Channel<Int>(2)
    channel.trySend(3)
    channel.trySend(3)
    channel.trySend(3)
    channel.send(3)
    println("Here!")
}
