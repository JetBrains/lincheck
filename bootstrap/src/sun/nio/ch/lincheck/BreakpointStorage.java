/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package sun.nio.ch.lincheck;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Central registry for the live-debugger breakpoint runtime state.
 * <p>
 *
 * Lives in the bootstrap module (boot classloader), so it is reachable from every
 * other module without introducing circular dependencies.
 * Callers that need to store module-specific data (e.g. {@code SnapshotBreakpoint}) pass it as an
 * opaque {@code Object userData}; the bootstrap layer never needs to know its type.
 */
public class BreakpointStorage {

    private BreakpointStorage() {}

    // -------------------------------------------------------------------------
    // Per-breakpoint state
    // -------------------------------------------------------------------------

    /**
     * Mutable runtime state for a single registered breakpoint.
     */
    public static class BreakpointState {
        /** Maximum number of hits before the hit-limit callback fires. */
        public final int hitLimit;
        /** Number of times this breakpoint has been hit so far. */
        public final AtomicInteger hitCount = new AtomicInteger(0);
        /**
         * Caller-supplied payload stored at registration time.
         * Typically, a {@code SnapshotBreakpoint}, but the bootstrap layer treats it as opaque.
         */
        public final Object userData;
        /**
         * Factory that creates a fresh {@link BooleanSupplier} condition for each hit.
         * {@code null} when the breakpoint has no condition.
         * Written once (at transformation time) and then read-only.
         */
        public volatile Function<Object[], BooleanSupplier> conditionFactory = null;

        public BreakpointState(int hitLimit, Object userData) {
            this.hitLimit = hitLimit;
            this.userData = userData;
        }
    }

    // -------------------------------------------------------------------------
    // Registry state
    // -------------------------------------------------------------------------

    private static final ConcurrentHashMap<Integer, BreakpointState> states = new ConcurrentHashMap<>();

    /**
     * Called exactly once when the hit-count of a breakpoint reaches its limit.
     * Receives the breakpoint id and {@link BreakpointState#userData}.
     */
    private static volatile HitLimitListener onHitLimitReached = null;

    /**
     * Called when a breakpoint's condition is detected to be unsafe (has side effects).
     * Receives the breakpoint id, {@link BreakpointState#userData}, and `SafetyViolation`.
     */
    private static volatile ConditionUnsafetyListener onConditionUnsafetyDetected = null;

    /**
     * Listener for hit-limit events.
     */
    @FunctionalInterface
    public interface HitLimitListener {
        void onHitLimitReached(int breakpointId, Object userData);
    }

    /**
     * Listener for condition-unsafety events.
     */
    @FunctionalInterface
    public interface ConditionUnsafetyListener {
        void onConditionUnsafetyDetected(int breakpointId, Object userData, Object safetyViolation);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Registers a new breakpoint under the caller-supplied id.
     * <p>
     * The id is assigned by the caller (typically the live-debugger module),
     * which owns the id-to-breakpoint mapping.
     *
     * @param breakpointId the caller-assigned breakpoint id; must be unique among currently registered breakpoints
     * @param hitLimit maximum number of hits before the hit-limit callback fires
     * @param userData caller-supplied payload (e.g. {@code SnapshotBreakpoint}); passed
     *                 verbatim to the hit-limit callback, never inspected here
     */
    public static void registerBreakpoint(int breakpointId, int hitLimit, Object userData) {
        states.put(breakpointId, new BreakpointState(hitLimit, userData));
    }

    /**
     * Attaches a condition factory to a previously registered breakpoint.
     * Called once per breakpoint at class-transformation time.
     *
     * @param breakpointId the breakpoint id returned by {@link #registerBreakpoint}
     * @param factory a function that accepts captured local-variable values and
     *                returns a {@link BooleanSupplier} that evaluates the condition
     */
    public static void registerConditionFactory(int breakpointId, Function<Object[], BooleanSupplier> factory) {
        BreakpointState state = states.get(breakpointId);
        if (state != null) state.conditionFactory = factory;
    }

    /**
     * Unregisters a breakpoint, removing all associated state.
     *
     * @param breakpointId the breakpoint id to remove
     */
    public static void removeBreakpoint(int breakpointId) {
        states.remove(breakpointId);
    }

    /**
     * Returns the {@code userData} payload registered with the given breakpoint id,
     * or {@code null} if no breakpoint is currently registered for that id.
     * <p>
     *
     * Bootstrap-layer callers receive an opaque {@code Object};
     * caller is responsible for casting it to the appropriate type
     * (typically, {@code SnapshotBreakpoint}).
     *
     * @param breakpointId the breakpoint id
     */
    public static Object getUserData(int breakpointId) {
        BreakpointState state = states.get(breakpointId);
        return state != null ? state.userData : null;
    }

    /**
     * Creates a condition instance for the given breakpoint by invoking its registered factory.
     *
     * @param breakpointId   the breakpoint id
     * @param args captured local-variable values to pass to the factory
     * @return a fresh {@link BooleanSupplier} that evaluates the condition
     * @throws IllegalStateException if no breakpoint or no factory is registered for {@code id}
     */
    public static BooleanSupplier createConditionInstance(int breakpointId, Object[] args) {
        BreakpointState state = states.get(breakpointId);
        if (state == null) {
            throw new IllegalStateException("No breakpoint registered with id=" + breakpointId);
        }
        Function<Object[], BooleanSupplier> factory = state.conditionFactory;
        if (factory == null) {
            throw new IllegalStateException("No condition factory for breakpoint id=" + breakpointId);
        }
        return factory.apply(args);
    }

    /**
     * Increments the hit count for the given breakpoint and checks whether it has
     * reached its configured limit.
     * <p>
     * Returns {@code false} (skip this hit) when the limit has already been exceeded.
     * Exactly one thread — the thread whose increment brings the count to {@code hitLimit} —
     * fires the hit-limit callback, ensuring no double-notification.
     *
     * @param breakpointId the breakpoint id
     * @return {@code true} if the hit should be processed; {@code false} if the limit
     *         was already reached and the hit should be skipped
     */
    public static boolean incrementAndCheckHitLimit(int breakpointId) {
        BreakpointState state = states.get(breakpointId);
        if (state == null) return false;
        int count = state.hitCount.incrementAndGet();
        if (count > state.hitLimit) return false;
        if (count == state.hitLimit) {
            HitLimitListener callback = onHitLimitReached;
            if (callback != null) callback.onHitLimitReached(breakpointId, state.userData);
        }
        return true;
    }

    /**
     * Registers the callback that fires when a breakpoint's hit count reaches its limit.
     * The callback receives the breakpoint id and {@link BreakpointState#userData}.
     * <p>
     *
     * Should be called before any breakpoint is registered, so that no hit-limit event
     * can fire before the callback is in place.
     *
     * @param callback invoked (on the hitting thread) with the breakpoint's id and {@code userData}
     */
    public static void setOnHitLimitReached(HitLimitListener callback) {
        onHitLimitReached = callback;
    }

    /**
     * Registers the callback that fires when a breakpoint's condition is detected to be unsafe.
     * The callback receives the breakpoint id, {@code userData}, and the safety violation.
     * <p>
     *
     * Should be called before any class transformation can occur, so that no
     * condition-unsafety event can fire before the callback is in place.
     *
     * @param callback invoked (on the transformation thread) with the breakpoint id,
     *                 {@code userData}, and safety violation.
     */
    public static void setOnConditionUnsafetyDetected(ConditionUnsafetyListener callback) {
        onConditionUnsafetyDetected = callback;
    }

    /**
     * Fires the condition-unsafety callback for the given breakpoint.
     * Called at class-transformation time when a breakpoint's condition is found to have
     * side effects and is therefore unsafe to evaluate at runtime.
     *
     * @param breakpointId    the breakpoint id
     * @param userData        the breakpoint's {@code userData} (typically a {@code SnapshotBreakpoint})
     * @param safetyViolation describes why the condition was rejected
     */
    public static void notifyConditionUnsafetyDetected(int breakpointId, Object userData, Object safetyViolation) {
        ConditionUnsafetyListener callback = onConditionUnsafetyDetected;
        if (callback != null) callback.onConditionUnsafetyDetected(breakpointId, userData, safetyViolation);
    }

    /**
     * Clears all stored breakpoint states and associated data,
     * including breakpoint condition factories.
     */
    public static void clear() {
        states.clear();
    }
}
