/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck

import java.util.*

/**
 * Methods of this interface are called from the instrumented tested code during model-checking.
 */
internal interface EventTracker {

    /**
     * @see Injections.lock
     */
    fun lock(monitor: Any, codeLocation: Int)

    /**
     * @see Injections.unlock
     */
    fun unlock(monitor: Any, codeLocation: Int)

    /**
     * @see Injections.park
     */
    fun park(codeLocation: Int)

    /**
     * @see Injections.unpark
     */
    fun unpark(thread: Thread, codeLocation: Int)

    /**
     * @see Injections.wait
     * @see Injections.waitWithTimeout
     */
    fun wait(monitor: Any, codeLocation: Int, withTimeout: Boolean)

    /**
     * @see Injections.notify
     * @see Injections.notifyAll
     */
    fun notify(monitor: Any, codeLocation: Int, notifyAll: Boolean)

    /**
     * @see Injections.beforeReadField
     */
    fun beforeReadField(obj: Any, className: String, fieldName: String, codeLocation: Int)


    /**
     * @see Injections.beforeReadFieldStatic
     */
    fun beforeReadFieldStatic(className: String, fieldName: String, codeLocation: Int)

    /**
     * @see Injections.beforeReadArray
     */
    fun beforeReadArrayElement(array: Any, index: Int, codeLocation: Int)

    /**
     * @see Injections.afterRead
     */
    fun afterRead(value: Any?)


    /**
     * @see Injections.beforeWriteField
     */
    fun beforeWriteField(obj: Any, className: String, fieldName: String, value: Any?, codeLocation: Int)

    /**
     * @see Injections.beforeReadFieldStatic
     */
    fun beforeWriteFieldStatic(className: String, fieldName: String, value: Any?, codeLocation: Int)

    /**
     * @see Injections.beforeWriteArray
     */
    fun beforeWriteArrayElement(array: Any, index: Int, value: Any?, codeLocation: Int)

    /**
     * @see Injections.beforeMethodCall
     */
    fun beforeMethodCall(owner: Any?, className: String, methodName: String, codeLocation: Int, params: Array<Any?>)

    /**
     * @see Injections.beforeAtomicMethodCall
     */
    fun beforeAtomicMethodCall(ownerName: String, methodName: String, codeLocation: Int, params: Array<Any?>)

    /**
     * @see Injections.beforeAtomicUpdaterMethodCall
     */
    fun beforeAtomicUpdaterMethodCall(owner: Any, methodName: String, codeLocation: Int, params: Array<Any?>)

    /**
     * @see Injections.onMethodCallFinishedSuccessfully
     */
    fun onMethodCallFinishedSuccessfully(result: Any?)

    /**
     * @see Injections.onMethodCallThrewException
     */
    fun onMethodCallThrewException(t: Throwable)

    /**
     * @see Injections.deterministicRandom
     */
    fun getThreadLocalRandom(): Random

    /**
     * @see Injections.nextInt
     */
    fun randomNextInt(): Int

    /**
     * @see Injections.onNewObjectCreation
     */
    fun onNewObjectCreation(obj: Any)

    /**
     * @see Injections.addDependency
     */
    fun addDependency(receiver: Any, value: Any?)

    /**
     * @see Injections.afterWrite
     */
    fun afterWrite()
}