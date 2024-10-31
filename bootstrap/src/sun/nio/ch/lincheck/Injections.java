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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Methods of this object are called from the instrumented code.
 */
public class Injections {

    // Special object to represent void method call result.
    public static final Object VOID_RESULT = new Object();

    // Used in the verification phase to store a suspended continuation.
    public static Object lastSuspendedCancellableContinuationDuringVerification = null;

    /*
     * Thread local variable storing the thread descriptor for each thread.
     *
     * NOTE: as an optimization, for the `TestThread` instances,
     *   we avoid lookup via `ThreadLocal` by instead storing
     *   the thread descriptor in a field of the thread object itself.
     */
    private static final ThreadLocal<ThreadDescriptor> threadDescriptor =
        ThreadLocal.withInitial(() -> null);

    private static final ConcurrentHashMap<Integer, ThreadDescriptor> threadDescriptorsMap =
        new ConcurrentHashMap<Integer, ThreadDescriptor>();

    public static ThreadDescriptor getCurrentThreadDescriptor() {
        var thread = Thread.currentThread();
        if (thread instanceof TestThread) {
            return ((TestThread) thread).descriptor;
        }
        return threadDescriptor.get();
    }

    public static void setCurrentThreadDescriptor(ThreadDescriptor descriptor) {
        var thread = Thread.currentThread();
        if (thread instanceof TestThread) {
            return;
        }
        threadDescriptor.set(descriptor);
    }

    public static ThreadDescriptor getThreadDescriptor(Thread thread) {
        if (thread instanceof TestThread) {
            return ((TestThread) thread).descriptor;
        }
        // TODO: handle hashcode collisions (?)
        var hashCode = System.identityHashCode(thread);
        return threadDescriptorsMap.get(hashCode);
    }

    public static void setThreadDescriptor(Thread thread, ThreadDescriptor descriptor) {
        if (thread instanceof TestThread) {
            ((TestThread) thread).descriptor = descriptor;
            return;
        }
        // TODO: handle hashcode collisions (?)
        var hashCode = System.identityHashCode(thread);
        var previousDescriptor = threadDescriptorsMap.put(hashCode, descriptor);
        if (previousDescriptor != null) {
            var message = String.format(
                "Thread descriptor of thread %s was already set (previous thread is %s)!",
                thread.getName(),
                previousDescriptor.getThread().getName()
            );
            throw new IllegalStateException(message);
        }
    }

    public static EventTracker getEventTracker() {
        var descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) {
            throw new RuntimeException("No event tracker set by Lincheck");
        }
        return descriptor.getEventTracker();
    }

    public static void storeCancellableContinuation(Object cont) {
        Thread t = Thread.currentThread();
        if (t instanceof TestThread) {
            ((TestThread) t).suspendedContinuation = cont;
        } else {
            // We are in the verification phase.
            lastSuspendedCancellableContinuationDuringVerification = cont;
        }
    }

    public static void enterTestingCode() {
        var descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) return;
        descriptor.enterTestingCode();
    }

    public static void leaveTestingCode() {
        var descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) return;
        descriptor.leaveTestingCode();
    }

    /**
     * Enters an ignored section for the current thread.
     * A code inside the ignored section is not analyzed by the Lincheck.
     *
     * Note that the thread may not actually enter the ignored section in the following cases.
     *   1. The thread is not registered in the Lincheck strategy.
     *   2. The thread is already inside the ignored section.
     *
     * @return true if the thread successfully entered the ignored section, false otherwise.
     */
    public static boolean enterIgnoredSection() {
        var descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) return false;
        return descriptor.enterIgnoredSection();
    }

    /**
     * Leaves an ignored section for the current thread.
     */
    public static void leaveIgnoredSection() {
        var descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) return;
        descriptor.leaveIgnoredSection();
    }

    /**
     * Determines if the current thread is inside an ignored section.
     *
     * @return true if the current thread is inside an ignored section, false otherwise.
     */
    public static boolean inIgnoredSection() {
        var descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) return true;
        return descriptor.inIgnoredSection();
    }

    /**
     * Current thread reports that it is going to start a new child thread {@code forkedThread}.
     */
    public static void beforeThreadFork(Thread forkedThread) {
        // TestThread is handled separately
        if (forkedThread instanceof TestThread) return;
        var descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) {
            return;
        }
        var tracker = descriptor.getEventTracker();
        var forkedThreadDescriptor = new ThreadDescriptor(forkedThread);
        forkedThreadDescriptor.setEventTracker(tracker);
        /*
         * Method `setThreadDescriptor` calls methods of `ConcurrentHashMap` (instrumented class),
         * and at this point the calling thread can have the event tracker set,
         * so we need to wrap the call into an ignored section.
         *
         * Note that other thread events tracking methods don't need to wrap anything
         * into an ignored section, because when they are called, either
         *   (1) thread descriptor (and thus event tracker) of the thread is not installed yet, or
         *   (2) they do not call any instrumented methods themselves.
         */
        descriptor.enterIgnoredSection();
        setThreadDescriptor(forkedThread, forkedThreadDescriptor);
        descriptor.leaveIgnoredSection();
        /*
         * End of the ignored section, the rest should be
         * wrapped into an ignored section by the event tracker itself, if necessary.
         */
        tracker.beforeThreadFork(forkedThread, forkedThreadDescriptor);
    }

    /**
     * Current thread reports that it started a new child thread {@code forkedThread}.
     */
    public static void afterThreadFork(Thread forkedThread) {
        // TestThread is handled separately
        if (forkedThread instanceof TestThread) return;
        var descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) return;
        var tracker = descriptor.getEventTracker();
        tracker.afterThreadFork(forkedThread);
    }

    /**
     * Current thread entered its {@code run} method.
     */
    public static void beforeThreadStart() {
        var thread = Thread.currentThread();
        // TestThread is handled separately
        if (thread instanceof TestThread) return;
        var descriptor = getThreadDescriptor(thread);
        if (descriptor == null) {
            return;
        }
        setCurrentThreadDescriptor(descriptor);
        var tracker = descriptor.getEventTracker();
        tracker.beforeThreadStart();
    }

    /**
     * Current thread returned from its {@code run} method.
     */
    public static void afterThreadFinish() {
        var thread = Thread.currentThread();
        // TestThread is handled separately
        if (thread instanceof TestThread) return;
        var descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) return;
        var tracker = descriptor.getEventTracker();
        tracker.afterThreadFinish();
    }

    /**
     * Current thread successfully joined thread {@code t}.
     * <p>
     * <b>Does not support joins with time limits yet</b>.
     */
    public static void beforeThreadJoin(Thread t) {
        var descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) return;
        var tracker = descriptor.getEventTracker();
        tracker.beforeThreadJoin(t);
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
     * Called from the instrumented code after atomic write is performed through either
     * the AtomicXXXFieldUpdater, VarHandle, or Unsafe APIs.
     * Incorporates all atomic methods that can set the field (or array element) of an object,
     * such as `set`, `compareAndSet`, `compareAndExchange`, etc.
     *
     * @param receiver The object to which field (or array element) the value is set.
     * @param value The value written into [receiver] field (or array element).
     */
    public static void afterReflectiveSetter(Object receiver, Object value) {
        getEventTracker().afterReflectiveSetter(receiver, value);
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