/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.traceonly

import org.jetbrains.kotlinx.lincheck.strategy.toAsmHandle
import org.jetbrains.kotlinx.lincheck.util.runInsideIgnoredSection
import org.objectweb.asm.ConstantDynamic
import sun.nio.ch.lincheck.BootstrapResult
import sun.nio.ch.lincheck.EventTracker
import sun.nio.ch.lincheck.InjectedRandom
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.MethodSignature
import sun.nio.ch.lincheck.ThreadDescriptor
import sun.nio.ch.lincheck.TraceDebuggerTracker
import java.io.File
import java.io.PrintStream
import java.lang.invoke.CallSite
import java.util.concurrent.ConcurrentHashMap

class TraceCollectingEventTracker(val traceDumpPath: String?) :  EventTracker {
    private val invokeDynamicCallSites = ConcurrentHashMap<ConstantDynamic, CallSite>()

    private var randoms = ThreadLocal.withInitial { InjectedRandom() }

    override fun beforeThreadFork(thread: Thread, descriptor: ThreadDescriptor) {
        // Nothing to do for now
    }

    override fun beforeThreadStart() {
        // Nothing to do for now
    }

    override fun afterThreadFinish() {
        // Nothing to do for now
    }

    override fun threadJoin(thread: Thread, withTimeout: Boolean) {
        // Nothing to do for now
    }

    override fun onThreadRunException(exception: Throwable) {
        // Nothing to do for now
    }

    override fun beforeLock(codeLocation: Int) {
        // Nothing to do for now
    }

    override fun lock(monitor: Any) {
        // Nothing to do for now
    }

    override fun unlock(monitor: Any, codeLocation: Int) {
        // Nothing to do for now
    }

    override fun beforePark(codeLocation: Int) {
        // Nothing to do for now
    }

    override fun park(codeLocation: Int) {
        // Nothing to do for now
    }

    override fun unpark(thread: Thread, codeLocation: Int) {
        // Nothing to do for now
    }

    override fun beforeWait(codeLocation: Int) {
        // Nothing to do for now
    }

    override fun wait(monitor: Any, withTimeout: Boolean) {
        // Nothing to do for now
    }

    override fun notify(monitor: Any, codeLocation: Int, notifyAll: Boolean) {
        // Nothing to do for now
    }

    override fun beforeNewObjectCreation(className: String) {
        // Nothing to do for now
    }

    override fun afterNewObjectCreation(obj: Any) {
        // Nothing to do for now
    }

    override fun getNextTraceDebuggerEventTrackerId(tracker: TraceDebuggerTracker): Long = 0

    override fun advanceCurrentTraceDebuggerEventTrackerId(
        tracker: TraceDebuggerTracker,
        oldId: Long
    ) {
        // Nothing to do for now
    }

    override fun getCachedInvokeDynamicCallSite(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Injections.HandlePojo,
        bootstrapMethodArguments: Array<out Any?>
    ): CallSite? {
        // Nothing to do for now
        val trueBootstrapMethodHandle = bootstrapMethodHandle.toAsmHandle()
        val invokeDynamic = ConstantDynamic(name, descriptor, trueBootstrapMethodHandle, *bootstrapMethodArguments)
        return invokeDynamicCallSites[invokeDynamic]
    }

    override fun cacheInvokeDynamicCallSite(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Injections.HandlePojo,
        bootstrapMethodArguments: Array<out Any?>,
        callSite: CallSite
    ) {
        val trueBootstrapMethodHandle = bootstrapMethodHandle.toAsmHandle()
        val invokeDynamic = ConstantDynamic(name, descriptor, trueBootstrapMethodHandle, *bootstrapMethodArguments)
        invokeDynamicCallSites[invokeDynamic] = callSite
    }

    override fun updateSnapshotBeforeConstructorCall(objs: Array<out Any?>) {
        // Nothing to do for now
    }

    override fun beforeReadField(
        obj: Any,
        className: String,
        fieldName: String,
        codeLocation: Int,
        isStatic: Boolean,
        isFinal: Boolean
    ): Boolean  = false

    override fun beforeReadArrayElement(
        array: Any,
        index: Int,
        codeLocation: Int
    ): Boolean = false

    override fun afterRead(value: Any) {
        // Nothing to do for now
    }

    override fun beforeWriteField(
        obj: Any,
        className: String,
        fieldName: String,
        value: Any,
        codeLocation: Int,
        isStatic: Boolean,
        isFinal: Boolean
    ): Boolean = false

    override fun beforeWriteArrayElement(
        array: Any,
        index: Int,
        value: Any,
        codeLocation: Int
    ): Boolean = false

    override fun afterWrite() {
        // Nothing to do for now
    }

    override fun afterLocalRead(codeLocation: Int, name: String, value: Any?) {
        // Nothing to do for now
    }

    override fun afterLocalWrite(codeLocation: Int, name: String, value: Any?) {
        // Nothing to do for now
    }

    override fun onMethodCall(
        className: String,
        methodName: String,
        codeLocation: Int,
        methodId: Int,
        methodSignature: MethodSignature?,
        receiver: Any,
        params: Array<out Any?>
    ): Any? {
        // Nothing to do for now
        return null
    }

    override fun onMethodCallReturn(
        className: String,
        methodName: String,
        descriptorId: Long,
        descriptor: Any,
        methodId: Int,
        receiver: Any,
        params: Array<out Any?>,
        result: Any?
    ) {
        // Nothing to do for now
    }

    override fun onMethodCallException(
        className: String,
        methodName: String,
        descriptorId: Long,
        descriptor: Any,
        receiver: Any,
        params: Array<out Any?>,
        t: Throwable
    ) {
        // Nothing to do for now
    }

    override fun onInlineMethodCall(
        className: String?,
        methodName: String?,
        methodId: Int,
        codeLocation: Int,
        owner: Any?
    ) {
        // Nothing to do for now
    }

    override fun onInlineMethodCallReturn(className: String?, methodId: Int) {
        // Nothing to do for now
    }

    override fun invokeDeterministicallyOrNull(
        descriptorId: Long,
        descriptor: Any,
        receiver: Any,
        params: Array<out Any?>
    ): BootstrapResult<*>? = null

    override fun getThreadLocalRandom(): InjectedRandom = runInsideIgnoredSection {
        randoms.get()
    }

    override fun randomNextInt(): Int = runInsideIgnoredSection {
        randoms.get().nextInt()
    }

    override fun shouldInvokeBeforeEvent(): Boolean = false

    override fun beforeEvent(eventId: Int, type: String) {
        // Nothing to do for now
    }

    override fun getEventId(): Int = 0

    override fun setLastMethodCallEventId() {
        // Nothing to do for now
    }

    fun dumpTrace() {
        val f = if (traceDumpPath == null) System.out else PrintStream(File(traceDumpPath))
        f.println("Hello there!")
    }
}