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
        var enteredIgnoredSection = enterIgnoredSection();
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
        return any instanceof Random;
    }

    /**
     * Called from the instrumented code before each field read.
     *
     * @return whether the trace point was created
     */
    public static boolean beforeReadField(Object obj, String className, String fieldName, int codeLocation) {
        if (obj == null) return false; // Ignore, NullPointerException will be thrown
        return getEventTracker().beforeReadField(obj, className, fieldName, codeLocation);
    }

    /**
     * Called from the instrumented code before any public static field read.
     */
    public static void beforeReadFieldStatic(String className, String fieldName, int codeLocation) {
        getEventTracker().beforeReadFieldStatic(className, fieldName, codeLocation);
    }

    /**
     * Called from the instrumented code before any public static field read.
     * We need to track such reads to ensure that the corresponding objects
     * are instrumented.
     */
    public static void beforeReadFinalFieldStatic(String className) {
        getEventTracker().beforeReadFinalFieldStatic(className);
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
    public static boolean beforeWriteField(Object obj, String className, String fieldName, Object value, int codeLocation) {
        if (obj == null) return false; // Ignore, NullPointerException will be thrown
        return getEventTracker().beforeWriteField(obj, className, fieldName, value, codeLocation);
    }

    /**
     * Called from the instrumented code before any public static field write.
     */
    public static void beforeWriteFieldStatic(String className, String fieldName, Object value, int codeLocation) {
        getEventTracker().beforeWriteFieldStatic(className, fieldName, value, codeLocation);
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
    public static void beforeMethodCall(Object owner, String className, String methodName, int codeLocation, Object[] params) {
        getEventTracker().beforeMethodCall(owner, className, methodName, codeLocation, params);
    }

    /**
     * Called from the instrumented code before any atomic method call.
     * This is just an optimization of [beforeMethodCall] for trusted
     * atomic constructs to avoid wrapping the invocations into try-finally blocks.
     */
    public static void beforeAtomicMethodCall(Object owner, String methodName, int codeLocation, Object[] params) {
        getEventTracker().beforeAtomicMethodCall(owner, methodName, codeLocation, params);
    }

    /**
     * Called from the instrumented code after any method successful call, i.e., without any exception.
     */
    public static void onMethodCallFinishedSuccessfully(Object result) {
        getEventTracker().onMethodCallFinishedSuccessfully(result);
    }

    /**
     * Called from the instrumented code after any method that returns void successful call, i.e., without any exception.
     */
    public static void onMethodCallVoidFinishedSuccessfully() {
        getEventTracker().onMethodCallFinishedSuccessfully(VOID_RESULT);
    }

    /**
     * Called from the instrumented code after any method call threw an exception
     */
    public static void onMethodCallThrewException(Throwable t) {
        getEventTracker().onMethodCallThrewException(t);
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
     * Called from the instrumented code after value assigned to any receiver field.
     * Required to track local objects.
     *
     * @param receiver              the object in whose field the entry is made
     * @param fieldOrArrayCellValue the value written into [receiver] field
     * @see [LocalObjectManager]
     */
    public static void onWriteToObjectFieldOrArrayCell(Object receiver, Object fieldOrArrayCellValue) {
        getEventTracker().onWriteToObjectFieldOrArrayCell(receiver, fieldOrArrayCellValue);
    }

    /**
     * Called from the instrumented code to replace [java.lang.Object.hashCode] method call with some
     * deterministic value.
     */
    public static int hashCodeDeterministic(Object obj) {
        var hashCode = obj.hashCode();
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

    public static void beforeEvent(int eventId, String type) {
        getEventTracker().beforeEvent(eventId, type);
    }

    /**
     * @param type type of the next event. Used only for debug purposes.
     */
    public static int getNextEventId(String type) {
        return getEventTracker().getEventId();
    }

    public static void setLastMethodCallEventId() {
        getEventTracker().setLastMethodCallEventId();
    }
}