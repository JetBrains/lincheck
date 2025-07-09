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

    // Special object to represent void method call result.
    public static final Object VOID_RESULT = new Object();

    // Used in the verification phase to store a suspended continuation.
    public static Object lastSuspendedCancellableContinuationDuringVerification = null;

    /**
     * Mark value of {@link #requestedBeforeEventId} field to skip calls to {@link #beforeEvent}.
     */
    private static final int DO_NOT_TRIGGER_BEFORE_EVENT = -1;

    /**
     * Mark value of {@link #requestedBeforeEventId} field to always call {@link #beforeEvent}.
     */
    private static final int STOP_AT_NEXT_EVENT_ID = -2;

    /**
     * This field is updated from the debugger to request a specific ID.
     * <p>
     * Initially not triggered, then it is updated from the debugger to the desired ID.
     */
    @SuppressWarnings({"FieldMayBeFinal", "FieldCanBeLocal"})
    private static int requestedBeforeEventId = DO_NOT_TRIGGER_BEFORE_EVENT;

    /**
     * This field is used by the debugger to have a fast source of current ID.
     */
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private static int currentEventId = -1;

    public static EventTracker getEventTracker() {
        ThreadDescriptor descriptor = ThreadDescriptor.getCurrentThreadDescriptor();
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
     * (e.g. not registered in the Lincheck strategy).
     */
    public static void enterIgnoredSection() {
        ThreadDescriptor descriptor = ThreadDescriptor.getCurrentThreadDescriptor();
        if (descriptor == null) return;
        descriptor.enterIgnoredSection();
    }

    /**
     * Leaves an ignored section for the current thread.
     *
     * <p>
     * Does not affect the current thread if it is untracked
     * (e.g. not registered in the Lincheck strategy).
     */
    public static void leaveIgnoredSection() {
        ThreadDescriptor descriptor = ThreadDescriptor.getCurrentThreadDescriptor();
        if (descriptor == null) return;
        descriptor.leaveIgnoredSection();
    }

    /**
     * Determines if the current thread is inside an ignored section.
     *
     * @return true if the current thread is inside an ignored section, false otherwise.
     */
    public static boolean inAnalyzedCode() {
        ThreadDescriptor descriptor = ThreadDescriptor.getCurrentThreadDescriptor();
        if (descriptor == null) return false;
        return descriptor.inAnalyzedCode();
    }

    /**
     * Current thread reports that it is going to start a new child thread {@code forkedThread}.
     */
    public static void beforeThreadFork(Thread forkedThread) {
        // TestThread is handled separately
        if (forkedThread instanceof TestThread) return;
        // If thread is started return immediately, as in this case, JVM will throw an `IllegalThreadStateException`
        if (forkedThread.getState() != Thread.State.NEW) return;
        ThreadDescriptor descriptor = ThreadDescriptor.getCurrentThreadDescriptor();
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
        ThreadDescriptor.setThreadDescriptor(forkedThread, forkedThreadDescriptor);
        descriptor.leaveIgnoredSection();
        /*
         * End of the ignored section, the rest should be
         * wrapped into an ignored section by the event tracker itself, if necessary.
         */
        tracker.beforeThreadFork(forkedThread, forkedThreadDescriptor);
    }

    /**
     * Current thread entered its {@code run} method.
     */
    public static void beforeThreadStart() {
        Thread thread = Thread.currentThread();
        // TestThread is handled separately
        if (thread instanceof TestThread) return;
        ThreadDescriptor descriptor = ThreadDescriptor.getThreadDescriptor(thread);
        if (descriptor == null) {
            return;
        }
        ThreadDescriptor.setCurrentThreadDescriptor(descriptor);
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
        ThreadDescriptor descriptor = ThreadDescriptor.getCurrentThreadDescriptor();
        if (descriptor == null) return;
        EventTracker tracker = descriptor.getEventTracker();
        tracker.afterThreadFinish();
    }

    /**
     * Called from thread's {@code run} method failed with an exception.
     *
     * @param exception the exception that was thrown in the thread.
     */
    public static void onThreadRunException(Throwable exception) {
        Thread thread = Thread.currentThread();
        // TestThread is handled separately
        if (thread instanceof TestThread) return;
        ThreadDescriptor descriptor = ThreadDescriptor.getCurrentThreadDescriptor();
        if (descriptor == null) return;
        EventTracker tracker = descriptor.getEventTracker();
        tracker.onThreadRunException(exception);
    }

    /**
     * Called from instrumented code instead of {@code thread.join()}.
     */
    public static void threadJoin(Thread thread, boolean withTimeout) {
        ThreadDescriptor descriptor = ThreadDescriptor.getCurrentThreadDescriptor();
        if (descriptor == null) return;
        EventTracker tracker = descriptor.getEventTracker();
        tracker.threadJoin(thread, withTimeout);
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
     * See [org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy.park] for the explanation
     * why we have beforePark method.
     *
     * Creates a trace point which is used in the subsequent [beforeEvent] method call.
     */
    public static void beforePark(int codeLocation) {
        getEventTracker().beforePark(codeLocation);
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
     * Called from the instrumented code to get a random instance that is deterministic and controlled by Lincheck.
     */
    public static InjectedRandom deterministicRandom() {
        return getEventTracker().getThreadLocalRandom();
    }

    /**
     * Called from the instrumented code before each field read.
     *
     * @return whether the trace point was created
     */
    public static boolean beforeReadField(Object obj, int codeLocation, int fieldId) {
        return getEventTracker().beforeReadField(obj, codeLocation, fieldId);
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

    public static void afterLocalRead(int codeLocation, int variableId, Object value) {
        getEventTracker().afterLocalRead(codeLocation, variableId, value);
    }

    public static void afterLocalWrite(int codeLocation, int variableId, Object value) {
        getEventTracker().afterLocalWrite(codeLocation, variableId, value);
    }

    /**
     * Called from the instrumented code after each field read (final field reads can be ignored here).
     */
    public static void afterReadField(Object obj, int codeLocation, int fieldId, Object value) {
        getEventTracker().afterReadField(obj, codeLocation, fieldId, value);
    }

    /**
     * Called from the instrumented code after each array read.
     */
    public static void afterReadArray(Object array, int index, int codeLocation, Object value) {
        getEventTracker().afterReadArrayElement(array, index, codeLocation, value);
    }

    /**
     * Called from the instrumented code before each field write.
     *
     * @return whether the trace point was created
     */
    public static boolean beforeWriteField(Object obj, Object value, int codeLocation, int fieldId) {
        return getEventTracker().beforeWriteField(obj, value, codeLocation, fieldId);
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
     * @param receiver is `null` for public static methods.
     * @return Deterministic call descriptor or null.
     */
    public static Object onMethodCall(int codeLocation, int methodId, Object receiver, Object[] params) {
        return getEventTracker().onMethodCall(codeLocation, methodId, receiver, params);
    }

    /**
     * Called from the instrumented code after any method successful call, i.e., without any exception.
     *
     * @param descriptor Deterministic call descriptor or null.
     * @param descriptorId Deterministic call descriptor id when applicable, or any other value otherwise.
     * @param result The call result.
     * @return The potentially modified {@code result}.
     */
    public static Object onMethodCallReturn(long descriptorId, Object descriptor, int methodId, Object receiver, Object[] params, Object result) {
        return getEventTracker().onMethodCallReturn(descriptorId, descriptor, methodId, receiver, params, result);
    }

    /**
     * Called from the instrumented code after any method that returns void successful call, i.e., without any exception.
     *
     * @param descriptor Deterministic call descriptor or null.
     * @param descriptorId Deterministic call descriptor id when applicable, or any other value otherwise.
     */
    public static void onMethodCallReturnVoid(long descriptorId, Object descriptor, int methodId, Object receiver, Object[] params) {
        getEventTracker().onMethodCallReturn(descriptorId, descriptor, methodId, receiver, params, VOID_RESULT);
    }

    /**
     * Called from the instrumented code after any method call threw an exception
     *
     * @param descriptor Deterministic call descriptor or null.
     * @param descriptorId Deterministic call descriptor id when applicable, or any other value otherwise.
     * @param t Thrown exception.
     * @return The potentially modified {@code t}.
     */
    public static Throwable onMethodCallException(long descriptorId, Object descriptor, int methodId, Object receiver, Object[] params, Throwable t) {
        return getEventTracker().onMethodCallException(descriptorId, descriptor, methodId, receiver, params, t);
    }

    /**
     * Invokes a method deterministically based on the provided descriptor and parameters, or returns null
     * if the original method should be called.
     *
     * @param descriptorId the unique identifier for the deterministic method descriptor or any value if not applicable.
     * @param descriptor the deterministic method descriptor object providing details about the method to invoke or null.
     * @param receiver the object on which the method is to be invoked.
     * @param params The array of parameters to pass to the method during invocation.
     * @return The result of the method invocation wrapped in a {@link BootstrapResult},
     * or {@code null} if the original method should be called.
     */
    public static BootstrapResult<?> invokeDeterministicallyOrNull(long descriptorId, Object descriptor, Object receiver, Object[] params) {
        return getEventTracker().invokeDeterministicallyOrNull(descriptorId, descriptor, receiver, params);
    }

    /**
     * Retrieves a value from the provided BootstrapResult object or throws an exception if the result contains it.
     *
     * @param result the BootstrapResult object from which the value is to be retrieved
     * @return the value contained in the BootstrapResult object
     * @throws Throwable if the result contains it
     */
    public static Object getFromOrThrow(BootstrapResult<?> result) throws Throwable {
        return result.getOrThrow();
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
     * on the first run the actual computation is performed and its result is cached,
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
    public static void onInlineMethodCall(int methodId, int codeLocation, Object owner) {
        getEventTracker().onInlineMethodCall(methodId, codeLocation, owner);
    }

    /**
     * Called from the instrumented code after any kotlin inline method successful call, i.e., without any exception.
     */
    public static void onInlineMethodCallReturn(int methodId) {
        getEventTracker().onInlineMethodCallReturn(methodId);
    }

    // == Methods required for the IDEA Plugin integration ==

    public static boolean shouldInvokeBeforeEvent() {
        return getEventTracker().shouldInvokeBeforeEvent();
    }

    /**
     * This method is introduced for performance purposes.
     * Instead of calling {@link #beforeEvent} on every event, we call it only at the requested point.
     * It greatly improves the performance as the debugger installs a breakpoint into {@link #beforeEvent} method,
     * so each call leads to unnecessary lincheck/debugger communication.
     *
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
}