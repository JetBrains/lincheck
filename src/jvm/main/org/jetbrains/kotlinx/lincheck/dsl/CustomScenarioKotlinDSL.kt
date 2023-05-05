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

@file:Suppress("PackageDirectoryMismatch")
// Package directive does not match the file location for backward compatibility
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import kotlin.reflect.*
import kotlin.reflect.jvm.javaMethod

/**
 * Creates a custom scenario using the specified DSL as in the following example:
 *
 * ```
 * scenario {
 *   initial {
 *     actor(QueueTest::offer, 1)
 *     actor(QueueTest::offer, 2)
 *   }
 *   parallel {
 *     thread {
 *       actor(QueueTest::poll)
 *     }
 *     thread {
 *       actor(QueueTest::poll)
 *     }
 *   }
 * }
 * ```
 */
fun scenario(block: DSLScenarioBuilder.() -> Unit): ExecutionScenario =
    DSLScenarioBuilder().apply(block).buildScenario()

/**
 * Create an actor with the specified [function][f] and [arguments][args].
 */
internal fun actor(f: KFunction<*>, vararg args: Any?, cancelOnSuspension: Boolean = false): Actor {
    val method = f.javaMethod ?: throw IllegalStateException("The function is a constructor or cannot be represented by a Java Method")
    require(method.exceptionTypes.all { Throwable::class.java.isAssignableFrom(it) }) { "Not all declared exceptions are Throwable" }

    return actor(method, args.toList())
}

@ScenarioDSLMarker
class DSLThreadScenario : ArrayList<Actor>() {

    /**
     * An actor to be executed
     */
    @JvmName("actor1")
    fun actor(f: KFunction0<*>) {
        add(org.jetbrains.kotlinx.lincheck.actor(f))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor1Suspend")
    fun actor(f: KSuspendFunction0<*>) {
        add(org.jetbrains.kotlinx.lincheck.actor(f))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor2")
    fun actor(f: KFunction1<*, *>, arg0: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor2Suspend")
    fun actor(f: KSuspendFunction1<*, *>, arg0: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor3")
    fun actor(f: KFunction2<*, *, *>, arg0: Any?, arg1: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor3Suspend")
    fun actor(f: KSuspendFunction2<*, *, *>, arg0: Any?, arg1: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor4")
    fun actor(f: KFunction3<*, *, *, *>, arg0: Any?, arg1: Any?, arg2: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor4Suspend")
    fun actor(f: KSuspendFunction3<*, *, *, *>, arg0: Any?, arg1: Any?, arg2: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor5")
    fun actor(f: KFunction4<*, *, *, *, *>, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor5Suspend")
    fun actor(f: KSuspendFunction4<*, *, *, *, *>, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor6")
    fun actor(f: KFunction5<*, *, *, *, *, *>, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor6Suspend")
    fun actor(f: KSuspendFunction5<*, *, *, *, *, *>, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor7")
    fun actor(f: KFunction6<*, *, *, *, *, *, *>, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?, arg5: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4, arg5))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor7Suspend")
    fun actor(f: KSuspendFunction6<*, *, *, *, *, *, *>, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?, arg5: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4, arg5))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor8")
    fun actor(f: KFunction7<*, *, *, *, *, *, *, *>, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?, arg5: Any?, arg6: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4, arg5, arg6))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor8Suspend")
    fun actor(f: KSuspendFunction7<*, *, *, *, *, *, *, *>, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?, arg5: Any?, arg6: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4, arg5, arg6))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor9")
    fun actor(f: KFunction8<*, *, *, *, *, *, *, *, *>, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?, arg5: Any?, arg6: Any?, arg7: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor9Suspend")
    fun actor(f: KSuspendFunction8<*, *, *, *, *, *, *, *, *>, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?, arg5: Any?, arg6: Any?, arg7: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor10")
    fun actor(f: KFunction9<*, *, *, *, *, *, *, *, *, *>, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?, arg5: Any?, arg6: Any?, arg7: Any?, arg8: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor10Suspend")
    fun actor(f: KSuspendFunction9<*, *, *, *, *, *, *, *, *, *>, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?, arg5: Any?, arg6: Any?, arg7: Any?, arg8: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8))
    }

}

@ScenarioDSLMarker
class DSLParallelScenario : ArrayList<DSLThreadScenario>() {
    /**
     * Define a sequence of actors to be executed in a separate thread
     */
    fun thread(block: DSLThreadScenario.() -> Unit) {
        add(DSLThreadScenario().apply(block))
    }
}

@ScenarioDSLMarker
class DSLScenarioBuilder {
    private val initial = mutableListOf<Actor>()
    private var parallel = mutableListOf<MutableList<Actor>>()
    private val post = mutableListOf<Actor>()
    private var initialSpecified = false
    private var parallelSpecified = false
    private var postSpecified = false

    /**
     * Specifies the initial part of the scenario.
     */
    fun initial(block: DSLThreadScenario.() -> Unit) {
        require(!initialSpecified) { "Redeclaration of the initial part is prohibited." }
        initialSpecified = true
        initial.addAll(DSLThreadScenario().apply(block))
    }

    /**
     * Specifies the parallel part of the scenario.
     */
    fun parallel(block: DSLParallelScenario.() -> Unit) {
        require(!parallelSpecified) { "Redeclaration of the parallel part is prohibited." }
        parallelSpecified = true
        parallel.addAll(DSLParallelScenario().apply(block))
    }

    /**
     * Specifies the post part of the scenario.
     */
    fun post(block: DSLThreadScenario.() -> Unit) {
        require(!postSpecified) { "Redeclaration of the post part is prohibited." }
        postSpecified = true
        post.addAll(DSLThreadScenario().apply(block))
    }

    /**
     * Constructs a new [scenario][ExecutionScenario] according to
     * the specified [initial], [parallel], and [post] parts.
     */
    fun buildScenario() = ExecutionScenario(initial, parallel, post)
}

@DslMarker
private annotation class ScenarioDSLMarker
