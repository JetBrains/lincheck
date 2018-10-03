/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

package com.devexperts.dxlab.lincheck.verifier

import com.devexperts.dxlab.lincheck.Actor
import com.devexperts.dxlab.lincheck.Result
import com.devexperts.dxlab.lincheck.Utils
import java.util.*


/**
 * The standard regular LTS is defined as [State x Op x State],
 * where `Op` is a pair of [Actor] and [Result] in our terms.
 * In order not to construct the full LTS (which is impossible
 * because it can be either infinite or just too big to build),
 * we construct it lazily during the requests and reuse it between runs.
 *
 * Taking into account that every transition from the specified element
 * by the specified actor determines the possible result and
 * the next state uniquely, we internally represent the regular LTS as
 * [State x Actor x ResultWithNextState], which reduces the number of
 * transitions to be stored.
 *
 * In order to perform sequential executions, [seqImplClass] should be
 * provided and has an empty constructor which creates the initial state
 * of the testing data structure.
 */
class RegularLTS(val seqImplClass: Class<*>) : LTS<RegularLTS.State> {
    override val initialState = State(emptyArray())
    // seqImpl -> State
    private val states: MutableMap<Any, State> = mutableMapOf()

    inner class State(private val actorsToCreate: Array<Actor>) {
        private val transitions: MutableMap<Actor, ResultWithNextState> = HashMap()

        fun next(actor: Actor, result: Result): State? {
            val resultWithNextState = transitions.computeIfAbsent(actor) {
                val newSeqImpl = copySeqImpl()
                val res = Utils.executeActor(newSeqImpl, actor)
                val actors = actorsToCreate + actor
                ResultWithNextState(res, getOrCreateState(newSeqImpl, actors))
            }
            return if (resultWithNextState.result == result) resultWithNextState.state else null
        }

        private fun copySeqImpl(): Any {
            val newSeqImpl = seqImplClass.newInstance()
            actorsToCreate.forEach { actor -> Utils.executeActor(newSeqImpl, actor) }
            return newSeqImpl
        }
    }

    private fun getOrCreateState(seqImpl: Any, actorsToCreate: Array<Actor>): State {
        return states.computeIfAbsent(seqImpl) { State(actorsToCreate) }
    }

    private class ResultWithNextState(val result: Result, val state: State)
}
