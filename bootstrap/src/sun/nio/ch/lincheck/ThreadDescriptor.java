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
 * Represents a descriptor for a thread, containing metadata requited for
 * the Lincheck analysis of the thread execution.
 *
 * <p>
 * The thread descriptor can be registered in the thread registry and then retrieved at any point,
 * using the static methods of this class:
 * - {@link ThreadDescriptor#setCurrentThreadDescriptor(ThreadDescriptor)}
 * - {@link ThreadDescriptor#setThreadDescriptor(Thread, ThreadDescriptor)}
 * - {@link ThreadDescriptor#getCurrentThreadDescriptor()}
 * - {@link ThreadDescriptor#getThreadDescriptor(Thread)}
 *
 * <p>
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
     */
    private boolean isAnalysisEnabled = false;

    /**
     * Counter keeping track of the ignored section re-entrance depth.
     *
     * @see #inIgnoredSection
     */
    private int ignoredSectionDepth = 0;

    public ThreadDescriptor(Thread thread) {
        if (thread == null) {
            throw new IllegalArgumentException("Thread must not be null");
        }
        this.thread = new WeakReference<>(thread);
    }

    public Thread getThread() {
        return thread.get();
    }

    public EventTracker getEventTracker() {
        return eventTracker.get();
    }

    public void setEventTracker(EventTracker eventTracker) {
        this.eventTracker = new WeakReference<>(eventTracker);
    }

    public Object getEventTrackerData() {
        return eventTrackerData.get();
    }

    public void setEventTrackerData(Object eventTrackerData) {
        this.eventTrackerData = new WeakReference<>(eventTrackerData);
    }

    public void setAsRootDescriptor() {
        if (rootThread != null) {
            throw new IllegalStateException("Root Thread Descriptor is already set");
        }
        if (Thread.currentThread() instanceof TestThread) {
            throw new IllegalStateException("Root Thread cannot be TestThread");
        }
        rootThread = Thread.currentThread();
        rootDescriptor = this;
    }

    public void removeAsRootDescriptor() {
        if (rootThread == null) {
            throw new IllegalStateException("Root Thread Descriptor is not set");
        }
        if (rootThread != Thread.currentThread()) {
            throw new IllegalStateException("Root Thread Descriptor was set from other thread");
        }
        if (rootDescriptor != this) {
            throw new IllegalStateException("Root Thread Descriptor was set to oher descriptor");
        }
        rootThread = null;
        rootDescriptor = null;
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
     */
    private static final Map<Thread, ThreadDescriptor> threadDescriptorsMap =
        Collections.synchronizedMap(new WeakIdentityHashMap<>());

    /**
     * Store fast-path descriptor for "main" thread
     */
    private static ThreadDescriptor rootDescriptor = null;
    /**
     * Store "main" thread for
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

    private static IllegalStateException DescriptorAlreadySetException(Thread thread) {
        String message = "Descriptor of thread " + thread.getName() + " is already set!";
        return new IllegalStateException(message);
    }

}
