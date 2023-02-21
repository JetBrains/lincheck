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
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock

// we need to use some "legal" package for the bootstrap class loader

internal interface SharedEventsTracker {
    companion object {
        @JvmStatic
        var analyzer: SharedEventsTracker? = null
    }

    fun lock(monitor: Any, codeLocation: Int)
    fun unlock(monitor: Any, codeLocation: Int)

    fun park(codeLocation: Int)
    fun unpark(thread: Thread, codeLocation: Int)

    fun wait(monitor: Any, codeLocation: Int, withTimeout: Boolean)
    fun notify(monitor: Any, codeLocation: Int, notifyAll: Boolean)

    fun beforeReadField(obj: Any, fieldName: String, codeLocation: Int)
    fun beforeReadFieldStatic(className: String, fieldName: String, codeLocation: Int)
    fun beforeReadArrayElement(array: Array<*>, index: Int, codeLocation: Int)
    fun onReadValue(value: Any?)

    fun beforeWriteField(obj: Any, fieldName: String, value: Any?, codeLocation: Int)
    fun beforeWriteFieldStatic(className: String, fieldName: String, value: Any?, codeLocation: Int)
    fun beforeWriteArrayElement(array: Array<*>, index: Int, value: Any?, codeLocation: Int)

    fun getRandom(currentThreadId: Int): Random
}

internal object DummySharedEventsTracker : SharedEventsTracker {
    private val locks = WeakIdentityHashMap<Any, ReentrantLock>()

    override fun lock(monitor: Any, codeLocation: Int) {
        val lock = synchronized(locks) {
            if (locks[monitor] == null) locks.put(monitor, ReentrantLock())
            locks[monitor]
        }
        lock.lock()
    }

    override fun unlock(monitor: Any, codeLocation: Int) {
        val lock = synchronized(locks) {
            locks[monitor]!!
        }
        lock.unlock()
    }

    override fun park(codeLocation: Int) {
        LockSupport.park()
    }

    override fun unpark(thread: Thread, codeLocation: Int) {
        LockSupport.unpark(thread)
    }

    override fun wait(monitor: Any, codeLocation: Int, withTimeout: Boolean) {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (monitor as Object).wait(if (withTimeout) 1 else 0)
    }

    override fun notify(monitor: Any, codeLocation: Int, notifyAll: Boolean) {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        monitor as Object
        if (notifyAll) monitor.notifyAll() else monitor.notify()
    }

    override fun beforeReadField(obj: Any, fieldName: String, codeLocation: Int) {}
    override fun beforeReadFieldStatic(className: String, fieldName: String, codeLocation: Int) {}
    override fun beforeReadArrayElement(array: Array<*>, index: Int, codeLocation: Int) {}
    override fun onReadValue(value: Any?) {}

    override fun beforeWriteField(obj: Any, fieldName: String, value: Any?, codeLocation: Int) {}
    override fun beforeWriteFieldStatic(className: String, fieldName: String, value: Any?, codeLocation: Int) {}
    override fun beforeWriteArrayElement(array: Array<*>, index: Int, value: Any?, codeLocation: Int) {}

    override fun getRandom(currentThreadId: Int) = Random()
}

/**
When Lincheck runs a test, all threads should be instances of this [TestThread] class.
See its usages to get more insight.

NB: we need to load this class in the bootstrap class loader, as the transformation requires it.
 */
internal class TestThread(
    val iThread: Int,
    val runnerHash: Int,
    r: Runnable
) : Thread(r, "Lincheck@$runnerHash-$iThread") {
    @JvmField
    var cont: Any? = null // The suspended continuation, if present.

    // This flag is set to `true` when Lincheck runs user's code.
    //
    // When it is `false`, the analysis is disabled.
    @JvmField
    var inTestingCode = false

    // During user's executions, Lincheck may enter a code block the analysis of which
    // should be disabled. As such code blocks can be nested, we maintain the current
    // depth of them.
    //
    // When it is >=0, the analysis is disabled.
    @JvmField
    var ignoredSectionDepth = 0
}
