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

import org.jetbrains.kotlinx.lincheck.transformation.CodeLocations

object ExecutionEvents {
    sealed class Event {
        abstract val shortName: String

        class StartParallelPart : Event() {
            override fun toString(): String = ""
            override val shortName = ""
        }
        class ThreadFinish(val threadId: Int) : Event() {
            override fun toString(): String = "Finish Thread-$threadId"
            override val shortName = "F$threadId"
        }
        class ThreadJoin(val currentThreadId: Int, val joiningThreadId: Int, val attempt: Int /* since joining happens in cycle */) : Event() {
            override fun toString(): String = "Thread-$currentThreadId joins Thread-$joiningThreadId"
            override val shortName = "J$currentThreadId-$joiningThreadId (attempt=$attempt)"
        }
        class LiveLock(val threadId: Int, val codeLocation: Int) : Event() {
            override fun toString(): String = "LiveLock detected on Thread-$threadId at: ${CodeLocations.stackTrace(codeLocation)}"
            override val shortName = "L$threadId"
        }
        class BeforeMonitorLock(val threadId: Int, val codeLocation: Int) : Event() {
            override fun toString(): String = "Before monitor lock by Thread-$threadId at: ${CodeLocations.stackTrace(codeLocation)}"
            override val shortName = "BML$threadId"
        }
        class MonitorLock(val threadId: Int, val attempt: Int /* since locking happens in cycle */) : Event() {
            override fun toString(): String = "Lock monitor in Thread-$threadId"
            override val shortName = "LM$threadId (attempt=$attempt)"
        }
        class ThreadPark(val threadId: Int, val codeLocation: Int, val attempt: Int /* since parking happens in cycle */) : Event() {
            override fun toString(): String = "Park Thread-$threadId at ${CodeLocations.stackTrace(codeLocation)}"
            override val shortName = "P$threadId (attempt=$attempt)"
        }
        class BeforeWait(val threadId: Int, val codeLocation: Int) : Event() {
            override fun toString(): String = "Before wait by Thread-$threadId at ${CodeLocations.stackTrace(codeLocation)}"
            override val shortName = "BW$threadId"
        }
        class ThreadWait(val threadId: Int, val attempt: Int /* since waiting happens in cycle */) : Event() {
            override fun toString(): String = "Thread-$threadId waits on monitor"
            override val shortName = "W$threadId (attempt=$attempt)"
        }
        class BeforeRead(val threadId: Int, val codeLocation: Int, val className: String, val fieldName: String) : Event() {
            override fun toString(): String = "Before read field (field=$className::$fieldName) by Thread-$threadId at ${CodeLocations.stackTrace(codeLocation)}"
            override val shortName = "Re$threadId"
        }
        class BeforeReadArrayElement(val threadId: Int, val codeLocation: Int, val array: Any, val index: Int) : Event() {
            override fun toString(): String = "Before read array element (arr=${array.javaClass.simpleName}, index=$index) by Thread-$threadId at ${CodeLocations.stackTrace(codeLocation)}"
            override val shortName = "ReA$threadId"
        }
        class BeforeWrite(val threadId: Int, val codeLocation: Int, val className: String, val fieldName: String) : Event() {
            override fun toString(): String = "Before write field (field=$className::$fieldName) by Thread-$threadId at ${CodeLocations.stackTrace(codeLocation)}"
            override val shortName = "Wr$threadId"
        }
        class BeforeWriteArrayElement(val threadId: Int, val codeLocation: Int, val array: Any, val index: Int) : Event() {
            override fun toString(): String = "Before write array element (arr=${array.javaClass.simpleName}, index=$index) by Thread-$threadId at ${CodeLocations.stackTrace(codeLocation)}"
            override val shortName = "WrA$threadId"
        }
        class BeforeMethodCall(val threadId: Int, val codeLocation: Int, val className: String, val methodName: String) : Event() {
            override fun toString(): String = "Before method call $className::$methodName by Thread-$threadId at ${CodeLocations.stackTrace(codeLocation)}"
            override val shortName = "MC$threadId"
        }
        class AfterCoroutineSuspended(val threadId: Int) : Event() {
            override fun toString(): String = "After coroutine suspended in Thread-$threadId"
            override val shortName = "CS$threadId"
        }
    }
}