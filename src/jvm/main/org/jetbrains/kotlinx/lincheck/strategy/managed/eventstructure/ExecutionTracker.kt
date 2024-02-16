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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

/**
 * The execution tracker interface is used to track the execution's modifications.
 * It can be used to maintain additional data structures associated with the execution,
 * such as various events' indices or auxiliary relations on events.
 *
 * There are two types of modifications that can be tracked.

 * The first type of modifications is incremental changes, such as the addition of a new event.
 * The tracker can take advantage of the incremental nature of such changes to
 * optimize the update of its internal data structures.
 *
 * The second type of modifications is complete reset of the execution state
 * to some novel state, containing a different set of events.
 * In response to that, the tracker might need to re-compute its internal data structures.
 * Nevertheless, the tracker can utilize the fact that some subset of events might be preserved after reset,
 * and thus save some part of its internal data structures.
 *
 * @param E the type of events of the tracked execution.
 */
interface ExecutionTracker<E : ThreadEvent, X : Execution<E>> {

    /**
     * This method is called when a new event is added to the execution.
     *
     * @param event the newly added event.
     *
     * @see MutableExecution.add
     */
    fun onAdd(event: E)

    /**
     * This method is called when the execution is reset to a novel state.
     *
     * @param execution the execution representing the new set of events after reset.
     */
    fun onReset(execution: X)
}