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

import org.jetbrains.kotlinx.lincheck.runInIgnoredSection
import java.util.*

internal object Injections {
    @JvmStatic
    var lastSuspendedCancellableContinuationDuringVerification: Any? = null

    @JvmStatic
    fun storeCancellableContinuation(cont: Any) {
        val t = Thread.currentThread()
        if (t is TestThread) {
            t.suspendedContinuation = cont
        } else {
            lastSuspendedCancellableContinuationDuringVerification = cont
        }
    }

    @JvmStatic
    fun enterIgnoredSection(): Boolean {
        val t = Thread.currentThread()
        if (t !is TestThread || t.inIgnoredSection) return false
        t.inIgnoredSection = true
        return true
    }

    @JvmStatic
    fun leaveIgnoredSection() {
        val t = Thread.currentThread() as TestThread
        check(t.inIgnoredSection)
        t.inIgnoredSection = false
    }

    @JvmStatic
    fun inTestingCode(): Boolean {
        val t = Thread.currentThread()
        if (t !is TestThread) return false
        return t.inTestingCode && !t.inIgnoredSection
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
        return runInIgnoredSection { deterministicRandom().nextInt() }
    }

    @JvmStatic
    fun deterministicRandom(): Random {
        return sharedEventsTracker.getThreadLocalRandom()
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
    fun afterRead(value: Any?) {
        sharedEventsTracker.afterRead(value)
    }

    @JvmStatic
    fun afterWrite() {
        sharedEventsTracker.afterWrite()
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
    fun beforeMethodCall(owner: Any?, className: String, methodName: String, codeLocation: Int, params: Array<Any?>) {
        sharedEventsTracker.beforeMethodCall(owner, className, methodName, codeLocation, params)
    }


    @JvmStatic
    fun beforeAtomicMethodCall(ownerName: String, methodName: String, codeLocation: Int, params: Array<Any?>) {
        sharedEventsTracker.beforeAtomicMethodCall(ownerName, methodName, codeLocation, params)
    }

    @JvmStatic
    fun beforeAtomicUpdaterMethodCall(owner: Any?, methodName: String, codeLocation: Int, params: Array<Any?>) {
        sharedEventsTracker.beforeAtomicUpdaterMethodCall(owner!!, methodName, codeLocation, params)
    }

    @JvmStatic
    fun onMethodCallFinishedSuccessfully(result: Any?) {
        sharedEventsTracker.onMethodCallFinishedSuccessfully(result)
    }

    @JvmStatic
    fun onMethodCallVoidFinishedSuccessfully() {
        sharedEventsTracker.onMethodCallFinishedSuccessfully(VOID_RESULT)
    }

    @JvmStatic
    fun onMethodCallThrewException(t: Throwable) {
        sharedEventsTracker.onMethodCallThrewException(t)
    }

    @JvmStatic
    fun onNewObjectCreation(obj: Any) {
        sharedEventsTracker.onNewObjectCreation(obj)
    }

    @JvmStatic
    private val sharedEventsTracker: SharedEventsTracker
        get() = (Thread.currentThread() as TestThread).sharedEventsTracker!! // should be non-null

    @JvmStatic
    val VOID_RESULT = Any()

    @JvmStatic
    fun addDependency(receiver: Any, value: Any?) {
        sharedEventsTracker.addDependency(receiver, value)
    }

    // == LISTENING METHODS ==

    @JvmStatic
    internal fun hashCodeDeterministic(obj: Any): Int {
        val hashCode = obj.hashCode()
        return if (hashCode == System.identityHashCode(obj)) {
            identityHashCodeDeterministic(obj)
        } else {
            hashCode
        }
    }

    @JvmStatic
    internal fun identityHashCodeDeterministic(obj: Any?): Int {
        if (obj == null) return 0
        // TODO: easier to support when `javaagent` is merged
        return 0
    }
}