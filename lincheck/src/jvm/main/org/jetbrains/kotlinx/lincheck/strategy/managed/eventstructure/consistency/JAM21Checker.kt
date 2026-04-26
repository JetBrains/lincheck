/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency

import org.jetbrains.kotlinx.lincheck.strategy.managed.MemoryLocation
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.AtomicMemoryAccessEventIndex
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.AtomicThreadEvent
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.Execution
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.MutableExtendedExecution
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.ThreadEvent
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.ThreadFinishLabel
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.ThreadForkLabel
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.ThreadJoinLabel
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.ThreadStartLabel
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.isAcquire
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.isRelease
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.isWrite
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.locations
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.readsFromOpt
import org.jetbrains.kotlinx.lincheck.util.ThreadId
import org.jetbrains.lincheck.util.Computable
import org.jetbrains.lincheck.util.Relation
import org.jetbrains.lincheck.util.RelationMatrix
import org.jetbrains.lincheck.util.toEnumerator

// TODO: Extremely primitive, there is a better way to do this. It makes the tests green for now, so that is good.
class JAM21Checker(
    val execution: Execution<AtomicThreadEvent>,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val programOrder: Relation<ThreadEvent>
) : ConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution> {

    class CycleIncosistency: Inconsistency() {}

    override fun check(execution: MutableExtendedExecution): Inconsistency? {

        val vo = VisibilityOrder(execution, memoryAccessEventIndex, programOrder)
        vo.compute()

        if(!vo.isIrreflexive) {
            return CycleIncosistency()
        } else {
            return null
        }
    }

}

class VisibilityOrder(
    val execution: Execution<AtomicThreadEvent>,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val programOrder: Relation<ThreadEvent>
): Computable {

    private val _events = events()
    var isIrreflexive: Boolean = false

    // We can also have synchronization on thread join events. That means that we need to have some sort of
    // unique way to identify which threads are getting joined.
    // The first thing I came up with is to use the threadID
    sealed class JamLocation: Comparable<JamLocation> {
        abstract val classNum: Int
        abstract val cmp: Int

        data class JamMemoryLocation(val memoryLocation: MemoryLocation): JamLocation() {
            override val classNum = 0
            override val cmp = memoryLocation.objID
        }
        data class JamThreadId(val threadId: ThreadId): JamLocation() {
            override val classNum = 1
            override val cmp = threadId
        }

        override fun compareTo(other: JamLocation): Int {
            return compareBy<JamLocation>( {it.classNum}, {it.cmp}).compare(this, other)
        }

    }

    // This event just uses this new custom memory location
    data class JamEvent(
        val location: JamLocation,
        val event: AtomicThreadEvent,
    ): Comparable<JamEvent> {
        override fun compareTo(other: JamEvent): Int {
            return compareBy<JamEvent>({it.event}, {it.location} ).compare(this, other)
        }
    }

    // As we said again, we not only care about read/write events but also about thread events
    // There is probably a better way to do this, and a better place to put this code, but it works
    private fun events(): Iterable<JamEvent> {
        val normalAccesses = memoryAccessEventIndex.locations.flatMap {
            memoryAccessEventIndex.getWrites(it).map{foo -> JamEvent(JamLocation.JamMemoryLocation(it), foo)} +
                    memoryAccessEventIndex.getReadResponses(it).map { foo -> JamEvent(JamLocation.JamMemoryLocation(it), foo) }
        }

        val threadOperations = execution.mapNotNull { event ->
            when  {
                (event.label) is ThreadForkLabel -> JamEvent(JamLocation.JamThreadId((event.label as ThreadForkLabel).forkThreadIds.first()), event)
                (event.label) is ThreadStartLabel && event.label.isResponse -> JamEvent(JamLocation.JamThreadId((event.label as ThreadStartLabel).threadId), event)
                (event.label) is ThreadFinishLabel  -> JamEvent(JamLocation.JamThreadId((event.label as ThreadFinishLabel).finishedThreadIds.first()), event)
                (event.label) is ThreadJoinLabel && event.label.isResponse && event.senders.size == 1 -> {
                    val threadFinishEvent = event.senders.first()
                    val threadId = threadFinishEvent.threadId
                    JamEvent(JamLocation.JamThreadId(threadId), event)
                }
                else -> null
            }
        }

        return normalAccesses + threadOperations
    }



    override fun compute() {
        // let rfso = rf  //and some other stuff
        // let rel = W & (RA | V)
        // let acq = R & (RA | V)
        // let ra = po;[rel] | [acq];po | rfso
        // let vo = ra+ | po-loc

        val rf = RelationMatrix(_events.toList(),_events.toEnumerator())
        for(event1 in rf.nodes) {
            for(event2 in rf.nodes) {
                rf[event1,event2] = false
                if(event2.event.readsFromOpt == event1.event && event1.location == event2.location) rf[event1, event2] = true
            }
        }
        val poloc = RelationMatrix(_events.toList(),_events.toEnumerator())
        for(event1 in poloc.nodes) {
            for(event2 in poloc.nodes) {
                poloc[event1,event2] = false
                if(programOrder(event1.event, event2.event) && event1.location == event2.location) poloc[event1, event2] = true
            }
        }

        val ra =  RelationMatrix(_events.toList(),_events.toEnumerator())
        for(event1 in ra.nodes) {
            for(event2 in ra.nodes) {
                ra[event1, event2] = false
                if(rf[event1, event2]) ra[event1, event2] = true
                if(programOrder(event1.event, event2.event) && event2.event.isRelease) ra[event1, event2] = true
                if(programOrder(event1.event, event2.event) && event1.event.isAcquire) ra[event1, event2] = true
            }
        }
        ra.transitiveClosure()
        val vo = ra.copy()
        for(event1 in vo.nodes) {
            for(event2 in vo.nodes) {
                if(poloc[event1, event2]) vo[event1, event2] = true
            }
        }

        // let coww = wwco(vo)
        // let cowr = wwco(vo;invrf)
        // let corw = wwco(vo;po-loc)
        // let corr = wwco(rf;po-loc;invrf)
        //TODO: VERY VERY SLOW AND BAD
        val invrf = rf.copy().also { it.transpose() }
        val voCinvrf = vo.compose(invrf)
        val voCpoloc = vo.compose(poloc)
        val rfCpolocCinvrf = rf.compose(poloc).compose(invrf)


        val co = RelationMatrix(_events.toList(),_events.toEnumerator())
        for(w1 in vo.nodes) {
            for(w2 in vo.nodes) {
                co[w1,w2] = false
                if(w1.event.isWrite && w2.event.isWrite && w1 != w2 && w1.location == w2.location) {
                    if(vo[w1, w2]) co[w1, w2] = true
                    if(voCinvrf[w1, w2]) co[w1, w2] = true
                    if(voCpoloc[w1, w2]) co[w1, w2] = true
                    if(rfCpolocCinvrf[w1, w2]) co[w1, w2] = true
                }
            }
        }

        co.transitiveClosure()
        for(w1 in vo.nodes) {
            for(w2 in vo.nodes) {
                if(co[w1, w2]) {
                    check(w1.event.isWrite)
                    check(w2.event.isWrite)
                }
            }
        }
        isIrreflexive = co.isIrreflexive()
    }

    override fun reset() {
        TODO("Not yet implemented")
    }

}
