/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.lincheck.util.ensureNull

fun buildEnumerator(events: List<AtomicThreadEvent>) = object : Enumerator<AtomicThreadEvent> {

    // TODO: perhaps we can maintain event numbers directly in events themself
    //   and update them during replay?
    val index: Map<AtomicThreadEvent, Int> =
        mutableMapOf<AtomicThreadEvent, Int>().apply {
            events.forEachIndexed { i, event ->
                put(event, i).ensureNull()
            }
        }

    override fun get(i: Int): AtomicThreadEvent = events[i]

    override fun get(x: AtomicThreadEvent): Int = index[x]!!

}