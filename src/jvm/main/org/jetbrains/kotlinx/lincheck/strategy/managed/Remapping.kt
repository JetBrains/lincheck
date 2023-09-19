/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import java.util.*

/// TODO: make value class?
// TODO: remapping should work with OpaqueValue?
class Remapping {

    private val map = IdentityHashMap<Any, Any>()

    operator fun get(from: Any): Any? = map[from]

    operator fun set(from: Any?, to: Any?) {
        if (from === to) return
        check(from != null && to != null) {
            "Value ${opaqueString(from)} cannot be remapped to ${opaqueString(to)} because one of them is null but not the other!"
        }
        map.put(from, to).also { old -> check(old == null || old === to) {
            "Value ${opaqueString(from)} cannot be remapped to ${opaqueString(to)} because it is already mapped to ${opaqueString(old!!)}!"
        }}
    }

    fun reset() {
        map.clear()
    }

}

fun Remapping.resynchronize(event: ThreadEvent, algebra: SynchronizationAlgebra) {
    check(event is AbstractAtomicThreadEvent)
    var resyncedLabel = event.label
    // TODO: unify cases
    event.allocation?.also { alloc ->
        this[event.label.obj?.unwrap()] = alloc.label.obj?.unwrap()
    }
    event.source?.also { source ->
        check(event.label is WriteAccessLabel)
        val value = (event.label as WriteAccessLabel).writeValue?.unwrap()
        this[value] = source.label.obj?.unwrap()
    }
    if (event.label.isResponse) {
        resyncedLabel = event.resynchronize(algebra)
        val value = (event.label as? ReadAccessLabel)?.readValue?.unwrap()
        this[value] = (resyncedLabel as? ReadAccessLabel)?.readValue?.unwrap()
    }
    event.label.remap(this)
    event.label.replay(resyncedLabel)
}

fun Remapping.replay(event: ThreadEvent, label: EventLabel) {
    event.label.remap(this)
    event.label.replay(label)
}