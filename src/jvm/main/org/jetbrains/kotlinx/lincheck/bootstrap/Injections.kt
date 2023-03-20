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

package sun.nio.ch.lincheck

import java.lang.invoke.VarHandle
import java.util.*

internal object Injections {
    // TODO: make this part more robust
    @JvmStatic
    var storedLastCancellableCont: Any? = null

    @JvmStatic
    fun storeCancellableContinuation(cont: Any) {
        val t = Thread.currentThread()
        if (t is TestThread) {
            t.cont = cont
        } else {
            storedLastCancellableCont = cont
        }
    }

    @JvmStatic
    fun deterministicHashCode(obj: Any): Int {
        val hashCode = obj.hashCode() // TODO: this is a dirty hack
        return if (hashCode == System.identityHashCode(obj)) 0 else hashCode
    }

    @JvmStatic
    fun enterIgnoredSection() {
        val t = Thread.currentThread()
        if (t !is TestThread) return
        t.ignoredSectionDepth++
    }

    @JvmStatic
    fun leaveIgnoredSection() {
        val t = Thread.currentThread()
        if (t !is TestThread) return
        t.ignoredSectionDepth--
    }

    @JvmStatic
    fun inTestingCode(): Boolean {
        val t = Thread.currentThread()
        if (t !is TestThread) return false
        return t.inTestingCode && t.ignoredSectionDepth == 0
    }

    @JvmStatic
    fun lock(monitor: Any, codeLocation: Int) {
        sharedEventsTracker.lock(monitor, codeLocation)
    }

    @JvmStatic
    fun unlock(monitor: Any, codeLocation: Int) {
        sharedEventsTracker.unlock(monitor, codeLocation)
    }

    @JvmStatic
    fun park(codeLocation: Int) {
        sharedEventsTracker.park(codeLocation)
    }

    @JvmStatic
    fun unpark(thread: Thread, codeLocation: Int) {
        sharedEventsTracker.unpark(thread, codeLocation)
    }

    @JvmStatic
    fun wait(monitor: Any, codeLocation: Int) {
        sharedEventsTracker.wait(monitor, codeLocation, withTimeout = false)
    }

    @JvmStatic
    fun waitWithTimeout(monitor: Any, codeLocation: Int) {
        sharedEventsTracker.wait(monitor, codeLocation, withTimeout = true)
    }

    @JvmStatic
    fun notify(monitor: Any, codeLocation: Int) {
        sharedEventsTracker.notify(monitor, codeLocation, notifyAll = false)
    }

    @JvmStatic
    fun notifyAll(monitor: Any, codeLocation: Int) {
        sharedEventsTracker.notify(monitor, codeLocation, notifyAll = true)
    }

    @JvmStatic
    fun nextInt(): Int {
        return deterministicRandom().nextInt()
    }

    @JvmStatic
    fun deterministicRandom(): Random {
        return sharedEventsTracker.getRandom(currentThreadId)
    }

    @JvmStatic
    fun isRandom(any: Any?): Boolean {
        return any is Random
    }

    @JvmStatic
    fun beforeReadField(obj: Any?, className: String, fieldName: String, codeLocation: Int) {
        if (obj == null) return // Ignore, NullPointerException will be thrown
        sharedEventsTracker.beforeReadField(obj, className, fieldName, codeLocation)
    }

    @JvmStatic
    fun beforeReadFieldStatic(className: String, fieldName: String, codeLocation: Int) {
        sharedEventsTracker.beforeReadFieldStatic(className, fieldName, codeLocation)
    }

    @JvmStatic
    fun beforeReadArray(array: Any?, index: Int, codeLocation: Int) {
        if (array == null) return // Ignore, NullPointerException will be thrown
        sharedEventsTracker.beforeReadArrayElement(array, index, codeLocation)
    }

    @JvmStatic
    fun onReadValue(value: Any?) {
        sharedEventsTracker.onReadValue(value)
    }

    @JvmStatic
    fun beforeWriteField(obj: Any?, className: String, fieldName: String, value: Any?, codeLocation: Int) {
        if (obj == null) return // Ignore, NullPointerException will be thrown
        sharedEventsTracker.beforeWriteField(obj, className, fieldName, value, codeLocation)
    }

    @JvmStatic
    fun beforeWriteFieldStatic(className: String, fieldName: String, value: Any?, codeLocation: Int) {
        sharedEventsTracker.beforeWriteFieldStatic(className, fieldName, value, codeLocation)
    }

    @JvmStatic
    fun beforeWriteArray(array: Any?, index: Int, value: Any?, codeLocation: Int) {
        if (array == null) return // Ignore, NullPointerException will be thrown
        sharedEventsTracker.beforeWriteArrayElement(array, index, value, codeLocation)
    }

    // owner == null for static methods
    @JvmStatic
    fun beforeMethodCall0(owner: Any?, className: String, methodName: String, codeLocation: Int) {
        sharedEventsTracker.beforeMethodCall0(owner, className, methodName, codeLocation)
        if (owner is VarHandle) {
            val t = Thread.currentThread() as TestThread
            t.ignoredSectionDepth++
            t.enteredIgnoredSectionByInjector = true
        }
    }

    @JvmStatic
    fun beforeMethodCall1(owner: Any?, className: String, methodName: String, codeLocation: Int, param1: Any?) {
        sharedEventsTracker.beforeMethodCall1(owner, className, methodName, codeLocation, param1)
        if (owner is VarHandle) {
            val t = Thread.currentThread() as TestThread
            t.ignoredSectionDepth++
            t.enteredIgnoredSectionByInjector = true
        }
    }

    @JvmStatic
    fun beforeMethodCall2(owner: Any?, className: String, methodName: String, codeLocation: Int, param1: Any?, param2: Any?) {
        sharedEventsTracker.beforeMethodCall2(owner, className, methodName, codeLocation, param1, param2)
        if (owner is VarHandle) {
            val t = Thread.currentThread() as TestThread
            t.ignoredSectionDepth++
            t.enteredIgnoredSectionByInjector = true
        }
    }

    @JvmStatic
    fun beforeMethodCall3(owner: Any?, className: String, methodName: String, codeLocation: Int, param1: Any?, param2: Any?, param3: Any?) {
        sharedEventsTracker.beforeMethodCall3(owner, className, methodName, codeLocation, param1, param2, param3)
        if (owner is VarHandle) {
            val t = Thread.currentThread() as TestThread
            t.ignoredSectionDepth++
            t.enteredIgnoredSectionByInjector = true
        }
    }

    @JvmStatic
    fun beforeMethodCall4(owner: Any?, className: String, methodName: String, codeLocation: Int, param1: Any?, param2: Any?, param3: Any?, param4: Any?) {
        sharedEventsTracker.beforeMethodCall4(owner, className, methodName, codeLocation, param1, param2, param3, param4)
        if (owner is VarHandle) {
            val t = Thread.currentThread() as TestThread
            t.ignoredSectionDepth++
            t.enteredIgnoredSectionByInjector = true
        }
    }

    @JvmStatic
    fun beforeMethodCall5(owner: Any?, className: String, methodName: String, codeLocation: Int, param1: Any?, param2: Any?, param3: Any?, param4: Any?, param5: Any?) {
        sharedEventsTracker.beforeMethodCall5(owner, className, methodName, codeLocation, param1, param2, param3, param4, param5)
        if (owner is VarHandle) {
            val t = Thread.currentThread() as TestThread
            t.ignoredSectionDepth++
            t.enteredIgnoredSectionByInjector = true
        }
    }

    @JvmStatic
    fun beforeMethodCall(owner: Any?, className: String, methodName: String, codeLocation: Int, params: Array<Any?>) {
        sharedEventsTracker.beforeMethodCall(owner, className, methodName, codeLocation, params)
        if (owner is VarHandle) {
            val t = Thread.currentThread() as TestThread
            t.ignoredSectionDepth++
            t.enteredIgnoredSectionByInjector = true
        }
    }

    @JvmStatic
    fun onMethodCallFinishedSuccessfully(result: Any?) {
        val currentThread = Thread.currentThread() as TestThread
        if (currentThread.enteredIgnoredSectionByInjector) {
            currentThread.ignoredSectionDepth--
            currentThread.enteredIgnoredSectionByInjector = false
        }
        sharedEventsTracker.onMethodCallFinishedSuccessfully(result)
    }

    @JvmStatic
    fun onMethodCallThrewException(t: Throwable) {
        val currentThread = Thread.currentThread() as TestThread
        if (currentThread.enteredIgnoredSectionByInjector) {
            currentThread.ignoredSectionDepth--
            currentThread.enteredIgnoredSectionByInjector = false
        }
        sharedEventsTracker.onMethodCallThrewException(t)
    }

    @JvmStatic
    fun onNewAtomicFieldUpdater(updater: Any?, name: String) {
        AtomicFieldNameMapper.newAtomic(updater!!, name)
    }

    @JvmStatic
    private val currentThreadId: Int get() =
        (Thread.currentThread() as? TestThread)?.iThread ?: 0

    @JvmStatic
    private val sharedEventsTracker: SharedEventsTracker
        get() = (Thread.currentThread() as TestThread).sharedEventsTracker!! // should be non-null
}
