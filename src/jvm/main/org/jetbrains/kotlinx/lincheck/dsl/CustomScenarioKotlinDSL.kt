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
    fun <T> actor(f: KFunction1<T, *>, arg0: T) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor2Suspend")
    fun <T> actor(f: KSuspendFunction1<T, *>, arg0: T) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor3")
    fun <T, V> actor(f: KFunction2<T, V, *>, arg0: T, arg1: V) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor3Suspend")
    fun <T, V> actor(f: KSuspendFunction2<T, V, *>, arg0: T, arg1: V) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor4")
    fun <T, V, K> actor(f: KFunction3<T, V, K, *>, arg0: T, arg1: V, arg2: K) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor4Suspend")
    fun <T, V, K> actor(f: KSuspendFunction3<T, V, K, *>, arg0: T, arg1: V, arg2: K) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor5")
    fun <T, V, K, M> actor(f: KFunction4<T, V, K, M, *>, arg0: T, arg1: V, arg2: K, arg3: M) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor5Suspend")
    fun <T, V, K, M> actor(f: KSuspendFunction4<T, V, K, M, *>, arg0: T, arg1: V, arg2: K, arg3: M) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor6")
    fun <T, V, K, M, N> actor(f: KFunction5<T, V, K, M, N, *>, arg0: T, arg1: V, arg2: K, arg3: M, arg4: N) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor6Suspend")
    fun <T, V, K, M, N> actor(f: KSuspendFunction5<T, V, K, M, N, *>, arg0: T, arg1: V, arg2: K, arg3: M, arg4: N) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor7")
    fun <T, V, K, M, N, L> actor(f: KFunction6<T, V, K, M, N, L, *>, arg0: T, arg1: V, arg2: K, arg3: M, arg4: N, arg5: L) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4, arg5))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor7Suspend")
    fun <T, V, K, M, N, L> actor(f: KSuspendFunction6<T, V, K, M, N, L, *>, arg0: T, arg1: V, arg2: K, arg3: M, arg4: N, arg5: L) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4, arg5))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor8")
    fun <T, V, K, M, N, L, P> actor(f: KFunction7<T, V, K, M, N, L, P, *>, arg0: T, arg1: V, arg2: K, arg3: M, arg4: N, arg5: L, arg6: P) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4, arg5, arg6))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor8Suspend")
    fun <T, V, K, M, N, L, P> actor(f: KSuspendFunction7<T, V, K, M, N, L, P, *>, arg0: T, arg1: V, arg2: K, arg3: M, arg4: N, arg5: L, arg6: P) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4, arg5, arg6))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor9")
    fun <T, V, K, M, N, L, P, S> actor(f: KFunction8<T, V, K, M, N, L, P, S, *>, arg0: T, arg1: V, arg2: K, arg3: M, arg4: N, arg5: L, arg6: P, arg7: S) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor9Suspend")
    fun <T, V, K, M, N, L, P, S> actor(f: KSuspendFunction8<T, V, K, M, N, L, P, S, *>, arg0: T, arg1: V, arg2: K, arg3: M, arg4: N, arg5: L, arg6: P, arg7: S) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor10")
    fun <T, V, K, M, N, L, P, S, R> actor(f: KFunction9<T, V, K, M, N, L, P, S, R, *>, arg0: T, arg1: V, arg2: K, arg3: M, arg4: N, arg5: L, arg6: P, arg7: S, arg8: R) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8))
    }

    /**
     * An actor to be executed
     */
    @JvmName("actor10Suspend")
    fun <T, V, K, M, N, L, P, S, R> actor(f: KSuspendFunction9<T, V, K, M, N, L, P, S, R, *>, arg0: T, arg1: V, arg2: K, arg3: M, arg4: N, arg5: L, arg6: P, arg7: S, arg8: R) {
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
