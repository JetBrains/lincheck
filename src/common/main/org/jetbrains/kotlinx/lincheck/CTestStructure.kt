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

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.*

expect class ValidationFunction

expect val ValidationFunction.name: String

expect class StateRepresentationFunction

/**
 * Contains information about the provided operations (see [Operation]).
 * Several [tests][StressCTest] can refer to one structure
 * (i.e. one test class could have several [StressCTest] annotations)
 */
expect class CTestStructure {
    val actorGenerators: List<ActorGenerator>
    val operationGroups: List<OperationGroup>
    val validationFunctions: List<ValidationFunction>
    val stateRepresentation: StateRepresentationFunction?
}

class OperationGroup(val name: String, val nonParallel: Boolean) {
    val actors: MutableList<ActorGenerator>
    override fun toString(): String {
        return "OperationGroup{" +
            "name='" + name + '\'' +
            ", nonParallel=" + nonParallel +
            ", actors=" + actors +
            '}'
    }

    init {
        actors = ArrayList()
    }
}