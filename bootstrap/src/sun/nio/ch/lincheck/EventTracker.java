/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package sun.nio.ch.lincheck;

import java.lang.invoke.CallSite;

/**
 * Methods of this interface are called from the instrumented tested code during model-checking.
 * See {@link Injections} for the documentation.
 */
public interface EventTracker {

    void beforeThreadFork(Thread thread, ThreadDescriptor descriptor);
    void beforeThreadStart();
    void afterThreadFinish();
    void threadJoin(Thread thread, boolean withTimeout);
    void onThreadRunException(Throwable exception);

    void beforeLock(int codeLocation);
    void lock(Object monitor);
    void unlock(Object monitor, int codeLocation);

    void beforePark(int codeLocation);
    void park(int codeLocation);
    void unpark(Thread thread, int codeLocation);

    void beforeWait(int codeLocation);
    void wait(Object monitor, boolean withTimeout);
    void notify(Object monitor, int codeLocation, boolean notifyAll);

    void beforeNewObjectCreation(String className);
    void afterNewObjectCreation(Object obj);

    long getNextTraceDebuggerEventTrackerId(TraceDebuggerTracker tracker);
    void advanceCurrentTraceDebuggerEventTrackerId(TraceDebuggerTracker tracker, long oldId);

    CallSite getCachedInvokeDynamicCallSite(
            String name,
            String descriptor,
            Injections.HandlePojo bootstrapMethodHandlePojo,
            Object[] bootstrapMethodArguments
    );
    void cacheInvokeDynamicCallSite(
            String name,
            String descriptor,
            Injections.HandlePojo bootstrapMethodHandlePojo,
            Object[] bootstrapMethodArguments,
            CallSite callSite
    );

    void updateSnapshotBeforeConstructorCall(Object[] objs);

    boolean beforeReadField(Object obj, int codeLocation, int fieldId);
    boolean beforeReadArrayElement(Object array, int index, int codeLocation);
    void afterReadField(Object obj, int codeLocation, int fieldId, Object value);
    void afterReadArrayElement(Object array, int index, int codeLocation, Object value);

    boolean beforeWriteField(Object obj, Object value, int codeLocation, int fieldId);
    boolean beforeWriteArrayElement(Object array, int index, Object value, int codeLocation);
    void afterWrite();

    void afterLocalRead(int codeLocation, int variableId, Object value);
    void afterLocalWrite(int codeLocation, int variableId, Object value);

    Object onMethodCall(int codeLocation, int methodId, Object receiver, Object[] params);
    Object onMethodCallReturn(long descriptorId, Object descriptor, int methodId, Object receiver, Object[] params, Object result);
    Throwable onMethodCallException(long descriptorId, Object descriptor, int methodId, Object receiver, Object[] params, Throwable t);

    void onInlineMethodCall(int codeLocation, int methodId, Object owner);
    void onInlineMethodCallReturn(int methodId);

    BootstrapResult<?> invokeDeterministicallyOrNull(long descriptorId, Object descriptor, Object receiver, Object[] params);

    InjectedRandom getThreadLocalRandom();
    int randomNextInt();

    // Methods required for the plugin integration

    boolean shouldInvokeBeforeEvent();
    void beforeEvent(int eventId, String type);
    int getEventId();
    void setLastMethodCallEventId();
}