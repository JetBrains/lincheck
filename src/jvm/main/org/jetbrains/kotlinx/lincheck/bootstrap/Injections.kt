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
        return 0 // TODO This solution is incorrect, see #131
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
    fun beforeReadField(obj: Any?, fieldName: String, codeLocation: Int) {
        if (obj == null) return // Ignore, NullPointerException will be thrown
        sharedEventsTracker.beforeReadField(obj, fieldName, codeLocation)
    }

    @JvmStatic
    fun beforeReadFieldStatic(className: String, fieldName: String, codeLocation: Int) {
        sharedEventsTracker.beforeReadFieldStatic(className, fieldName, codeLocation)
    }

    @JvmStatic
    fun beforeReadArrayElement(array: Array<*>?, index: Int, codeLocation: Int) {
        if (array == null) return // Ignore, NullPointerException will be thrown
        sharedEventsTracker.beforeReadArrayElement(array, index, codeLocation)
    }

    @JvmStatic
    fun onReadValue(value: Any?) {
        sharedEventsTracker.onReadValue(value)
    }

    @JvmStatic
    fun beforeWriteField(obj: Any?, fieldName: String, value: Any?, codeLocation: Int) {
        if (obj == null) return // Ignore, NullPointerException will be thrown
        sharedEventsTracker.beforeWriteField(obj, fieldName, value, codeLocation)
    }

    @JvmStatic
    fun beforeWriteFieldStatic(className: String, fieldName: String, value: Any?, codeLocation: Int) {
        sharedEventsTracker.beforeWriteFieldStatic(className, fieldName, value, codeLocation)
    }

    @JvmStatic
    fun beforeWriteArrayElement(array: Array<*>?, index: Int, value: Any?, codeLocation: Int) {
        if (array == null) return // Ignore, NullPointerException will be thrown
        sharedEventsTracker.beforeWriteArrayElement(array, index, value, codeLocation)
    }

    @JvmStatic
    fun onNewAtomicFieldUpdater(updater: Any?, name: String) {
        AtomicFieldNameMapper.newAtomic(updater!!, name)
    }

    @JvmStatic
    private val currentThreadId: Int get() =
        (Thread.currentThread() as TestThread).iThread

    @JvmStatic
    private val sharedEventsTracker: SharedEventsTracker
        get() = SharedEventsTracker.analyzer!! // should be non-null
}
