/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
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