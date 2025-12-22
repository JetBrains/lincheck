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
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;

/**
 * Methods of this object are called from the instrumented code.
 */
public class Injections {

    /**
     * Defines the available modes of event tracking.
     * <br>
     *
     * GLOBAL:
     *   In this mode, a single event tracker is stored in a global variable
     *   and is shared between all threads.
     *
     * <br>
     *
     * THREAD_LOCAL:
     *   In this mode, each thread has its own event tracker,
     *   and different threads can have different event trackers.
     *   When a new thread is started, it inherits the event tracker of its parent thread.
     *   The event tracker can also be registered for the given thread manually.
     */
    public enum EventTrackingMode {
        GLOBAL,
        THREAD_LOCAL,
    }

    /**
     * Stores the selected event tracking mode.
     * Null indicates event tracking is disabled.
     */
    private static volatile EventTrackingMode eventTrackingMode = null;

    /**
     * Stores the global event tracker.
     * Stores `null` if the event tracking mode is not set to `GLOBAL`.
     */
    private static volatile EventTracker globalEventTracker = null;

    /**
     * Retrieves the current event tracking mode.
     */
    public static EventTrackingMode getEventTrackingMode() {
        return eventTrackingMode;
    }

    /**
     * Retrieves the global event tracker.
     */
    public static EventTracker getGlobalEventTracker() {
        return globalEventTracker;
    }

    /**
     * Retrieves an instance of an event tracker based on the provided thread descriptor and
     * current event tracking mode.
     */
    public static EventTracker getEventTracker(ThreadDescriptor descriptor) {
        EventTrackingMode mode = eventTrackingMode;
        if (mode == EventTrackingMode.GLOBAL) {
            return globalEventTracker;
        }
        if (mode == EventTrackingMode.THREAD_LOCAL) {
            return (descriptor != null) ? descriptor.getEventTracker() : null;
        }
        return null;
    }

    /**
     * Retrieves an event tracker instance for the current thread based on the current event tracking mode.
     * <br>
     *
     * - In GLOBAL mode, the global event tracker is returned.
     *   In addition, if the current thread is not yet registered,
     *   registers the current thread, creating a new thread descriptor for it.
     * <br>
     *
     * - In THREAD_LOCAL mode, the thread-local event tracker is returned.
     * <br>
     *
     * This is an INTERNAL method, do not expose it in public API!
     *
     * @return the event tracker or `null` if event tracking is disabled or the current thread is not tracked.
     */
    private static EventTracker getEventTracker() {
        EventTrackingMode mode = eventTrackingMode;
        if (mode == EventTrackingMode.GLOBAL) {
            EventTracker eventTracker = globalEventTracker;
            if (eventTracker != null) {
                // Handle the case when all threads tracking was requested,
                // and we need to self-register the currently running thread by creating a new descriptor for it.
                registerRunningThread(eventTracker, Thread.currentThread());
            }
            return eventTracker;
        }
        if (mode == EventTrackingMode.THREAD_LOCAL) {
            ThreadDescriptor descriptor = ThreadDescriptor.getCurrentThreadDescriptor();
            return (descriptor != null) ? descriptor.getEventTracker() : null;
        }

        throw new IllegalStateException("Unexpected event tracking mode: " + mode);
    }

    /**
     * Retrieves the current thread's descriptor if it exists and the thread is in analyzed code.
     * <br>
     *
     * If the event tracking mode is GLOBAL, will automatically register the current thread,
     * creating a new thread descriptor for it.
     * <br>
     *
     * This is an INTERNAL method, do not expose it in public API!
     *
     * @return the event tracker or `null` if event tracking is disabled or the current thread is not tracked.
     */
    private static ThreadDescriptor getOrRegisterCurrentThreadDescriptor() {
        ThreadDescriptor descriptor = ThreadDescriptor.getCurrentThreadDescriptor();
        // Handle the case when global threads tracking was requested,
        // and we need to self-register the currently running thread by creating a new descriptor for it.
        if (descriptor == null && eventTrackingMode == EventTrackingMode.GLOBAL) {
            EventTracker eventTracker = globalEventTracker;
            if (eventTracker != null) {
                descriptor = registerRunningThread(eventTracker, Thread.currentThread());
            }
        }
        return descriptor;
    }

    /**
     * Retrieves the current thread's descriptor if it exists and the thread is in analyzed code.
     * <br>
     *
     * If the event tracking mode is GLOBAL, will automatically register the current thread,
     * creating a new thread descriptor for it.
     * <br>
     *
     * In general, this method SHOULD BE called from bytecode transformers when they
     * prepare the thread descriptor argument for passing it as a first argument into injections methods.
     * This procedure ensures that:
     *   - the thread descriptor will be registered automatically if necessary in GLOBAL mode;
     *   - if the code is not in analyzed code, the thread descriptor will be null,
     *     ensuring that the event tracker obtained through this descriptor will also be null,
     *     and thus the actual event tracking method will not be called.
     *  <br>
     *
     *  Alternatively, injections can wrap the injected code into `if (inAnalyzedCode()) { ... }` statements,
     *  using the `Injections::inAnalyzedCode()` method.
     *  This method checks if the current thread is currently inside
     *  an analyzed code, and if so, returns the current thread's descriptor,
     *  also performing automatic registration of the thread if necessary in GLOBAL mode.
     *  Inside the `then` branch of the conditional, it is fine to use simply
     *  `ThreadDescriptor::getCurrentThreadDescriptor()` to prepare the thread descriptor argument,
     *  because the thread will be registered at this point by the preceding `inAnalyzedCode()` method call.
     */
    public static ThreadDescriptor getCurrentThreadDescriptorIfInAnalyzedCode() {
        ThreadDescriptor descriptor = getOrRegisterCurrentThreadDescriptor();
        // If the thread is not registered, or it is not in analyzed code, return null.
        if (descriptor == null) return null;
        if (!descriptor.inAnalyzedCode()) return null;
        return descriptor;
    }

    /**
     * Enables event tracking for the specified mode using the provided event tracker.
     *
     * @param mode The mode of event tracking to be enabled.
     * @param eventTracker The event tracker instance.
     *                     Should be non-null for GLOBAL mode, and null for THREAD_LOCAL mode.
     * @throws IllegalStateException if event tracking is already enabled.
     */
    private static synchronized void enableEventTracking(EventTrackingMode mode, EventTracker eventTracker) {
        if (eventTrackingMode != null) {
            throw new IllegalStateException("Event tracking is already enabled");
        }
        if (mode == EventTrackingMode.GLOBAL) {
            globalEventTracker = eventTracker;
        }
        eventTrackingMode = mode;
    }

    /**
     * Disables event tracking.
     */
    private static synchronized void disableEventTracking() {
        EventTrackingMode mode = eventTrackingMode;
        if (mode == EventTrackingMode.GLOBAL) {
            globalEventTracker = null;
        }
        eventTrackingMode = null;
    }

    /**
     * Enables global event tracking.
     */
    public static void enableGlobalEventTracking(EventTracker eventTracker) {
        enableEventTracking(EventTrackingMode.GLOBAL, eventTracker);
    }

    /**
     * Disables global event tracking.
     */
    public static void disableGlobalEventTracking() {
        if (eventTrackingMode != EventTrackingMode.GLOBAL) {
            throw new IllegalStateException("Global event tracking is not enabled");
        }
        disableEventTracking();
    }

    /**
     * Enables thread-local event tracking.
     */
    public static void enableThreadLocalEventTracking() {
        enableEventTracking(EventTrackingMode.THREAD_LOCAL, null);
    }

    /**
     * Disables thread-local event tracking.
     */
    public static void disableThreadLocalEventTracking() {
        if (eventTrackingMode != EventTrackingMode.THREAD_LOCAL) {
            throw new IllegalStateException("Thread local event tracking is not enabled");
        }
        disableEventTracking();
    }

    /**
     * Enables analysis for the current thread.
    */
    public static void enableAnalysis() {
        ThreadDescriptor descriptor = ThreadDescriptor.getCurrentThreadDescriptor();
        if (descriptor == null) return;
        descriptor.enableAnalysis();
    }

    /**
     * Disables analysis for the current thread.
    */
    public static void disableAnalysis() {
        ThreadDescriptor descriptor = ThreadDescriptor.getCurrentThreadDescriptor();
        if (descriptor == null) return;
        descriptor.disableAnalysis();
    }

    /**
     * Enters an ignored section for the current thread.
     * A code inside the ignored section is not analyzed by the Lincheck.
     *
     * <p>
     * Does not affect the current thread if it is untracked
     * (that is not registered in the Lincheck strategy).
     */
    public static void enterIgnoredSection() {
        ThreadDescriptor descriptor = ThreadDescriptor.getCurrentThreadDescriptor();
        if (descriptor == null) return;
        descriptor.enterIgnoredSection();
    }

    /**
     * Leaves an ignored section for the current thread.
     * <p>
     *
     * Does not affect the current thread if it is untracked
     * (that is not registered in the Lincheck strategy).
     */
    public static void leaveIgnoredSection() {
        ThreadDescriptor descriptor = ThreadDescriptor.getCurrentThreadDescriptor();
        if (descriptor == null) return;
        descriptor.leaveIgnoredSection();
    }

    /**
     * Determines if the current thread is inside an analyzed code.
     *
     * @return true if the current thread is inside analyzed code, false otherwise.
     */
    public static boolean inAnalyzedCode() {
        // Some injections are wrapped into `if` statements with `inAnalyzedCode` checks.
        // Calling the `getCurrentThreadDescriptorIfInAnalyzedCode` method
        // (instead of just `getCurrentThreadDescriptor`)
        // will trigger thread descriptors creation for a yet-untracked thread.
        ThreadDescriptor descriptor = getCurrentThreadDescriptorIfInAnalyzedCode();
        return (descriptor != null);
    }

    /**
     * Registers a thread for event tracking, creating a new thread descriptor if one does not already exist.
     *
     * @param eventTracker the event tracker to associate with the thread.
     * @param thread the thread to register.
     * @return the thread descriptor associated with the given thread.
     */
    public static ThreadDescriptor registerThread(EventTracker eventTracker, Thread thread) {
        ThreadDescriptor descriptor = ThreadDescriptor.getThreadDescriptor(thread);
        if (descriptor == null) {
            descriptor = new ThreadDescriptor(thread);
            ThreadDescriptor.setThreadDescriptor(thread, descriptor);
        }
        if (eventTrackingMode == EventTrackingMode.THREAD_LOCAL) {
            descriptor.setEventTracker(eventTracker);
        }
        return descriptor;
    }

    /**
     * Unregisters a thread from event tracking.
     * <br>
     *
     * Only applicable in THREAD_LOCAL mode, in GLOBAL mode this is effectively a no-op.
     * Because in GLOBAL mode all threads are tracked
     * from the moment the tracking is enabled till the time it is disabled.
     */
    public static void unregisterThread(Thread thread) {
        ThreadDescriptor descriptor = ThreadDescriptor.getThreadDescriptor(thread);
        if (descriptor == null) return;

        // We only reset the event tracker but keep the thread in the global map,
        // so later it can be re-registered if requested without the need to update the map.
        if (eventTrackingMode == EventTrackingMode.THREAD_LOCAL) {
            descriptor.setEventTracker(null);
        }
    }

    /**
     * Registers the current thread for event tracking, creating a new thread descriptor if one does not already exist.
     *
     * @param eventTracker the event tracker to associate with the thread.
     * @return the thread descriptor associated with the given thread.
     */
    public static ThreadDescriptor registerCurrentThread(EventTracker eventTracker) {
        Thread thread = Thread.currentThread();
        ThreadDescriptor descriptor = registerThread(eventTracker, thread);
        ThreadDescriptor.setCurrentThreadDescriptor(descriptor);
        return descriptor;
    }

    /**
     * Registers an already running thread for event tracking, creating a new thread descriptor for it.
     * <br>
     *
     * This is an INTERNAL method, do not expose it in public API!
     * This method is used only to register the currently running thread in GLOBAL mode,
     * in case when this thread was started earlier than event tracking was enabled.
     *
     * @param eventTracker the event tracker to associate with the thread.
     * @return the thread descriptor associated with the given thread.
     */
    private static ThreadDescriptor registerRunningThread(EventTracker eventTracker, Thread thread) {
        ThreadDescriptor descriptor = registerThread(eventTracker, thread);
        ThreadDescriptor.setCurrentThreadDescriptor(descriptor);
        eventTracker.registerRunningThread(descriptor, thread);
        return descriptor;
    }

    /**
     * Called from instrumented code before {@code Thread::start} method.
     *
     * @param startingThread the thread which is going to be started.
     */
    public static void beforeThreadStart(Thread startingThread) {
        // TestThread is handled separately
        if (startingThread instanceof TestThread) return;
        // If thread is started return immediately, as in this case, JVM will throw an `IllegalThreadStateException`
        if (startingThread.getState() != Thread.State.NEW) return;

        ThreadDescriptor descriptor = getCurrentThreadDescriptorIfInAnalyzedCode();
        EventTracker eventTracker = getEventTracker(descriptor);
        if (eventTracker == null || descriptor == null) return;

        /* We need to prepare a thread descriptor for the starting thread.
         * We create it here and store it into the global thread descriptor map
         * via `ThreadDescriptor.setThreadDescriptor`.
         *
         * Later, when the thread starts its execution and enters its `run()` method
         * (see `beforeThreadRun` method below),
         * it will retrieve its thread descriptor from the global map
         * and save it as its own thread-local descriptor (for faster subsequent accesses).
         */

        ThreadDescriptor startingThreadDescriptor = new ThreadDescriptor(startingThread);
        if (eventTrackingMode == EventTrackingMode.THREAD_LOCAL) {
            startingThreadDescriptor.setEventTracker(eventTracker);
        }

        /* Method `setThreadDescriptor` calls methods of `ConcurrentHashMap` (instrumented class),
         * and at this point the calling thread can have the event tracker set,
         * so we need to wrap the call into an ignored section.
         *
         * Note that other thread events tracking methods don't need to wrap anything
         * into an ignored section, because when they are called, either
         *   (1) thread descriptor (and thus event tracker) of the thread is not installed yet, or
         *   (2) they do not call any instrumented methods themselves.
         */
        descriptor.enterIgnoredSection();
        ThreadDescriptor.setThreadDescriptor(startingThread, startingThreadDescriptor);
        descriptor.leaveIgnoredSection();
        /*
         * End of the ignored section, the rest should be
         * wrapped into an ignored section by the event tracker itself, if necessary.
         */

        eventTracker.beforeThreadStart(descriptor, startingThread, startingThreadDescriptor);
    }

    /**
     * Called from instrumented code at the beginning of {@code Thread::run} method.
     */
    public static void beforeThreadRun() {
        Thread thread = Thread.currentThread();
        // TestThread is handled separately
        if (thread instanceof TestThread) return;

        /* We need to retrieve the thread descriptor from the global map (if it was set)
         * and store it into current threads' own thread-local variable (for faster subsequent accesses).
         * If the thread descriptor was not set, we do nothing,
         * as it means the currently running thread is not tracked.
         */
        ThreadDescriptor descriptor = ThreadDescriptor.getThreadDescriptor(thread);
        if (descriptor == null) return;
        // Store the thread descriptor into the current thread's thread-local variable.
        ThreadDescriptor.setCurrentThreadDescriptor(descriptor);

        EventTracker eventTracker = getEventTracker(descriptor);
        if (eventTracker == null) return;

        eventTracker.beforeThreadRun(descriptor);
    }

    /**
     * Called from instrumented code at the end of {@code Thread::run} method,
     * in case of normal exit method exit.
     */
    public static void afterThreadRunReturn() {
        Thread thread = Thread.currentThread();
        // TestThread is handled separately
        if (thread instanceof TestThread) return;

        /* Here it is fine to retrieve the thread descriptor from the current thread's thread-local variable.
         *
         * There are three cases, either:
         * - the thread-local descriptor was already set by the `beforeThreadRun` method
         *   in case when the thread was registered from the start;
         *
         * - the thread-local descriptor was already set by the `registerRunningThread` method
         *   in case when the thread was registered on-the-fly in the middle of its execution;
         *
         * - the thread-local descriptor was not set, in which case we can just do nothing.
         */
        ThreadDescriptor descriptor = ThreadDescriptor.getCurrentThreadDescriptor();
        EventTracker eventTracker = getEventTracker(descriptor);
        if (descriptor == null || eventTracker == null) return;

        eventTracker.afterThreadRunReturn(descriptor);
    }

    /**
     * Called from instrumented code at the end of {@code Thread::run} method,
     * in case of exceptional exit.
     *
     * @param exception the exception that was thrown in the thread.
     */
    public static void afterThreadRunException(Throwable exception) {
        Thread thread = Thread.currentThread();
        // TestThread is handled separately
        if (thread instanceof TestThread) return;

        /* Here it is fine to retrieve the thread descriptor from the current thread's thread-local variable,
         * for the same reason as in the `afterThreadRunReturn` method.
         */
        ThreadDescriptor descriptor = ThreadDescriptor.getCurrentThreadDescriptor();
        EventTracker eventTracker = getEventTracker(descriptor);
        if (descriptor == null || eventTracker == null) return;

        eventTracker.afterThreadRunException(descriptor, exception);
    }

    /**
     * Called from instrumented code instead of {@code Thread::join} method.
     */
    public static void onThreadJoin(Thread thread, boolean withTimeout) {
        ThreadDescriptor descriptor = getCurrentThreadDescriptorIfInAnalyzedCode();
        EventTracker eventTracker = getEventTracker(descriptor);
        if (eventTracker == null || descriptor == null) return;

        eventTracker.onThreadJoin(descriptor, thread, withTimeout);
    }

    /**
     * See [org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy.lock] for the explanation
     * why we have beforeLock method.
     *
     * Creates a trace point which is used in the subsequent [beforeEvent] method call.
     */
    public static void beforeLock(ThreadDescriptor descriptor, int codeLocation) {
        EventTracker tracker = getEventTracker(descriptor);
        tracker.beforeLock(descriptor, codeLocation);
    }

    /**
     * Called from instrumented code instead of the MONITORENTER instruction,
     * but after [beforeEvent] method call, if the plugin is enabled.
     */
    public static void lock(ThreadDescriptor descriptor, Object monitor) {
        EventTracker tracker = getEventTracker(descriptor);
        tracker.lock(descriptor, monitor);
    }

    /**
     * Called from instrumented code instead of the MONITOREXIT instruction.
     */
    public static void unlock(ThreadDescriptor descriptor, int codeLocation, Object monitor) {
        EventTracker tracker = getEventTracker(descriptor);
        tracker.unlock(descriptor, codeLocation, monitor);
    }

    /**
     * See [org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy.park] for the explanation
     * why we have beforePark method.
     *
     * Creates a trace point which is used in the subsequent [beforeEvent] method call.
     */
    public static void beforePark(ThreadDescriptor descriptor, int codeLocation) {
        EventTracker tracker = getEventTracker(descriptor);
        tracker.beforePark(descriptor, codeLocation);
    }

    /**
     * Called from the instrumented code instead of `Unsafe.park`.
     */
    public static void park(ThreadDescriptor descriptor, int codeLocation) {
        EventTracker tracker = getEventTracker(descriptor);
        tracker.park(descriptor, codeLocation);
    }

    /**
     * Called from the instrumented code instead of `Unsafe.unpark`.
     */
    public static void unpark(ThreadDescriptor descriptor, int codeLocation, Thread thread) {
        EventTracker tracker = getEventTracker(descriptor);
        tracker.unpark(descriptor, codeLocation, thread);
    }

    /**
     * See [org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy.wait] for the explanation
     * why we have beforeWait method.
     *
     * Creates a trace point which is used in the subsequent [beforeEvent] method call.
     */
    public static void beforeWait(ThreadDescriptor descriptor, int codeLocation) {
        EventTracker tracker = getEventTracker(descriptor);
        tracker.beforeWait(descriptor, codeLocation);
    }

    /**
     * Called from the instrumented code instead of [Object.wait],
     * but after [beforeEvent] method call, if the plugin is enabled.
     */
    public static void wait(ThreadDescriptor descriptor, Object monitor) {
        EventTracker tracker = getEventTracker(descriptor);
        tracker.wait(descriptor, monitor, false);
    }


    /**
     * Called from the instrumented code instead of [Object.wait] with timeout,
     * but after [beforeEvent] method call, if the plugin is enabled.
     */
    public static void waitWithTimeout(ThreadDescriptor descriptor, Object monitor) {
        EventTracker tracker = getEventTracker(descriptor);
        tracker.wait(descriptor, monitor, true);
    }


    /**
     * Called from the instrumented code instead of [Object.notify].
     */
    public static void notify(ThreadDescriptor descriptor, int codeLocation, Object monitor) {
        EventTracker tracker = getEventTracker(descriptor);
        tracker.notify(descriptor, codeLocation, monitor, false);
    }

    /**
     * Called from the instrumented code instead of [Object.notify].
     */
    public static void notifyAll(ThreadDescriptor descriptor, int codeLocation, Object monitor) {
        EventTracker tracker = getEventTracker(descriptor);
        tracker.notify(descriptor, codeLocation, monitor, true);
    }

    /**
     * Called from the instrumented code replacing random `int` generation with a deterministic random value.
     */
    public static int nextInt() {
        return getEventTracker().randomNextInt();
    }

    /**
     * Called from the instrumented code to get a random instance that is deterministic and controlled by Lincheck.
     */
    public static InjectedRandom deterministicRandom() {
        return getEventTracker().getThreadLocalRandom();
    }

    /**
     * Called from the instrumented code before each field read.
     */
    public static void beforeReadField(ThreadDescriptor descriptor, int codeLocation, Object obj, int fieldId) {
        EventTracker eventTracker = getEventTracker(descriptor);
        if (descriptor == null || eventTracker == null) return;
        eventTracker.beforeReadField(descriptor, codeLocation, obj, fieldId);
    }

    /**
     * Called from the instrumented code before any array cell read.
     */
    public static void beforeReadArray(ThreadDescriptor descriptor, int codeLocation, Object array, int index) {
        EventTracker eventTracker = getEventTracker(descriptor);
        if (descriptor == null || eventTracker == null) return;
        eventTracker.beforeReadArrayElement(descriptor, codeLocation, array, index);
    }

    /**
     * Called from the instrumented code after each local variable read.
     */
    public static void afterLocalRead(ThreadDescriptor descriptor, int codeLocation, int variableId, Object value) {
        EventTracker eventTracker = getEventTracker(descriptor);
        if (descriptor == null || eventTracker == null) return;
        eventTracker.afterLocalRead(descriptor, codeLocation, variableId, value);
    }

    /**
     * Called from the instrumented code after each local variable write.
     */
    public static void afterLocalWrite(ThreadDescriptor descriptor, int codeLocation, int variableId, Object value) {
        EventTracker eventTracker = getEventTracker(descriptor);
        if (descriptor == null || eventTracker == null) return;
        eventTracker.afterLocalWrite(descriptor, codeLocation, variableId, value);
    }

    /**
     * Called from the instrumented code after each field read (final field reads can be ignored here).
     */
    public static void afterReadField(ThreadDescriptor descriptor, int codeLocation, Object obj, int fieldId, Object value) {
        EventTracker eventTracker = getEventTracker(descriptor);
        if (descriptor == null || eventTracker == null) return;
        eventTracker.afterReadField(descriptor, codeLocation, obj, fieldId, value);
    }

    /**
     * Called from the instrumented code after each array read.
     */
    public static void afterReadArray(ThreadDescriptor descriptor, int codeLocation, Object array, int index, Object value) {
        EventTracker eventTracker = getEventTracker(descriptor);
        if (descriptor == null || eventTracker == null) return;
        eventTracker.afterReadArrayElement(descriptor, codeLocation, array, index, value);
    }

    /**
     * Called from the instrumented code before each field write.
     */
    public static void beforeWriteField(ThreadDescriptor descriptor, int codeLocation, Object obj, Object value, int fieldId) {
        EventTracker eventTracker = getEventTracker(descriptor);
        if (descriptor == null || eventTracker == null) return;
        eventTracker.beforeWriteField(descriptor, codeLocation, obj, value, fieldId);
    }

    /**
     * Called from the instrumented code before any array cell write.
     */
    public static void beforeWriteArray(ThreadDescriptor descriptor, int codeLocation, Object array, int index, Object value) {
        EventTracker eventTracker = getEventTracker(descriptor);
        if (descriptor == null || eventTracker == null) return;
        eventTracker.beforeWriteArrayElement(descriptor, codeLocation, array, index, value);
    }

    /**
     * Called from the instrumented code before any write operation.
     */
    public static void afterWrite(ThreadDescriptor descriptor) {
        EventTracker eventTracker = getEventTracker(descriptor);
        if (descriptor == null || eventTracker == null) return;
        eventTracker.afterWrite(descriptor);
    }

    /**
     * Called from the instrumented code before any method call.
     *
     * @param receiver is `null` for public static methods.
     * @return Deterministic call descriptor or null.
     */
    public static void onMethodCall(ThreadDescriptor descriptor, int codeLocation, int methodId, Object receiver, Object[] params, ResultInterceptor interceptor) {
        EventTracker eventTracker = getEventTracker(descriptor);
        if (descriptor == null || eventTracker == null) return;
        eventTracker.onMethodCall(descriptor, codeLocation, methodId, receiver, params, interceptor);
    }

    /**
     * Called from the instrumented code after any method successful call, i.e., without any exception.
     *
     * @param result The call result.
     */
    public static void onMethodCallReturn(ThreadDescriptor threadDescriptor, int methodId, Object receiver, Object[] params, Object result, ResultInterceptor interceptor) {
        EventTracker eventTracker = getEventTracker(threadDescriptor);
        if (eventTracker == null || threadDescriptor == null) return;
        eventTracker.onMethodCallReturn(threadDescriptor, methodId, receiver, params, result, interceptor);
    }

    /**
     * Called from the instrumented code after any method that returns void successful call, i.e., without any exception.
     */
    public static void onMethodCallReturnVoid(ThreadDescriptor threadDescriptor, int methodId, Object receiver, Object[] params, ResultInterceptor interceptor) {
        onMethodCallReturn(threadDescriptor, methodId, receiver, params, VOID_RESULT, interceptor);
    }

    /**
     * Called from the instrumented code after any method call threw an exception
     *
     * @param exception Thrown exception.
     * @return The potentially modified {@code t}.
     */
    public static void onMethodCallException(ThreadDescriptor threadDescriptor, int methodId, Object receiver, Object[] params, Throwable exception, ResultInterceptor interceptor) {
        EventTracker eventTracker = getEventTracker(threadDescriptor);
        if (eventTracker == null || threadDescriptor == null) return;
        eventTracker.onMethodCallException(threadDescriptor, methodId, receiver, params, exception, interceptor);
    }

    public static ResultInterceptor createResultInterceptor() {
        return new ResultInterceptor();
    }

    public static boolean isResultOrExceptionIntercepted(ResultInterceptor resultInterceptor) {
        // No result intercepted if there is no result interceptor in the first place
        if (resultInterceptor == null) return false;
        return resultInterceptor.isResultIntercepted() || resultInterceptor.isExceptionIntercepted();
    }

    /**
     * Retrieves a value from the provided ResultInterceptor object or throws an exception if the result contains it.
     *
     * @param resultInterceptor the ResultInterceptor object from which the value is to be retrieved. Assumes that it is not null
     * @return the value contained in the BootstrapResult object
     * @throws Throwable if the result contains it
     */
    public static Object getOrThrowInterceptedResult(ResultInterceptor resultInterceptor) throws Throwable {
        if (resultInterceptor.isExceptionIntercepted()) {
            throw resultInterceptor.getInterceptedException();
        }
        return resultInterceptor.getInterceptedResult();
    }


    /**
     * Called from the instrumented code before NEW instruction
     */
    public static void beforeNewObjectCreation(ThreadDescriptor descriptor, String className) {
        EventTracker eventTracker = getEventTracker(descriptor);
        eventTracker.beforeNewObjectCreation(descriptor, className);
    }

    /**
     * Called from the instrumented code after any object is created
     */
    public static void afterNewObjectCreation(ThreadDescriptor descriptor, Object obj) {
        EventTracker eventTracker = getEventTracker(descriptor);
        eventTracker.afterNewObjectCreation(descriptor, obj);
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
     * Retrieves the next object id, used for identity hash code substitution, and then advances it by one.
     */
    public static long getNextTraceDebuggerEventTrackerId(TraceDebuggerTracker tracker) {
        return getEventTracker().getNextTraceDebuggerEventTrackerId(tracker);
    }

    /**
     * Advances the current object id with the delta, associated with the old id {@code oldId},
     * previously received with {@code getNextObjectId}.
     * <p>
     * If for the given {@code oldId} there is no saved {@code newId},
     * the function saves the current object id and associates it with the {@code oldId}.
     * On subsequent re-runs, when for the given {@code oldId} there exists a saved {@code newId},
     * the function sets the counter to the {@code newId}.
     * <p>
     * This function is typically used to account for some cached computations:
     * on the first run the actual computation is performed, and its result is cached,
     * and on subsequent runs the cached value is re-used.
     * One example of such a situation is the {@code invokedynamic} instruction.
     * <p>
     * In such cases, on the first run, the performed computation may allocate more objects,
     * assigning more object ids to them.
     * On subsequent runs, however, these objects will not be allocated, and thus the object ids numbering may vary.
     * To account for this, before the first invocation of the cached computation,
     * the last allocated object id {@code oldId} can be saved, and after the computation,
     * the new last object id can be associated with it via a call {@code advanceCurrentObjectId(oldId)}.
     * On subsequent re-runs, the cached computation will be skipped, but the
     * current object id will still be advanced by the required delta via a call to {@code advanceCurrentObjectId(oldId)}.
     */
    public static void advanceCurrentTraceDebuggerEventTrackerId(TraceDebuggerTracker tracker, long oldId) {
        getEventTracker().advanceCurrentTraceDebuggerEventTrackerId(tracker, oldId);
    }


    /**
     * Replacement for ASM {@code Handle} type, not presented in the bootstrap module.
     */
    public static class HandlePojo {
        public final int tag;
        public final String owner;
        public final String name;
        public final String desc;
        public final boolean isInterface;
        public HandlePojo(int tag, String owner, String name, String desc, boolean isInterface) {
            this.tag = tag;
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.isInterface = isInterface;
        }
    }

    /**
     * Retrieves a cached CallSite associated with the provided invoke dynamic parameters.
     *
     * @param name the name of the method to be invoked dynamically.
     * @param descriptor the method descriptor specifying the method signature.
     * @param bootstrapMethodHandle the bootstrap method handle used to resolve the call site.
     * @param bootstrapMethodArguments the additional arguments provided to the bootstrap method handle.
     * @return the cached CallSite corresponding to the provided dynamic invocation parameters.
     */
    public static CallSite getCachedInvokeDynamicCallSite(
            String name,
            String descriptor,
            HandlePojo bootstrapMethodHandle,
            Object[] bootstrapMethodArguments
    ) {
        return getEventTracker().getCachedInvokeDynamicCallSite(
                name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments
        );
    }

    /**
     * Caches an invokedynamic call site for later reuse.
     *
     * @param name the name of the invokedynamic instruction.
     * @param descriptor the method descriptor associated with the invokedynamic instruction.
     * @param bootstrapMethodHandle the bootstrap method handle used to link the invokedynamic instruction.
     * @param bootstrapMethodArguments the arguments passed to the bootstrap method.
     * @param callSite the resolved call site to be cached.
     */
    public static void putCachedInvokeDynamicCallSite(
            String name,
            String descriptor,
            HandlePojo bootstrapMethodHandle,
            Object[] bootstrapMethodArguments,
            CallSite callSite
    ) {
        getEventTracker().cacheInvokeDynamicCallSite(
                name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments, callSite
        );
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

    /**
     * Called from the instrumented code before any kotlin inlined method call.
     */
    public static void onInlineMethodCall(ThreadDescriptor descriptor, int codeLocation, int methodId, Object owner) {
        EventTracker eventTracker = getEventTracker(descriptor);
        eventTracker.onInlineMethodCall(descriptor, codeLocation, methodId, owner);
    }

    /**
     * Called from the instrumented code after any kotlin inline method successful call, i.e., without any exception.
     */
    public static void onInlineMethodCallReturn(ThreadDescriptor descriptor, int methodId) {
        EventTracker eventTracker = getEventTracker(descriptor);
        eventTracker.onInlineMethodCallReturn(descriptor, methodId);
    }

    /**
     * Called from the instrumented code after any kotlin inline method throws exception
     */
    public static void onInlineMethodCallException(ThreadDescriptor descriptor, int methodId, Throwable t) {
        EventTracker eventTracker = getEventTracker(descriptor);
        eventTracker.onInlineMethodCallException(descriptor, methodId, t);
    }

    /**
     * Called at the beginning of every loop iteration (including the first one).
     */
    public static void onLoopIteration(ThreadDescriptor descriptor, int codeLocation, int loopId) {
        EventTracker eventTracker = getEventTracker(descriptor);
        if (eventTracker == null || descriptor == null) return;
        eventTracker.onLoopIteration(descriptor, codeLocation, loopId);
    }

    /**
     * Called on a normal (non-exceptional) exit from a loop body and
     * at an exception handler entry that is reachable from within a loop body and lies outside it.
     *
     * @param exception the exception that was thrown during the loop exit in case of exceptional exit, null otherwise.
     * @param isReachableFromOutsideLoop true if the handler can also be reached from outside the loop body;
     *   false if it is exclusive to the loop body.
     */
    public static void afterLoopExit(ThreadDescriptor descriptor, int codeLocation, int loopId, Throwable exception, boolean isReachableFromOutsideLoop) {
        EventTracker eventTracker = getEventTracker(descriptor);
        if (eventTracker == null || descriptor == null) return;
        eventTracker.afterLoopExit(descriptor, codeLocation, loopId, exception, isReachableFromOutsideLoop);
    }

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

    // == Methods required for the IDEA Plugin integration ==

    /**
     * Mark value of {@link #requestedBeforeEventId} field to skip calls to {@link #beforeEvent}.
     */
    private static final int DO_NOT_TRIGGER_BEFORE_EVENT = -1;

    /**
     * Mark value of {@link #requestedBeforeEventId} field to always call {@link #beforeEvent}.
     */
    private static final int STOP_AT_NEXT_EVENT_ID = -2;

    /**
     * This field is updated from the debugger to request a specific event ID.
     * <p>
     * Initially not triggered, then it is updated from the debugger to the desired event ID.
     */
    @SuppressWarnings({"FieldMayBeFinal", "FieldCanBeLocal"})
    private static int requestedBeforeEventId = DO_NOT_TRIGGER_BEFORE_EVENT;

    /**
     * This field is used by the debugger to have a fast source of current event ID.
     */
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private static int currentEventId = -1;

    /**
     * Determines whether a {@link #beforeEvent} hook should be invoked.
     * Queries the event tracker to determine if it currently requests {@link #beforeEvent} processing.
     *
     * @return true if the {@link #beforeEvent} method should be invoked before the event, false otherwise
     */
    public static boolean shouldInvokeBeforeEvent() {
        ThreadDescriptor descriptor = getCurrentThreadDescriptorIfInAnalyzedCode();
        EventTracker eventTracker = getEventTracker(descriptor);
        if (descriptor == null || eventTracker == null) return false;

        return eventTracker.shouldInvokeBeforeEvent();
    }

    /**
     * This method is introduced for performance purposes.
     * Instead of calling {@link #beforeEvent} on every event, we call it only at the requested point.
     * It greatly improves the performance as the debugger installs a breakpoint into {@link #beforeEvent} method,
     * so each call leads to unnecessary lincheck/debugger communication.
     *
     * @param eventId current id value.
     * @return whether the current event id is equal to the requested id.
     */
    public static boolean isBeforeEventRequested(int eventId) {
        int requestedId = requestedBeforeEventId;
        return requestedId == STOP_AT_NEXT_EVENT_ID || requestedId == eventId;
    }

    /**
     * This method is invoked before the event with specified id occurs.
     * <p>
     *
     * IDEA plugin installs a breakpoint on this method
     * to stop the debugger right before the specified event.
     *
     * @param eventId the unique identifier of the event.
     * @param type type of the next event. Used only for debug purposes.
     */
    public static void beforeEvent(int eventId, String type) {
        getEventTracker().beforeEvent(eventId, type);
    }

    /**
     * Requests current event ID from the event tracker and sets it into {@link #currentEventId}.
     *
     * @param type type of the next event. Used only for debug purposes.
     */
    public static int getCurrentEventId(String type) {
        int eventId = getEventTracker().getCurrentEventId();
        currentEventId = eventId;
        return eventId;
    }

    // == Methods for bypassing JDK-8 specific restrictions on MethodHandles.Lookup ==

    /**
     * Attempts to retrieve the Class object associated with the given class name.
     * If the class is not found, it returns null instead of throwing an exception.
     *
     * @param name the fully qualified name of the desired class
     * @return the Class object for the class with the specified name, 
     *         or null if the class cannot be located
     */
    public static Class<?> getClassForNameOrNull(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Constructor<MethodHandles.Lookup> lookUpPrivateConstructor = null;

    /**
     * Provides a trusted MethodHandles.Lookup for the given class,
     * bypassing JDK 8-specific restrictions written in the {@link MethodHandles} implementation.
     * <p> 
     * In JDK 8 there are additional <a href="https://github.com/frohoff/jdk8u-jdk/blob/da0da73ab82ed714dc5be94acd2f0d00fbdfe2e9/src/share/classes/java/lang/invoke/MethodHandles.java#L681">restrictions</a>,
     * breaking {@code MethodHandles.lookup()} from being called from the Java standard library.
     * Calling it is necessary for {@code invokedynamic} handling in the trace debugger.
     * <p> 
     * The function instead calls a private constructor with the TRUSTED mode via reflection.
     *
     * @param callerClass the class for which the trusted {@link MethodHandles.Lookup} is to be created
     * @return a trusted {@link MethodHandles.Lookup} instance for the specified class
     * @throws Exception if an error occurs while creating the trusted lookup instance
     */
    public static MethodHandles.Lookup trustedLookup(Class<?> callerClass) throws Exception {
        if (lookUpPrivateConstructor == null) {
            Constructor<MethodHandles.Lookup> declaredConstructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
            declaredConstructor.setAccessible(true);
            lookUpPrivateConstructor = declaredConstructor;
        }
        return lookUpPrivateConstructor.newInstance(callerClass, -1);
    }

    // == Utilities ==

    // Special object to represent void method call result.
    public static final Object VOID_RESULT = new Object();
}
