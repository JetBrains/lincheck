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
 * Methods of this object are called from the instrumented code.
 */
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

    /**
     * Called from instrumented code instead of the MONITORENTER instruction.
     */
    @JvmStatic
    fun lock(monitor: Any, codeLocation: Int) {
        eventTracker.lock(monitor, codeLocation)
    }

    /**
     * Called from instrumented code instead of the MONITOREXIT instruction.
     */
    @JvmStatic
    fun unlock(monitor: Any, codeLocation: Int) {
        eventTracker.unlock(monitor, codeLocation)
    }

    /**
     * Called from the instrumented code instead of `Unsafe.park`.
     */
    @JvmStatic
    fun park(codeLocation: Int) {
        eventTracker.park(codeLocation)
    }

    /**
     * Called from the instrumented code instead of `Unsafe.unpark`.
     */
    @JvmStatic
    fun unpark(thread: Thread, codeLocation: Int) {
        eventTracker.unpark(thread, codeLocation)
    }

    /**
     * Called from the instrumented code instead of [Object.wait].
     */
    @JvmStatic
    fun wait(monitor: Any, codeLocation: Int) {
        eventTracker.wait(monitor, codeLocation, withTimeout = false)
    }


    /**
     * Called from the instrumented code instead of [Object.wait].
     */
    @JvmStatic
    fun waitWithTimeout(monitor: Any, codeLocation: Int) {
        eventTracker.wait(monitor, codeLocation, withTimeout = true)
    }

    /**
     * Called from the instrumented code instead of [Object.notify].
     */
    @JvmStatic
    fun notify(monitor: Any, codeLocation: Int) {
        eventTracker.notify(monitor, codeLocation, notifyAll = false)
    }

    /**
     * Called from the instrumented code instead of [Object.notify].
     */
    @JvmStatic
    fun notifyAll(monitor: Any, codeLocation: Int) {
        eventTracker.notify(monitor, codeLocation, notifyAll = true)
    }

    /**
     * Called from the instrumented code replacing random `int` generation with a deterministic random value.
     */
    @JvmStatic
    fun nextInt(): Int =
        eventTracker.randomNextInt()

    /**
     * Called from the instrumented code to replace `ThreadLocalRandom.nextInt(origin, bound)` with a deterministic random value.
     */
    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun nextInt2(origin: Int, bound: Int): Int =
        eventTracker.run {
            runInIgnoredSection {
                getThreadLocalRandom().nextInt(bound)
            }
        }

    /**
     * Called from the instrumented code to get a random instance that is deterministic and controlled by Lincheck.
     */
    @JvmStatic
    fun deterministicRandom(): Random =
        eventTracker.getThreadLocalRandom()

    /**
     * Called from the instrumented code to check whether the object is a [Random] instance.
     */
    @JvmStatic
    fun isRandom(any: Any?): Boolean =
        any is Random

    /**
     * Called from the instrumented code before each field read.
     */
    @JvmStatic
    fun beforeReadField(obj: Any?, className: String, fieldName: String, codeLocation: Int) {
        if (obj == null) return // Ignore, NullPointerException will be thrown
        eventTracker.beforeReadField(obj, className, fieldName, codeLocation)
    }

    /**
     * Called from the instrumented code before any static field read.
     */
    @JvmStatic
    fun beforeReadFieldStatic(className: String, fieldName: String, codeLocation: Int) {
        eventTracker.beforeReadFieldStatic(className, fieldName, codeLocation)
    }

    /**
     * Called from the instrumented code before any array cell read.
     */
    @JvmStatic
    fun beforeReadArray(array: Any?, index: Int, codeLocation: Int) {
        if (array == null) return // Ignore, NullPointerException will be thrown
        eventTracker.beforeReadArrayElement(array, index, codeLocation)
    }

    /**
     * Called from the instrumented code after each field read (final field reads can be ignored here).
     */
    @JvmStatic
    fun afterRead(value: Any?) {
        eventTracker.afterRead(value)
    }

    /**
     * Called from the instrumented code before each field write.
     */
    @JvmStatic
    fun beforeWriteField(obj: Any?, className: String, fieldName: String, value: Any?, codeLocation: Int) {
        if (obj == null) return // Ignore, NullPointerException will be thrown
        eventTracker.beforeWriteField(obj, className, fieldName, value, codeLocation)
    }

    /**
     * Called from the instrumented code before any static field write.
     */
    @JvmStatic
    fun beforeWriteFieldStatic(className: String, fieldName: String, value: Any?, codeLocation: Int) {
        eventTracker.beforeWriteFieldStatic(className, fieldName, value, codeLocation)
    }

    /**
     * Called from the instrumented code before any array cell write.
     */
    @JvmStatic
    fun beforeWriteArray(array: Any?, index: Int, value: Any?, codeLocation: Int) {
        if (array == null) return // Ignore, NullPointerException will be thrown
        eventTracker.beforeWriteArrayElement(array, index, value, codeLocation)
    }

    /**
     * Called from the instrumented code before any write operation.
     */
    @JvmStatic
    fun afterWrite() {
        eventTracker.afterWrite()
    }

    /**
     * Called from the instrumented code before any method call.
     *
     * @param owner is `null` for static methods.
     */
    @JvmStatic
    fun beforeMethodCall(owner: Any?, className: String, methodName: String, codeLocation: Int, params: Array<Any?>) {
        eventTracker.beforeMethodCall(owner, className, methodName, codeLocation, params)
    }

    /**
     * Called from the instrumented code before any atomic method call.
     * This is just an optimization of [beforeMethodCall] for trusted
     * atomic constructs to avoid wrapping the invocations into try-finally blocks.
     */
    @JvmStatic
    fun beforeAtomicMethodCall(owner: Any?, methodName: String, codeLocation: Int, params: Array<Any?>) {
        eventTracker.beforeAtomicMethodCall(owner, methodName, codeLocation, params)
    }

    /**
     * Called from the instrumented code after any method successful call, i.e., without any exception.
     */
    @JvmStatic
    fun onMethodCallFinishedSuccessfully(result: Any?) {
        eventTracker.onMethodCallFinishedSuccessfully(result)
    }

    /**
     * Called from the instrumented code after any method that returns void successful call, i.e., without any exception.
     */
    @JvmStatic
    fun onMethodCallVoidFinishedSuccessfully() {
        eventTracker.onMethodCallFinishedSuccessfully(VOID_RESULT)
    }

    /**
     * Called from the instrumented code after any method call threw an exception
     */
    @JvmStatic
    fun onMethodCallThrewException(t: Throwable) {
        eventTracker.onMethodCallThrewException(t)
    }

    /**
     * Called from the instrumented code after any object is created
     */
    @JvmStatic
    fun onNewObjectCreation(obj: Any) {
        eventTracker.onNewObjectCreation(obj)
    }

    /**
     * Called from the instrumented code after value assigned to any receiver field.
     * Required to track local objects.
     * @param receiver the object in whose field the entry is made
     * @param value the value written into [receiver] field
     *
     * @see [LocalObjectManager]
     */
    @JvmStatic
    fun afterFieldAssign(receiver: Any, value: Any?) {
        eventTracker.afterFieldAssign(receiver, value)
    }

    /**
     * Called from the instrumented code to replace [java.lang.Object.hashCode] method call with some
     * deterministic value.
     */
    @JvmStatic
    internal fun hashCodeDeterministic(obj: Any): Int {
        val hashCode = obj.hashCode()
        // This is a dirty hack to determine whether there is a
        // custom hashCode() implementation or it is always delegated
        // to System.identityHashCode(..).
        // While this code is not robust in theory, it works
        // fine in practice.
        return if (hashCode == System.identityHashCode(obj)) {
            identityHashCodeDeterministic(obj)
        } else {
            hashCode
        }
    }

    /**
     * Called from the instrumented code to replace [java.lang.System.identityHashCode] method call with some
     * deterministic value.
     */
    @JvmStatic
    internal fun identityHashCodeDeterministic(obj: Any?): Int {
        if (obj == null) return 0
        // TODO: easier to support when `javaagent` is merged
        return 0
    }

    @JvmStatic
    private val eventTracker: EventTracker
        get() = (Thread.currentThread() as TestThread).eventTracker!! // should be non-null

    @JvmStatic
    val VOID_RESULT = Any()
}