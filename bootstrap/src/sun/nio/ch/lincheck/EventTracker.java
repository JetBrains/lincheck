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

    void beforeThreadStart(ThreadDescriptor descriptor, Thread startingThread, ThreadDescriptor startingThreadDescriptor);
    void beforeThreadRun(ThreadDescriptor descriptor);
    void afterThreadRunReturn(ThreadDescriptor descriptor);
    void afterThreadRunException(ThreadDescriptor descriptor, Throwable exception);
    void onThreadJoin(ThreadDescriptor descriptor, Thread thread, boolean withTimeout);
    void registerRunningThread(ThreadDescriptor descriptor, Thread thread);

    void beforeLock(ThreadDescriptor descriptor, int codeLocation);
    void lock(ThreadDescriptor descriptor, Object monitor);
    void unlock(ThreadDescriptor descriptor, int codeLocation, Object monitor);

    void beforePark(ThreadDescriptor descriptor, int codeLocation);
    void park(ThreadDescriptor descriptor, int codeLocation);
    void unpark(ThreadDescriptor descriptor, int codeLocation, Thread thread);

    void beforeWait(ThreadDescriptor descriptor, int codeLocation);
    void wait(ThreadDescriptor descriptor, Object monitor, boolean withTimeout);
    void notify(ThreadDescriptor descriptor, int codeLocation, Object monitor, boolean notifyAll);

    void beforeNewObjectCreation(ThreadDescriptor descriptor, String className);
    void afterNewObjectCreation(ThreadDescriptor descriptor, Object obj);

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

    void beforeReadField(ThreadDescriptor descriptor, int codeLocation, Object obj, int fieldId, String typeDescriptor);
    void beforeReadArrayElement(ThreadDescriptor descriptor, int codeLocation, Object array, int index, String typeDescriptor);

    Object interceptReadResult();

    void afterReadField(ThreadDescriptor descriptor, int codeLocation, Object obj, int fieldId, Object value);
    void afterReadArrayElement(ThreadDescriptor descriptor, int codeLocation, Object array, int index, Object value);

    void beforeWriteField(ThreadDescriptor descriptor, int codeLocation, Object obj, Object value, int fieldId, String typeDescriptor);
    void beforeWriteArrayElement(ThreadDescriptor descriptor, int codeLocation, Object array, int index, Object value, String typeDescriptor);
    void afterWrite(ThreadDescriptor descriptor);

    void afterLocalRead(ThreadDescriptor descriptor, int codeLocation, int variableId, Object value);
    void afterLocalWrite(ThreadDescriptor descriptor, int codeLocation, int variableId, Object value);

    void onMethodCall(ThreadDescriptor descriptor, int codeLocation, int methodId, Object receiver, Object[] params, ResultInterceptor interceptor);
    void onMethodCallReturn(ThreadDescriptor descriptor, int methodId, Object receiver, Object[] params, Object result, ResultInterceptor interceptor);
    void onMethodCallException(ThreadDescriptor descriptor, int methodId, Object receiver, Object[] params, Throwable t, ResultInterceptor interceptor);

    void onInlineMethodCall(ThreadDescriptor descriptor, int codeLocation, int methodId, Object owner);
    void onInlineMethodCallReturn(ThreadDescriptor descriptor, int methodId);
    void onInlineMethodCallException(ThreadDescriptor descriptor, int methodId, Throwable t);

    void onLoopIteration(ThreadDescriptor descriptor, int codeLocation, int loopId);
    void afterLoopExit(ThreadDescriptor descriptor, int codeLocation, int loopId, Throwable exception, boolean isReachableFromOutsideLoop);

    InjectedRandom getThreadLocalRandom();
    int randomNextInt();

    // Methods required for the plugin integration

    boolean shouldInvokeBeforeEvent();
    void beforeEvent(int eventId, String type);
    int getCurrentEventId();
}