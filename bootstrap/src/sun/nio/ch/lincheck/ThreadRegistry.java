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

import java.util.Map;
import java.util.Collections;

/**
 * The thread registry class provides mechanisms to manage and retrieve thread descriptors
 * (see {@link ThreadDescriptor}) associated with threads within the Lincheck framework.
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
 * <p>
 * This class provides only static members and is not meant to be instantiated.
 */
public final class ThreadRegistry {

    // forbid instance creation
    private ThreadRegistry() {}

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
     * Retrieves the current thread's {@code ThreadDescriptor}.
     *
     * @return the {@code ThreadDescriptor} associated with the current thread
     *   or {@code null} if no descriptor is associated with it.
     */
    public static ThreadDescriptor getCurrentThreadDescriptor() {
        Thread thread = Thread.currentThread();
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
            // TODO: fixme
            return;
        }
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
            String message = "Descriptor of thread " + thread.getName() + " is already set!";
            throw new IllegalStateException(message);
        }
        threadDescriptorsMap.put(thread, descriptor);
    }
}
