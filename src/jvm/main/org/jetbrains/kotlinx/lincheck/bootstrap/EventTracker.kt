/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

// we need to use some "legal" package for the bootstrap class loader
@file:Suppress("PackageDirectoryMismatch")

package sun.nio.ch.lincheck

import java.util.*

/**
 * Methods of this interface are called from the instrumented tested code during model-checking.
 */
internal interface EventTracker {

    /**
     * Called from instrumented code before the lock operation
     */
    fun lock(monitor: Any, codeLocation: Int)

    /**
     * Called from instrumented code before the unlock operation
     */
    fun unlock(monitor: Any, codeLocation: Int)

    /**
     * Called from the instrumented code before the park operation
     */
    fun park(codeLocation: Int)

    /**
     * Called from the instrumented code before the unpark operation
     */
    fun unpark(thread: Thread, codeLocation: Int)

    /**
     * Called from the instrumented code before the wait operation
     */
    fun wait(monitor: Any, codeLocation: Int, withTimeout: Boolean)

    /**
     * Called from the instrumented code before the notify or notifyAll operation
     */
    fun notify(monitor: Any, codeLocation: Int, notifyAll: Boolean)

    /**
     * Called from the instrumented code before any field read
     */
    fun beforeReadField(obj: Any, className: String, fieldName: String, codeLocation: Int)


    fun beforeReadFieldStatic(className: String, fieldName: String, codeLocation: Int)
    fun beforeReadArrayElement(array: Any, index: Int, codeLocation: Int)
    fun afterRead(value: Any?)

    fun beforeWriteField(obj: Any, className: String, fieldName: String, value: Any?, codeLocation: Int)
    fun beforeWriteFieldStatic(className: String, fieldName: String, value: Any?, codeLocation: Int)
    fun beforeWriteArrayElement(array: Any, index: Int, value: Any?, codeLocation: Int)

    fun beforeMethodCall(owner: Any?, className: String, methodName: String, codeLocation: Int, params: Array<Any?>)
    fun beforeAtomicMethodCall(ownerName: String, methodName: String, codeLocation: Int, params: Array<Any?>)
    fun beforeAtomicUpdaterMethodCall(owner: Any, methodName: String, codeLocation: Int, params: Array<Any?>)

    fun onMethodCallFinishedSuccessfully(result: Any?)
    fun onMethodCallThrewException(t: Throwable)

    fun getThreadLocalRandom(): Random

    fun onNewObjectCreation(obj: Any)

    fun addDependency(receiver: Any, value: Any?)
    fun afterWrite()
}