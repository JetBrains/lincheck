/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package sun.nio.ch.lincheck

import java.util.*

/**
 * Methods of this interface are called from the instrumented tested code during model-checking.
 * See [Injections] for the documentation.
 */
interface EventTracker {

    fun beforeLock(codeLocation: Int)
    fun lock(monitor: Any)
    fun unlock(monitor: Any, codeLocation: Int)

    fun park(codeLocation: Int)
    fun unpark(thread: Thread, codeLocation: Int)

    fun beforeWait(codeLocation: Int)
    fun wait(monitor: Any, withTimeout: Boolean)
    fun notify(monitor: Any, codeLocation: Int, notifyAll: Boolean)

    fun beforeNewObjectCreation(className: String)
    fun afterNewObjectCreation(obj: Any)

    fun beforeReadField(obj: Any?, className: String, fieldName: String, codeLocation: Int,
                        isStatic: Boolean, isFinal: Boolean): Boolean
    fun beforeReadArrayElement(array: Any, index: Int, codeLocation: Int): Boolean
    fun afterRead(value: Any?)

    fun beforeWriteField(obj: Any?, className: String, fieldName: String, value: Any?, codeLocation: Int,
                         isStatic: Boolean, isFinal: Boolean): Boolean
    fun beforeWriteArrayElement(array: Any, index: Int, value: Any?, codeLocation: Int): Boolean
    fun afterWrite()

    fun afterReflectiveSetter(receiver: Any?, value: Any?)

    fun beforeMethodCall(owner: Any?, className: String, methodName: String, codeLocation: Int, params: Array<Any?>)
    fun beforeAtomicMethodCall(owner: Any?, className: String, methodName: String, codeLocation: Int, params: Array<Any?>)
    fun onMethodCallReturn(result: Any?)
    fun onMethodCallException(t: Throwable)

    fun getThreadLocalRandom(): Random
    fun randomNextInt(): Int

    // Methods required for the plugin integration

    fun shouldInvokeBeforeEvent(): Boolean
    fun beforeEvent(eventId: Int, type: String)
    fun getEventId(): Int
    fun setLastMethodCallEventId()
}