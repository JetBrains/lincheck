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
package org.jetbrains.kotlinx.lincheck.verifier.quantitative

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.Result
import org.jetbrains.kotlinx.lincheck.verifier.LTS
import org.jetbrains.kotlinx.lincheck.verifier.RegularLTS
import org.jetbrains.kotlinx.lincheck.verifier.quantitative.ExtendedLTS.State
import java.lang.reflect.Method

/**
 * This LTS is based on the presented in the "Quantitative relaxation of concurrent data structures"
 * paper by Henzinger et al. Unlike their formalism, we use a mix of regular and extended labeled
 * transition systems. Usually, data structures have both relaxed and non-relaxed methods, which
 * should be verified differently. Essentially, we store both regular transitions ([State x Op x State],
 * where `Op` is a pair of [Actor] and [Result] in our terms) and relaxed transitions with the cost
 * ([State x Op_Ext x State], where `Op_Ext` is a tuple of [Actor], [Result], and cost ([Int]) in our terms).
 *
 * Like in [RegularLTS], there is a similar [State.nextRegular] method for non-relaxed transitions. However,
 * there is also [State.nextRelaxed] method which returns a list of next `CostCounter` instances with
 * the transition costs, which is represented via [CostWithNextCostCounter] class. In order to get next states,
 * a special [getStateForCostCounter] method should be used.
 */
class ExtendedLTS(private val costCounterClass: Class<*>, relaxationFactor: Int) : LTS<State> {
    // costCounter -> State
    private val states: MutableMap<Any, State> = HashMap()

    override val initialState = getStateForCostCounter(costCounterClass.getConstructor(Int::class.java).newInstance(relaxationFactor))

    inner class State(private val costCounter: Any) {
        // [actor, result] -> List<CostWithNextCostCounter> | nextCostCounter | NULL (for null)
        private val transitions: MutableMap<ActorWithResult, Any> = HashMap()


        fun nextRegular(actor: Actor, result: Result): State? {
            val next = next(actor, result) ?: return null
            check(next.javaClass == costCounterClass) {
                "Non-relaxed $actor should store transitions within CostCounter instances, but $next is found"
            }
            return getStateForCostCounter(next)
        }

        fun nextRelaxed(actor: Actor, result: Result): List<CostWithNextCostCounter<*>> {
            val next = next(actor, result)
            check(next is List<*>) {
                "Relaxed $actor should store transitions within a list of CostWithNextCostCounter, but $next is found"
            }
            return next as List<CostWithNextCostCounter<*>>
        }

        private fun next(actor: Actor, result: Result): Any? {
            val next = transitions.computeIfAbsent(ActorWithResult(actor, result)) {
                val actorRelatedMethod = getCostCounterMethod(actor.method)
                val params = actor.arguments + result
                actorRelatedMethod.invoke(costCounter, *params) ?: NULL
            }
            return if (next == NULL) null else next
        }
    }

    fun getStateForCostCounter(costCounter: Any) = states.computeIfAbsent(costCounter) { State(costCounter) }

    private data class ActorWithResult(val actor: Actor, val result: Result)

    // test class method -> cost counter method
    private val methodsMapping: MutableMap<Method, Method> = HashMap()

    private fun getCostCounterMethod(testClassMethod: Method): Method {
        return methodsMapping.computeIfAbsent(testClassMethod) {
            val params = testClassMethod.parameterTypes + Result::class.java
            costCounterClass.getMethod(testClassMethod.name, *params)
        }
    }
}

private val NULL = Any()
