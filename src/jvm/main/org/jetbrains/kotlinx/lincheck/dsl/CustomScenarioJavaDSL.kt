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

package org.jetbrains.kotlinx.lincheck.dsl

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.actor
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario

/**
 * Creates a custom scenario using the specified DSL as in the following example:
 *
 * ```
 *   .addCustomScenario(
 *           new ScenarioBuilder(this.getClass())
 *                   .parallel(
 *                           thread(
 *                                   actor("inc", 0)
 *                           ),
 *                           thread(
 *                                   actor("inc", 0)
 *                           )
 *                   )
 *                   .post(
 *                           actor("get", 0)
 *                   )
 *                   .build()
 *   )
 * ```
 */
class ScenarioBuilder(private val testStructureClass: Class<*>) {

    private var initPart: List<Actor>? = null
    private var parallelPart: List<List<Actor>>? = null
    private var postPart: List<Actor>? = null

    /**
     * Specifies the initial part of the scenario.
     */
    fun initial(vararg actorsInfos: ActorInfo): ScenarioBuilder {
        check(initPart == null) { "Redeclaration of the initial part is prohibited." }
        initPart = actorsInfos.map { createActor(it) }

        return this
    }

    /**
     * Specifies the parallel part of the scenario.
     */
    fun parallel(vararg threadInfos: ThreadInfo): ScenarioBuilder {
        check(parallelPart == null) { "Redeclaration of the parallel part is prohibited." }
        parallelPart = threadInfos.map { threadInfo -> threadInfo.actorsInfos.map { createActor(it) } }

        return this
    }

    /**
     * Specifies the post part of the scenario.
     */
    fun post(vararg actorsInfos: ActorInfo): ScenarioBuilder {
        check(postPart == null) {  "Redeclaration of the post part is prohibited." }
        postPart = actorsInfos.map { createActor(it) }

        return this
    }

    /**
     * Constructs a new [scenario][ExecutionScenario] according to
     * the specified [initial], [parallel], and [post] parts.
     */
    fun build() = ExecutionScenario(
        /* initExecution = */ initPart ?: emptyList(),
        /* parallelExecution = */ parallelPart ?: emptyList(),
        /* postExecution = */ postPart ?: emptyList()
    )

    private fun createActor(actorInfo: ActorInfo): Actor {
        val parameterCount = actorInfo.arguments.size
        val methods = testStructureClass.methods.filter {
            it.name == actorInfo.methodName && it.parameterCount == parameterCount
        }

        require(methods.isNotEmpty()) { "Method with name ${actorInfo.methodName} and parameterCount $parameterCount not found" }
        require(methods.size == 1) {
            """
            More than one method with name ${actorInfo.methodName} and parameter count $parameterCount found in $testStructureClass.
            Having two or more methods with same name and parameter count is prohibited. Please create wrapper or rename method.
            """.trimIndent()
        }
        val method = methods.first()
        val arguments = actorInfo.arguments

        return actor(method, arguments)
    }

    companion object {

        /**
         * An actor to be executed.
         */
        @JvmStatic
        fun actor(methodName: String, vararg arguments: Any?) = ActorInfo(methodName, arguments.toList())

        /**
         * Thread's parallel part in custom scenario
         */
        @JvmStatic
        fun thread(vararg actorsInfos: ActorInfo) = ThreadInfo(actorsInfos.toList())
    }

    data class ActorInfo(val methodName: String, val arguments: List<Any?>)
    data class ThreadInfo(val actorsInfos: List<ActorInfo>)

}