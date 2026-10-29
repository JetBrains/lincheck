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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

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

    /** Slot token for a breakpoint condition in {@link #notifyBreakpointExpressionEvaluationFailed}. */
    public static final int EXPRESSION_SLOT_CONDITION = 0;
    /** Slot token for breakpoint watches in {@link #notifyBreakpointExpressionEvaluationFailed}. */
    public static final int EXPRESSION_SLOT_WATCH = 1;

    /**
     * Number of failed condition evaluations, with no successful one ever, after which
     * the breakpoint is reported as disabled: such a condition is almost certainly broken.
     */
    public static final int CONDITION_FAILURE_LIMIT = 1000;

    /** Value of {@link BreakpointState#conditionFailures} once the condition has evaluated without throwing. */
    private static final int CONDITION_SUCCEEDED = -1;

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
        /**
         * Factory that creates a fresh {@link Supplier} of watch values for each hit.
         * {@code null} when the breakpoint has no watches.
         * Written once (at transformation time) and then read-only.
         */
        public volatile Function<Object[], Supplier> watchFactory = null;
        /**
         * Number of failed condition evaluations, capped at {@link #CONDITION_FAILURE_LIMIT},
         * or {@link #CONDITION_SUCCEEDED} once the condition has evaluated without throwing.
         * One field, so the success marker and the failure limit decision are a single atomic transition:
         * a recorded success can never be followed by a trip, and a trip is never undone by a later success.
         */
        public final AtomicInteger conditionFailures = new AtomicInteger(0);
        /** {@code slot:failureKind} pairs already reported, so each failure kind is reported once. */
        public final Set<String> reportedFailures = ConcurrentHashMap.newKeySet();

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
     * Called when a breakpoint expression (condition or watch) is detected to be unsafe (has side effects).
     * Receives the breakpoint id, {@link BreakpointState#userData}, the expression kind, and `SafetyViolation`.
     */
    private static volatile BreakpointExpressionUnsafetyListener onBreakpointExpressionUnsafetyDetected = null;

    /**
     * Called when a breakpoint is rejected by a sensitive-area blocklist (at registration or
     * instrumentation time). Receives the breakpoint id, {@link BreakpointState#userData}, and an
     * opaque reason string.
     */
    private static volatile BreakpointBlockedListener onBreakpointBlocked = null;
    private static volatile BreakpointExpressionCompilationFailedListener onBreakpointExpressionCompilationFailed = null;

    /**
     * Called when a hit is suppressed because its call stack passes through a blocked sensitive
     * area (dynamic-extent / Stage 3). Receives the breakpoint id, {@link BreakpointState#userData},
     * the blocked frame's class name, and an opaque reason string.
     */
    private static volatile HitSuppressedListener onHitSuppressed = null;

    /**
     * Called when a breakpoint condition or watch throws on a hit,
     * once per failure kind and once more when the condition failure limit disables the breakpoint.
     */
    private static volatile BreakpointExpressionEvaluationFailedListener onBreakpointExpressionEvaluationFailed = null;

    /**
     * Listener for hit-limit events.
     */
    @FunctionalInterface
    public interface HitLimitListener {
        void onHitLimitReached(int breakpointId, Object userData);
    }

    /**
     * Listener for breakpoint-expression-unsafety events (conditions and watches alike).
     * The {@code expressionKind} is an opaque token (a {@code BreakpointExpressionKind} enum
     * value) the bootstrap layer forwards without inspecting.
     */
    @FunctionalInterface
    public interface BreakpointExpressionUnsafetyListener {
        void onBreakpointExpressionUnsafetyDetected(int breakpointId, Object userData, Object expressionKind, Object safetyViolation);
    }

    /**
     * Listener for blocklist-rejection events. The {@code reason} is an opaque human-readable
     * string the bootstrap layer forwards without inspecting.
     */
    @FunctionalInterface
    public interface BreakpointBlockedListener {
        void onBreakpointBlocked(int breakpointId, Object userData, Object reason);
    }

    /** Listener for an expression that could not be compiled during class transformation. */
    @FunctionalInterface
    public interface BreakpointExpressionCompilationFailedListener {
        void onBreakpointExpressionCompilationFailed(int breakpointId, Object userData, Object message);
    }

    /**
     * Listener for dynamic-extent hit-suppression events (a hit skipped because its call stack
     * passes through a blocked sensitive area). The {@code reason} is an opaque human-readable
     * string the bootstrap layer forwards without inspecting.
     */
    @FunctionalInterface
    public interface HitSuppressedListener {
        void onHitSuppressed(int breakpointId, Object userData, String blockedFrameClass, Object reason);
    }

    /**
     * Listener for expression failures on a hit.
     * {@code slot} is {@link #EXPRESSION_SLOT_CONDITION} or {@link #EXPRESSION_SLOT_WATCH};
     * {@code failureKind} is the class name of the thrown object;
     * {@code disabled} is {@code true} when the condition failure limit was reached.
     */
    @FunctionalInterface
    public interface BreakpointExpressionEvaluationFailedListener {
        void onBreakpointExpressionEvaluationFailed(int breakpointId, Object userData, int slot, String failureKind, boolean disabled);
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
     * Attaches a watch factory to a previously registered breakpoint.
     * Called once per breakpoint at class-transformation time.
     *
     * @param breakpointId the breakpoint id returned by {@link #registerBreakpoint}
     * @param factory a function that accepts captured local-variable values and
     *                returns a {@link Supplier} that evaluates watches
     */
    public static void registerWatchFactory(int breakpointId, Function<Object[], Supplier> factory) {
        BreakpointState state = states.get(breakpointId);
        if (state != null) state.watchFactory = factory;
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
     * Whether the given breakpoint has already reached its hit limit, without consuming budget.
     * O(1) non-mutating peek used before expensive per-hit work (e.g. the stack capture);
     * {@link #incrementAndCheckHitLimit} remains the single authority — a benign race with a
     * concurrent increment only means one extra hit proceeds to the authoritative check.
     *
     * @param breakpointId the breakpoint id
     * @return {@code true} if the limit is reached (or the breakpoint is unregistered)
     */
    public static boolean isHitLimitReached(int breakpointId) {
        BreakpointState state = states.get(breakpointId);
        return state == null || state.hitCount.get() >= state.hitLimit;
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
     * Creates a watch supplier instance for the given breakpoint.
     *
     * @param breakpointId the breakpoint id
     * @param args captured local-variable values to pass to the factory
     * @return a fresh {@link Supplier} that evaluates watches
     * @throws IllegalStateException if no breakpoint or no factory is registered for {@code id}
     */
    public static Supplier createWatchInstance(int breakpointId, Object[] args) {
        BreakpointState state = states.get(breakpointId);
        if (state == null) {
            throw new IllegalStateException("No breakpoint registered with id=" + breakpointId);
        }
        Function<Object[], Supplier> factory = state.watchFactory;
        if (factory == null) {
            throw new IllegalStateException("No watch factory for breakpoint id=" + breakpointId);
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
     * Registers the callback that fires when a breakpoint expression (condition or watch)
     * is detected to be unsafe. The callback receives the breakpoint id, {@code userData},
     * the expression kind, and the safety violation.
     * <p>
     *
     * Should be called before any class transformation can occur, so that no
     * unsafety event can fire before the callback is in place.
     *
     * @param callback invoked (on the transformation thread) with the breakpoint id,
     *                 {@code userData}, expression kind, and safety violation.
     */
    public static void setOnBreakpointExpressionUnsafetyDetected(BreakpointExpressionUnsafetyListener callback) {
        onBreakpointExpressionUnsafetyDetected = callback;
    }

    /**
     * Fires the breakpoint-expression-unsafety callback for the given breakpoint.
     * Called at class-transformation time when a breakpoint condition or watch is found to
     * have side effects and is therefore unsafe to evaluate at runtime.
     *
     * @param breakpointId    the breakpoint id
     * @param userData        the breakpoint's {@code userData} (typically a {@code SnapshotBreakpoint})
     * @param expressionKind  which expression was rejected (opaque {@code BreakpointExpressionKind})
     * @param safetyViolation describes why the expression was rejected
     */
    public static void notifyBreakpointExpressionUnsafetyDetected(int breakpointId, Object userData, Object expressionKind, Object safetyViolation) {
        BreakpointExpressionUnsafetyListener callback = onBreakpointExpressionUnsafetyDetected;
        if (callback != null) callback.onBreakpointExpressionUnsafetyDetected(breakpointId, userData, expressionKind, safetyViolation);
    }

    /**
     * Registers the callback that fires when a breakpoint is rejected by a sensitive-area blocklist.
     * The callback receives the breakpoint id, {@code userData}, and an opaque reason string.
     * <p>
     * Should be called before any breakpoint registration or class transformation, so that no
     * blocked event can fire before the callback is in place.
     *
     * @param callback invoked with the breakpoint id, {@code userData}, and reason
     */
    public static void setOnBreakpointBlocked(BreakpointBlockedListener callback) {
        onBreakpointBlocked = callback;
    }

    /**
     * Fires the blocklist-rejection callback for the given breakpoint, if a listener is installed.
     *
     * @param breakpointId the breakpoint id
     * @param userData the breakpoint's {@code userData} (typically a {@code SnapshotBreakpoint})
     * @param reason opaque human-readable explanation of which policy rejected the breakpoint
     */
    public static void notifyBreakpointBlocked(int breakpointId, Object userData, Object reason) {
        BreakpointBlockedListener callback = onBreakpointBlocked;
        if (callback != null) callback.onBreakpointBlocked(breakpointId, userData, reason);
    }

    public static void setOnBreakpointExpressionCompilationFailed(BreakpointExpressionCompilationFailedListener callback) {
        onBreakpointExpressionCompilationFailed = callback;
    }

    public static void notifyBreakpointExpressionCompilationFailed(int breakpointId, Object userData, Object message) {
        BreakpointExpressionCompilationFailedListener callback = onBreakpointExpressionCompilationFailed;
        if (callback != null) callback.onBreakpointExpressionCompilationFailed(breakpointId, userData, message);
    }

    /**
     * Registers the callback that fires when a hit is suppressed by dynamic-extent enforcement.
     * Should be installed before tracing starts, so no suppression event can fire unobserved.
     *
     * @param callback invoked with the breakpoint id, {@code userData}, blocked frame class, and reason
     */
    public static void setOnHitSuppressed(HitSuppressedListener callback) {
        onHitSuppressed = callback;
    }

    /**
     * Fires the dynamic-extent hit-suppression callback for the given breakpoint, if a listener
     * is installed.
     *
     * @param breakpointId the breakpoint id
     * @param userData the breakpoint's {@code userData} (typically a {@code SnapshotBreakpoint})
     * @param blockedFrameClass canonical class name of the blocked stack frame
     * @param reason opaque human-readable explanation of which policy blocked the frame
     */
    public static void notifyHitSuppressed(int breakpointId, Object userData, String blockedFrameClass, Object reason) {
        HitSuppressedListener callback = onHitSuppressed;
        if (callback != null) callback.onHitSuppressed(breakpointId, userData, blockedFrameClass, reason);
    }

    /**
     * Registers the callback that fires when a breakpoint condition or watch throws on a hit.
     * Should be installed before tracing starts, so no failure can go unobserved.
     *
     * @param callback invoked (on the hitting thread) with the breakpoint id, {@code userData},
     *                 slot, failure kind, and whether the breakpoint must be disabled
     */
    public static void setOnBreakpointExpressionEvaluationFailed(BreakpointExpressionEvaluationFailedListener callback) {
        onBreakpointExpressionEvaluationFailed = callback;
    }

    /**
     * Records that the condition of the given breakpoint evaluated without throwing,
     * which permanently exempts the breakpoint from the condition failure limit,
     * unless the limit has already disabled it.
     *
     * @param breakpointId the breakpoint id
     */
    public static void markConditionSucceeded(int breakpointId) {
        BreakpointState state = states.get(breakpointId);
        if (state == null) return;
        int failures;
        do {
            failures = state.conditionFailures.get();
            if (failures == CONDITION_SUCCEEDED || failures >= CONDITION_FAILURE_LIMIT) return;
        } while (!state.conditionFailures.compareAndSet(failures, CONDITION_SUCCEEDED));
    }

    /**
     * Counts a condition failure unless the condition has succeeded or the limit is already reached.
     *
     * @return {@code true} for exactly the failure that reaches {@link #CONDITION_FAILURE_LIMIT}
     */
    private static boolean recordConditionFailure(BreakpointState state) {
        int failures;
        do {
            failures = state.conditionFailures.get();
            if (failures == CONDITION_SUCCEEDED || failures >= CONDITION_FAILURE_LIMIT) return false;
        } while (!state.conditionFailures.compareAndSet(failures, failures + 1));
        return failures + 1 == CONDITION_FAILURE_LIMIT;
    }

    /**
     * Records a condition or watch failure on a hit and fires the failure callback
     * the first time a {@code (slot, failure kind)} pair is seen for the breakpoint,
     * and exactly once more, with {@code disabled = true}, when the condition fails for
     * the {@link #CONDITION_FAILURE_LIMIT}-th time without ever having succeeded.
     * <p>
     * Only the class name of {@code failure} is read: its {@code getMessage()} and {@code toString()}
     * are application code and may have side effects.
     *
     * @param breakpointId the breakpoint id
     * @param slot {@link #EXPRESSION_SLOT_CONDITION} or {@link #EXPRESSION_SLOT_WATCH}
     * @param failure the throwable raised by the expression
     */
    public static void notifyBreakpointExpressionEvaluationFailed(int breakpointId, int slot, Throwable failure) {
        BreakpointState state = states.get(breakpointId);
        if (state == null) return;
        String failureKind = failure.getClass().getName();
        boolean disabled = slot == EXPRESSION_SLOT_CONDITION && recordConditionFailure(state);
        boolean firstOfKind = state.reportedFailures.add(slot + ":" + failureKind);
        if (!firstOfKind && !disabled) return;
        BreakpointExpressionEvaluationFailedListener callback = onBreakpointExpressionEvaluationFailed;
        if (callback != null) callback.onBreakpointExpressionEvaluationFailed(breakpointId, state.userData, slot, failureKind, disabled);
    }

    /**
     * Clears all stored breakpoint states and associated data,
     * including breakpoint condition and watch factories.
     */
    public static void clear() {
        states.clear();
    }
}
