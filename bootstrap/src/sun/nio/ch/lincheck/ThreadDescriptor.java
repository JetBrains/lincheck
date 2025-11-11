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

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;

/**
 * Represents a descriptor for a thread containing metadata requited for
 * the Lincheck's analysis of the thread execution.
 *
 * <br>
 *
 * The thread descriptor can be registered in the thread registry and then retrieved at any point,
 * using the static methods of this class:
 * - {@link ThreadDescriptor#setCurrentThreadDescriptor(ThreadDescriptor)}
 * - {@link ThreadDescriptor#setThreadDescriptor(Thread, ThreadDescriptor)}
 * - {@link ThreadDescriptor#getCurrentThreadDescriptor()}
 * - {@link ThreadDescriptor#getThreadDescriptor(Thread)}
 *
 * <br>
 *
 * The registry uses a combination of techniques to associate and retrieve thread descriptors with threads.
 * - For the instances of the {@link TestThread} class, that is threads usually created by the Lincheck itself,
 *   the descriptor is stored in one of the fields of the class.
 * - For the other subclasses of the {@link Thread} class, typically those created by the user code,
 *   the descriptor is stored in the thread-local variable.
 *
 * <p>
 * Moreover, the registry also maintains a global map
 * to store a mapping from each registered thread to its descriptor
 * in a thread-safe and garbage collection-friendly manner.
 * The thread-local variable is used for quick retrieval of the descriptor from within the thread itself,
 * while the global map is used to access a descriptor of the thread from another thread.
 *
 */
public class ThreadDescriptor {

    /**
     * The thread instance associated with this descriptor.
     */
    private final WeakReference<Thread> thread;

    /**
     * The {@code EventTracker} for tracking events in the model checking mode.
     */
    private WeakReference<EventTracker> eventTracker = new WeakReference<>(null);

    /**
     * Holds additional event-tracker-specific data associated with the thread.
     */
    private WeakReference<Object> eventTrackerData = null;

    /**
     * This flag indicates whether the Lincheck analysis is currently enabled in this thread.
     * <br><br>
     *
     * Note: the field is volatile because it could be modified from different threads:
     * another thread may reset it to false, signaling that the thread should is no longer be analyzed.
     */
    private volatile boolean isAnalysisEnabled = false;

    /**
     * This flag indicates whether the thread is currently executing injected code (e.g., method from {@code EventTracker}).
     * <br><br>
     *
     * This flag is used together with {@code isAnalysisEnabled}, they allow for proper synchronization in
     * case if Trace Recorder is used with {@code globalEventTracker != null}.
     * <br>
     *
     * When the Main thread wants to dump the trace, it needs to logically "finish" all currently running threads
     * so that they stop writing their trace points. For that Main marks some thread's {@code isAnalysisEnabled} flag as false,
     * and then waits until that thread's {@code isInsideInjectedCode} flag is also false,
     * afterward Main thread dumps the remaining tracepoints of thread that it waited for.
     * <br><br>
     *
     * Note: the field is volatile because it could be modified from different threads:
     * owner of this {@code ThreadDescriptor} and the Main thread of the test.
     */
    private volatile boolean isInsideInjectedCode = false;

    /**
     * Counter keeping track of the ignored section re-entrance depth.
     *
     * @see #inIgnoredSection
     */
    private int ignoredSectionDepth = 0;

    /**
     * Creates a new thread descriptor for the given thread.
     */
    public ThreadDescriptor(Thread thread) {
        if (thread == null) {
            throw new IllegalArgumentException("Thread must not be null");
        }
        this.thread = new WeakReference<>(thread);
    }

    /**
     * Retrieves the thread associated with this descriptor.
     */
    public Thread getThread() {
        return thread.get();
    }

    /**
     * Retrieves the thread-local {@code EventTracker} associated with this descriptor.
     */
    public EventTracker getEventTracker() {
        return eventTracker.get();
    }

    /**
     * Sets the thread-local {@code EventTracker} associated with this descriptor.
     */
    public void setEventTracker(EventTracker eventTracker) {
        this.eventTracker = new WeakReference<>(eventTracker);
    }

    /**
     * Retrieves the additional event-tracker-specific data associated with this descriptor.
     */
    public Object getEventTrackerData() {
        return eventTrackerData.get();
    }

    /**
     * Sets the additional event-tracker-specific data associated with this descriptor.
     */
    public void setEventTrackerData(Object eventTrackerData) {
        this.eventTrackerData = new WeakReference<>(eventTrackerData);
    }

    /**
     * Determines whether the thread is currently within an analyzed code section,
     * that is analysis was enabled and the thread is not currently within an ignored section.
     *
     * @return true if the thread is in a section of analyzed code, false otherwise.
     */
    public boolean inAnalyzedCode() {
        return isAnalysisEnabled && (ignoredSectionDepth == 0);
    }

    /**
     * @return `true` if analysis is enabled for this thread, `false` otherwise.
     */
    public boolean isAnalysisEnabled() {
        return isAnalysisEnabled;
    }

    /**
     * Enables analysis for this thread.
     */
    public void enableAnalysis() {
        isAnalysisEnabled = true;
    }

    /**
     * Disables analysis for this thread.
     */
    public void disableAnalysis() {
        isAnalysisEnabled = false;
    }

    /**
     * @return `true` if the thread is currently executing injected code, `false` otherwise.
     */
    public boolean isInsideInjectedCode() {
        return isInsideInjectedCode;
    }

    /**
     * Marks the thread as executing injected code.
     */
    public void enterInjectedCode() {
        isInsideInjectedCode = true;
    }

    /**
     * Marks the thread as no longer executing injected code.
     */
    public void leaveInjectedCode() {
        isInsideInjectedCode = false;
    }

    /**
     * Checks if this thread is currently within an ignored section.
     * Ignored section is used to temporarily disable tracking of all events.
     *
     * @return true if the thread is within an ignored section, false otherwise.
     */
    public boolean inIgnoredSection() {
        return ignoredSectionDepth > 0;
    }

    /**
     * Enters an ignored section in this thread.
     *
     * <p>
     * Ignored sections are re-entrant, meaning the thread may enter
     * the ignored section multiple times before exiting it.
     */
    public void enterIgnoredSection() {
        ignoredSectionDepth++;
    }

    /**
     * Exits an ignored section for this thread.
     *
     * <p>
     * Ignored sections are re-entrant, meaning the thread may need to exit
     * the section multiple times if previously it entered it multiple times.
     */
    public void leaveIgnoredSection() {
        ignoredSectionDepth--;
    }

    /**
     * Resets the ignored section re-entrance depth for this thread to 0 and returns the previous depth.
     *
     * <p>
     * This can be used to temporarily exit the current ignored section
     * and then later re-enter it with the same re-entrance depth,
     * using the corresponding restore method.
     *
     * @return the previous depth of the ignored section before it was reset.
     */
    public int saveAndResetIgnoredSectionDepth() {
        int depth = ignoredSectionDepth;
        ignoredSectionDepth = 0;
        return depth;
    }

    /**
     * Restores the ignored section re-entrance depth for this thread to the given value.
     *
     * @param depth the depth to which the ignored section re-entrance is being restored.
     */
    public void restoreIgnoredSectionDepth(int depth) {
        ignoredSectionDepth = depth;
    }

    /**
     * Thread local variable storing the thread descriptor for each thread.
     * <br>
     *
     * NOTE: as an optimization, for the `TestThread` instances,
     *   we avoid lookup via `ThreadLocal` by instead storing
     *   the thread descriptor in a field of the thread object itself.
     */
    private static final ThreadLocal<ThreadDescriptor> threadDescriptor =
        ThreadLocal.withInitial(() -> null);

    /**
     * A global map storing a thread descriptor for each thread object.
     * <br>
     *
     * The map should be concurrent, use identity hash code,
     * and do not prevent garbage collection of thread objects (i.e., use weak keys).
     * However, because there is no `ConcurrentWeakIdentityHashMap` out of the box in
     * the java standard library, we use a few tricks to achieve a similar effect.
     * <br>
     *
     * - We use `ConcurrentHashMap` to guarantee thread safety.
     * - We use identity hash codes of the thread objects as keys.
     * - To accommodate for potential hash code collisions, we store lists of thread descriptors as values.
     * - These lists use copy-on-write strategy when a new descriptor is added to avoid race conditions.
     * - Thread descriptors store weak references to thread objects, and thus do not prevent their garbage collection.
     */
    private static final Map<Thread, ThreadDescriptor> threadDescriptorsMap =
        Collections.synchronizedMap(new WeakIdentityHashMap<>());

    /**
     * Store a fast-path "root" descriptor.
     * Typically, the main thread or the test executor thread are used as the "root" thread.
     */
    private static ThreadDescriptor rootDescriptor = null;

    /**
     * Store the "root" thread.
     *
     * @see #rootDescriptor
     */
    private static Thread rootThread = null;

    /**
     * Retrieves the current thread's {@code ThreadDescriptor}.
     *
     * @return the {@code ThreadDescriptor} associated with the current thread
     *   or {@code null} if no descriptor is associated with it.
     */
    public static ThreadDescriptor getCurrentThreadDescriptor() {
        Thread thread = Thread.currentThread();
        if (thread == rootThread) {
            return rootDescriptor;
        }
        if (thread instanceof TestThread) {
            return ((TestThread) thread).descriptor;
        }
        return threadDescriptor.get();
    }

    /**
     * Sets the {@code ThreadDescriptor} for the current thread.
     *
     * @param descriptor the {@code ThreadDescriptor} to associate with the current thread
     *   or {@code null} if no descriptor is associated with it.
     */
    public static void setCurrentThreadDescriptor(ThreadDescriptor descriptor) {
        Thread thread = Thread.currentThread();
        if (thread instanceof TestThread) {
            TestThread testThread = (TestThread) thread;
            if (testThread.descriptor != null) throw DescriptorAlreadySetException(thread);
            testThread.descriptor = descriptor;
            return;
        }
        if (threadDescriptor.get() != null) throw DescriptorAlreadySetException(thread);
        threadDescriptor.set(descriptor);
    }

    /**
     * Retrieves the {@code ThreadDescriptor} associated with a given {@code Thread}.
     *
     * @param thread the thread for which the descriptor is being retrieved. Must not be null.
     * @return the {@code ThreadDescriptor} associated with the provided thread,
     *   or {@code null} if no descriptor is found.
     */
    public static ThreadDescriptor getThreadDescriptor(Thread thread) {
        if (thread instanceof TestThread) {
            return ((TestThread) thread).descriptor;
        }
        return threadDescriptorsMap.get(thread);
    }

    /**
     * Associates a {@code ThreadDescriptor} with a given {@code Thread}.
     *
     * @param thread the thread for which the {@code ThreadDescriptor} is being set. Must not be null.
     * @param descriptor the {@code ThreadDescriptor} to associate with the provided thread. Must not be null.
     * @throws IllegalStateException if another descriptor is already associated with the given thread.
     */
    public static void setThreadDescriptor(Thread thread, ThreadDescriptor descriptor) {
        if (thread instanceof TestThread) {
            ((TestThread) thread).descriptor = descriptor;
            return;
        }
        if (threadDescriptorsMap.containsKey(thread)) {
            throw DescriptorAlreadySetException(thread);
        }
        threadDescriptorsMap.put(thread, descriptor);
    }

    public static void setCurrentThreadAsRoot(ThreadDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("Thread descriptor must not be null");
        }
        if (rootThread != null) {
            throw new IllegalStateException("Root thread is already set");
        }

        Thread thread = Thread.currentThread();
        if (thread instanceof TestThread) {
            throw new IllegalStateException("Root thread cannot be TestThread");
        }

        rootThread = thread;
        rootDescriptor = descriptor;
    }

    public static ThreadDescriptor unsetRootThread() {
        if (rootThread == null) {
            throw new IllegalStateException("Root thread is not set");
        }
        if (rootThread != Thread.currentThread()) {
            throw new IllegalStateException("Root thread descriptor was set from another thread");
        }

        ThreadDescriptor descriptor = rootDescriptor;
        rootThread = null;
        rootDescriptor = null;
        return descriptor;
    }

    private static IllegalStateException DescriptorAlreadySetException(Thread thread) {
        String message = "Descriptor of thread " + thread.getName() + " is already set!";
        return new IllegalStateException(message);
    }

}
