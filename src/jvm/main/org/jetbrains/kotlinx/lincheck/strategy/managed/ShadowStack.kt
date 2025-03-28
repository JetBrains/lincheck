/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.isSuffixOf

internal class ShadowStack {

    private val _frames = mutableListOf<ShadowStackFrame>()

    private val _suspendedFrames = mutableListOf<ShadowStackFrame>()

    // Stores the currently executing methods call stack for each thread.
    val frames: List<ShadowStackFrame> get() = _frames

    // In case of suspension, the call stack of the corresponding `suspend`
    // methods is stored here, so that the same method call identifiers are
    // used on resumption, and the trace point before and after the suspension
    // correspond to the same method call in the trace.
    // NOTE: the call stack is stored in the reverse order,
    // i.e., the first element is the top stack trace element.
    val suspendedFrames: List<ShadowStackFrame> get() = _suspendedFrames

    fun pushFrame(frame: ShadowStackFrame) {
        _frames.add(frame)
    }

    fun popFrame() {
        _frames.removeLast()
    }

    fun suspendCurrentFrame() {
        val frame = _frames.removeLast()
        _suspendedFrames.add(frame)
    }

    // TODO; remove `actor` argument
    fun resumeFromFrame(frame: ShadowStackFrame, actor: Actor) {
        // find a stack frame corresponding to the resumed one
        var frameIndex = suspendedFrames.indexOfFirst {
            it.className == frame.className &&
            it.methodName == frame.methodName
            // it.receiver === frame.receiver
        }
        if (frameIndex == -1) {
            // this case is possible and can occur when we resume the coroutine,
            // and it results in a call to a top-level actor `suspend` function;
            // currently top-level actor functions are not represented in the `callStackTrace`,
            // we should probably refactor and fix that, because it is very inconvenient
            check(frame.className == actor.method.declaringClass.name)
            check(frame.methodName == actor.method.name)
            frameIndex = suspendedFrames.size
        }
        // get suspended stack trace elements to restore
        val resumedFrames = suspendedFrames
            .subList(frameIndex, suspendedFrames.size)
            .reversed()
        // we assume that all methods lying below the resumed one in stack trace
        // have empty resumption part or were already resumed before,
        // so we remove them from the suspended methods stack.
        _suspendedFrames.subList(0, frameIndex).clear()
        // we need to restore suspended stack trace elements
        // if they are not on the top of the current stack trace
        if (!resumedFrames.isSuffixOf(frames)) {
            // restore resumed stack trace elements
            _frames.addAll(resumedFrames)
        }
    }
}

/**
 * Represents a shadow stack frame used to reflect the program's stack in [ManagedStrategy].
 *
 * @property receiver the object on which the method was invoked, null in the case of a static method.
  */
internal class ShadowStackFrame(
    val className: String,
    val methodName: String,
    val receiver: Any?,
) {
    private val localVariables: MutableMap<String, LocalVariableState> = mutableMapOf()

    private var accessCounter: Int = 0

    private data class LocalVariableState(
        val value: Any?,
        val accessCounter: Int,
    )

    var tracePoint: MethodCallTracePoint? = null
        private set

    fun getLocalVariable(name: String): Any? {
        return localVariables[name]
    }

    fun setLocalVariable(name: String, value: Any?) {
        localVariables[name] = LocalVariableState(value, accessCounter++)
    }

    fun getLastAccessVariable(value: Any?): String? {
        return localVariables
            .filter { (_, state) -> state.value === value }
            .maxByOrNull { (_, state) -> state.accessCounter }
            ?.key
    }

    fun initiateTracePoint(tracePoint: MethodCallTracePoint) {
        check(this.tracePoint == null) {
            "Trace point already initiated"
        }
        this.tracePoint = tracePoint
    }
}