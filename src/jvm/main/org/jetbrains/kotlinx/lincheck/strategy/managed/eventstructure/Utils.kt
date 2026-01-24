/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.util.*

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