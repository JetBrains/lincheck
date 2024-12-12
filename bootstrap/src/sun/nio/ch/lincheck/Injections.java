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
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Methods of this object are called from the instrumented code.
 */
public class Injections {

    // Special object to represent void method call result.
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

    /*
     * Thread local variable storing the thread descriptor for each thread.
     *
     * NOTE: as an optimization, for the `TestThread` instances,
     *   we avoid lookup via `ThreadLocal` by instead storing
     *   the thread descriptor in a field of the thread object itself.
     */
    private static final ThreadLocal<ThreadDescriptor> threadDescriptor =
        ThreadLocal.withInitial(() -> null);

    /*
     * A global map storing a thread descriptor for each thread object.
     *
     * The map should be concurrent, use identity hash code,
     * and do not prevent garbage collection of thread objects (i.e., use weak keys).
     * However, because there is no `ConcurrentWeakIdentityHashMap` out of the box in
     * the java standard library, we use a few tricks to achieve a similar effect.
     *
     * - We use `ConcurrentHashMap` to guarantee thread safety.
     * - We use identity hash codes of the thread objects as keys.
     * - To accommodate for potential hash code collisions, we store lists of thread descriptors as values.
     * - These lists use copy-on-write strategy when a new descriptor is added to avoid race conditions.
     * - Thread descriptors store weak references to thread objects, and thus do not prevent their garbage collection.
     *
     * TODO: although the garbage collection of thread objects is not prevented thanks to weak references,
     *   the hash map entries themself (key - identity hash code, and value - `ThreadDescriptor` object)
     *   would not be garbage collected, potentially creating a memory leak.
     *   However, we expect that typical programs would create only a bounded number of threads during
     *   their whole lifetime, and thus this map also would only occupy a bounded amount of memory.
     *   Still, it would be good to eventually implement proper periodic clean-up of the map
     *   to remove obsolete entries.
     */
    private static final ConcurrentHashMap<Integer, ArrayList<ThreadDescriptor>> threadDescriptorsMap =
        new ConcurrentHashMap<Integer, ArrayList<ThreadDescriptor>>();

    public static ThreadDescriptor getCurrentThreadDescriptor() {
        Thread thread = Thread.currentThread();
        if (thread instanceof TestThread) {
            return ((TestThread) thread).descriptor;
        }
        return threadDescriptor.get();
    }

    public static void setCurrentThreadDescriptor(ThreadDescriptor descriptor) {
        Thread thread = Thread.currentThread();
        if (thread instanceof TestThread) {
            return;
        }
        threadDescriptor.set(descriptor);
    }

    public static ThreadDescriptor getThreadDescriptor(Thread thread) {
        if (thread instanceof TestThread) {
            return ((TestThread) thread).descriptor;
        }
        int hashCode = System.identityHashCode(thread);
        ArrayList<ThreadDescriptor> threadDescriptors = threadDescriptorsMap.get(hashCode);
        if (threadDescriptors == null) return null;
        for (ThreadDescriptor descriptor : threadDescriptors) {
            if (descriptor.getThread() == thread) {
                return descriptor;
            }
        }
        return null;
    }

    public static void setThreadDescriptor(Thread thread, ThreadDescriptor descriptor) {
        if (thread instanceof TestThread) {
            ((TestThread) thread).descriptor = descriptor;
            return;
        }
        int hashCode = System.identityHashCode(thread);
        ArrayList<ThreadDescriptor> threadDescriptors = threadDescriptorsMap.get(hashCode);
        if (threadDescriptors == null) {
            threadDescriptors = new ArrayList<ThreadDescriptor>(1);
            threadDescriptors.add(descriptor);
        } else {
            // in an unlikely case of hash-code collision,
            // we create a full copy of the thread descriptors list
            // to avoid potential race conditions on reads/writes to the descriptors' list
            threadDescriptors = new ArrayList<ThreadDescriptor>(threadDescriptors);
        }
        threadDescriptors.add(descriptor);
        threadDescriptorsMap.put(hashCode, threadDescriptors);
    }

    public static EventTracker getEventTracker() {
        ThreadDescriptor descriptor = getCurrentThreadDescriptor();
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
        ThreadDescriptor descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) return;
        descriptor.enterTestingCode();
    }

    public static void leaveTestingCode() {
        ThreadDescriptor descriptor = getCurrentThreadDescriptor();
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
        ThreadDescriptor descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) return false;
        return descriptor.enterIgnoredSection();
    }

    /**
     * Leaves an ignored section for the current thread.
     */
    public static void leaveIgnoredSection() {
        ThreadDescriptor descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) return;
        descriptor.leaveIgnoredSection();
    }

    /**
     * Determines if the current thread is inside an ignored section.
     *
     * @return true if the current thread is inside an ignored section, false otherwise.
     */
    public static boolean inIgnoredSection() {
        ThreadDescriptor descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) return true;
        return descriptor.inIgnoredSection();
    }

    /**
     * Current thread reports that it is going to start a new child thread {@code forkedThread}.
     */
    public static void beforeThreadFork(Thread forkedThread) {
        // TestThread is handled separately
        if (forkedThread instanceof TestThread) return;
        ThreadDescriptor descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) {
            return;
        }
        EventTracker tracker = descriptor.getEventTracker();
        ThreadDescriptor forkedThreadDescriptor = new ThreadDescriptor(forkedThread);
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
        ThreadDescriptor descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) return;
        EventTracker tracker = descriptor.getEventTracker();
        tracker.afterThreadFork(forkedThread);
    }

    /**
     * Current thread entered its {@code run} method.
     */
    public static void beforeThreadStart() {
        Thread thread = Thread.currentThread();
        // TestThread is handled separately
        if (thread instanceof TestThread) return;
        ThreadDescriptor descriptor = getThreadDescriptor(thread);
        if (descriptor == null) {
            return;
        }
        setCurrentThreadDescriptor(descriptor);
        EventTracker tracker = descriptor.getEventTracker();
        tracker.beforeThreadStart();
    }

    /**
     * Current thread returned from its {@code run} method.
     */
    public static void afterThreadFinish() {
        Thread thread = Thread.currentThread();
        // TestThread is handled separately
        if (thread instanceof TestThread) return;
        ThreadDescriptor descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) return;
        EventTracker tracker = descriptor.getEventTracker();
        tracker.afterThreadFinish();
    }

    /**
     * Current thread successfully joined thread {@code t}.
     * <p>
     * <b>Does not support joins with time limits yet</b>.
     */
    public static void beforeThreadJoin(Thread t) {
        ThreadDescriptor descriptor = getCurrentThreadDescriptor();
        if (descriptor == null) return;
        EventTracker tracker = descriptor.getEventTracker();
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