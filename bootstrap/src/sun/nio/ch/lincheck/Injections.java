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

import java.util.Random;

/**
 * Methods of this object are called from the instrumented code.
 */
public class Injections {
    public static final Object VOID_RESULT = new Object();
    // Used in the verification phase to store a suspended continuation.
    public static Object lastSuspendedCancellableContinuationDuringVerification = null;

    /**
     * Mark value of {@link #requestedBeforeEventId} field to skip calls to {@link #beforeEvent}.
     */
    @SuppressWarnings("unused")
    private static final int DO_NOT_TRIGGER_BEFORE_EVENT = -1;

    /**
     * Mark value of {@link #requestedBeforeEventId} field to always call {@link #beforeEvent}.
     */
    private static final int STOP_AT_NEXT_EVENT_ID = -2;

    /**
     * This field is updated from the debugger to request a specific ID.
     * <p>
     * The default value is calling always for compatibility with old plugin versions.
     */
    @SuppressWarnings({"FieldMayBeFinal", "FieldCanBeLocal"})
    private static int requestedBeforeEventId = STOP_AT_NEXT_EVENT_ID;

    /**
     * This field is used by the debugger to have a fast source of current ID.
     */
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private static int currentEventId = -1;

    public static void storeCancellableContinuation(Object cont) {
        Thread t = Thread.currentThread();
        if (t instanceof TestThread) {
            ((TestThread) t).suspendedContinuation = cont;
        } else {
            // We are in the verification phase.
            lastSuspendedCancellableContinuationDuringVerification = cont;
        }
    }

    public static boolean enterIgnoredSection() {
        Thread t = Thread.currentThread();
        if (!(t instanceof TestThread)) return false;
        TestThread testThread = (TestThread) t;
        if (testThread.inIgnoredSection) return false;
        testThread.inIgnoredSection = true;
        return true;
    }

    public static void leaveIgnoredSection() {
        Thread t = Thread.currentThread();
        if (t instanceof TestThread) {
            ((TestThread) t).inIgnoredSection = false;
        }
    }

    public static boolean inTestingCode() {
        Thread t = Thread.currentThread();
        if (t instanceof TestThread) {
            TestThread testThread = (TestThread) t;
            return testThread.inTestingCode && !testThread.inIgnoredSection;
        }
        return false;
    }

    /**
     * See [org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy.lock] for the explanation
     * why we have beforeLock method.
     *
     * Creates a trace point which is used in the subsequent [beforeEvent] method call.
     */
    public static void beforeLock(int codeLocation) {
        getEventTracker().beforeLock(codeLocation);
    }

    /**
     * Called from instrumented code instead of the MONITORENTER instruction,
     * but after [beforeEvent] method call, if the plugin is enabled.
     */
    public static void lock(Object monitor) {
        getEventTracker().lock(monitor);
    }

    /**
     * Called from instrumented code instead of the MONITOREXIT instruction.
     */
    public static void unlock(Object monitor, int codeLocation) {
        getEventTracker().unlock(monitor, codeLocation);
    }

    /**
     * Called from the instrumented code instead of `Unsafe.park`.
     */
    public static void park(int codeLocation) {
        getEventTracker().park(codeLocation);
    }

    /**
     * Called from the instrumented code instead of `Unsafe.unpark`.
     */
    public static void unpark(Thread thread, int codeLocation) {
        getEventTracker().unpark(thread, codeLocation);
    }

    /**
     * See [org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy.wait] for the explanation
     * why we have beforeWait method.
     *
     * Creates a trace point which is used in the subsequent [beforeEvent] method call.
     */
    public static void beforeWait(int codeLocation) {
        getEventTracker().beforeWait(codeLocation);
    }

    /**
     * Called from the instrumented code instead of [Object.wait],
     * but after [beforeEvent] method call, if the plugin is enabled.
     */
    public static void wait(Object monitor) {
        getEventTracker().wait(monitor, false);
    }


    /**
     * Called from the instrumented code instead of [Object.wait] with timeout,
     * but after [beforeEvent] method call, if the plugin is enabled.
     */
    public static void waitWithTimeout(Object monitor) {
        getEventTracker().wait(monitor, true);
    }


    /**
     * Called from the instrumented code instead of [Object.notify].
     */
    public static void notify(Object monitor, int codeLocation) {
        getEventTracker().notify(monitor, codeLocation, false);
    }

    /**
     * Called from the instrumented code instead of [Object.notify].
     */
    public static void notifyAll(Object monitor, int codeLocation) {
        getEventTracker().notify(monitor, codeLocation, true);
    }

    /**
     * Called from the instrumented code replacing random `int` generation with a deterministic random value.
     */
    public static int nextInt() {
        return getEventTracker().randomNextInt();
    }

    /**
     * Called from the instrumented code to replace `ThreadLocalRandom.nextInt(origin, bound)` with a deterministic random value.
     */
    public static int nextInt2(int origin, int bound) {
        boolean enteredIgnoredSection = enterIgnoredSection();
        try {
            return deterministicRandom().nextInt(bound);
        } finally {
            if (enteredIgnoredSection) {
                leaveIgnoredSection();
            }
        }
    }

    /**
     * Called from the instrumented code to get a random instance that is deterministic and controlled by Lincheck.
     */
    public static Random deterministicRandom() {
        return getEventTracker().getThreadLocalRandom();
    }

    /**
     * Called from the instrumented code to check whether the object is a [Random] instance.
     */
    public static boolean isRandom(Object any) {
        // Is this a Java random?
        if (any instanceof Random) return  true;
        // Is this a Kotlin random?
        try {
            Class<?> kotlinRandomClass = any.getClass().getClassLoader().loadClass("kotlin.random.Random");
            return kotlinRandomClass.isInstance(any);
        } catch (ClassNotFoundException e) {
            // Kotlin is not used in the user project.
        }
        // No, this is not a random instance.
        return false;
    }

    /**
     * Called from the instrumented code before each field read.
     *
     * @return whether the trace point was created
     */
    public static boolean beforeReadField(Object obj, String className, String fieldName, int codeLocation,
                                          boolean isStatic, boolean isFinal) {
        if (!isStatic && obj == null) return false; // Ignore, NullPointerException will be thrown
        return getEventTracker().beforeReadField(obj, className, fieldName, codeLocation, isStatic, isFinal);
    }

    /**
     * Called from the instrumented code before any array cell read.
     *
     * @return whether the trace point was created
     */
    public static boolean beforeReadArray(Object array, int index, int codeLocation) {
        if (array == null) return false; // Ignore, NullPointerException will be thrown
        return getEventTracker().beforeReadArrayElement(array, index, codeLocation);
    }

    /**
     * Called from the instrumented code after each field read (final field reads can be ignored here).
     */
    public static void afterRead(Object value) {
        getEventTracker().afterRead(value);
    }

    /**
     * Called from the instrumented code before each field write.
     *
     * @return whether the trace point was created
     */
    public static boolean beforeWriteField(Object obj, String className, String fieldName, Object value, int codeLocation,
                                           boolean isStatic, boolean isFinal) {
        if (!isStatic && obj == null) return false; // Ignore, NullPointerException will be thrown
        return getEventTracker().beforeWriteField(obj, className, fieldName, value, codeLocation, isStatic, isFinal);
    }

    /**
     * Called from the instrumented code before any array cell write.
     *
     * @return whether the trace point was created
     */
    public static boolean beforeWriteArray(Object array, int index, Object value, int codeLocation) {
        if (array == null) return false; // Ignore, NullPointerException will be thrown
        return getEventTracker().beforeWriteArrayElement(array, index, value, codeLocation);
    }

    /**
     * Called from the instrumented code before any write operation.
     */
    public static void afterWrite() {
        getEventTracker().afterWrite();
    }

    /**
     * Called from the instrumented code before any method call.
     *
     * @param owner is `null` for public static methods.
     */
    public static void beforeMethodCall(Object owner, String className, String methodName, int codeLocation, int methodId, Object[] params) {
        getEventTracker().beforeMethodCall(owner, className, methodName, codeLocation, methodId, params);
    }

    /**
     * Called from the instrumented code after any method successful call, i.e., without any exception.
     */
    public static void onMethodCallReturn(Object result) {
        getEventTracker().onMethodCallReturn(result);
    }

    /**
     * Called from the instrumented code after any method that returns void successful call, i.e., without any exception.
     */
    public static void onMethodCallReturnVoid() {
        getEventTracker().onMethodCallReturn(VOID_RESULT);
    }

    /**
     * Called from the instrumented code after any method call threw an exception
     */
    public static void onMethodCallException(Throwable t) {
        getEventTracker().onMethodCallException(t);
    }

    /**
     * Called from the instrumented code before NEW instruction
     */
    public static void beforeNewObjectCreation(String className) {
        getEventTracker().beforeNewObjectCreation(className);
    }

    /**
     * Called from the instrumented code after any object is created
     */
    public static void afterNewObjectCreation(Object obj) {
        getEventTracker().afterNewObjectCreation(obj);
    }

    /**
     * Called from instrumented code before constructors' invocations,
     * where passed objects are subtypes of the constructor class type.
     * Required to update the static memory snapshot.
     */
    public static void updateSnapshotBeforeConstructorCall(Object[] objs) {
        getEventTracker().updateSnapshotBeforeConstructorCall(objs);
    }

    /**
     * Called from the instrumented code to replace [java.lang.Object.hashCode] method call with some
     * deterministic value.
     */
    public static int hashCodeDeterministic(Object obj) {
        int hashCode = obj.hashCode();
        // This is a dirty hack to determine whether there is a
        // custom hashCode() implementation or it is always delegated
        // to System.identityHashCode(..).
        // While this code is not robust in theory, it works
        // fine in practice.
        if (hashCode == System.identityHashCode(obj)) {
            return identityHashCodeDeterministic(obj);
        } else {
            return hashCode;
        }
    }

    /**
     * Called from the instrumented code to replace [java.lang.System.identityHashCode] method call with some
     * deterministic value.
     */
    public static int identityHashCodeDeterministic(Object obj) {
        if (obj == null) return 0;
        // TODO: easier to support when `javaagent` is merged
        return 0;
    }

    private static EventTracker getEventTracker() {
        Thread currentThread = Thread.currentThread();
        if (currentThread instanceof TestThread) {
            return ((TestThread) currentThread).eventTracker;
        }
        throw new RuntimeException("Current thread is not an instance of TestThread");
    }


    // == Methods required for the IDEA Plugin integration ==

    public static boolean shouldInvokeBeforeEvent() {
        return getEventTracker().shouldInvokeBeforeEvent();
    }

    /**
     * This method is introduced for performance purposes.
     * Instead of calling {@link #beforeEvent} on every event, we call it only at the requested point.
     * It greatly improves the performance as the debugger installs a breakpoint into {@link #beforeEvent} method,
     * so each call leads to unnecessary lincheck-debugger communication.
     * @param eventId current id value
     * @return whether the current point should lead to {@link #beforeEvent} call
     */
    public static boolean isBeforeEventRequested(int eventId) {
        int requestedId = requestedBeforeEventId;
        return requestedId == STOP_AT_NEXT_EVENT_ID || requestedId == eventId;
    }

    public static void beforeEvent(int eventId, String type) {
        // IDEA plugin installs breakpoint to this method
        getEventTracker().beforeEvent(eventId, type);
    }

    /**
     * Gets current ID and sets it into {@link #currentEventId}.
     * @param type type of the next event. Used only for debug purposes.
     */
    public static int getNextEventId(String type) {
        int eventId = getEventTracker().getEventId();
        currentEventId = eventId;
        return eventId;
    }

    public static void setLastMethodCallEventId() {
        getEventTracker().setLastMethodCallEventId();
    }
}