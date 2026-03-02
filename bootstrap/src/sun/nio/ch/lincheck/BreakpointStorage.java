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
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Central registry for all live-debugger breakpoint runtime state.
 * <p>
 * Lives in the bootstrap module (boot classloader) so it is reachable from every
 * other module without introducing circular dependencies.  Callers that need to
 * store module-specific data (e.g. {@code SnapshotBreakpoint}) pass it as an
 * opaque {@code Object userData}; the bootstrap layer never needs to know its type.
 */
public class BreakpointStorage {

    private BreakpointStorage() {}

    // -------------------------------------------------------------------------
    // Per-breakpoint state
    // -------------------------------------------------------------------------

    /**
     * All mutable runtime state for a single registered breakpoint.
     */
    public static class BreakpointState {
        /** Maximum number of hits before the hit-limit callback fires. */
        public final int hitLimit;
        /** Number of times this breakpoint has been hit so far. */
        public final AtomicInteger hitCount = new AtomicInteger(0);
        /**
         * Caller-supplied payload stored at registration time.
         * Typically a {@code SnapshotBreakpoint}, but the bootstrap layer treats it as opaque.
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

    /** Source of unique breakpoint ids. */
    private static final AtomicInteger nextId = new AtomicInteger(0);

    /**
     * Called exactly once when the hit-count of a breakpoint reaches its limit.
     * Receives {@link BreakpointState#userData} directly — no id look-up needed.
     */
    private static volatile Consumer<Object> onHitLimitReached = null;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Registers a new breakpoint and returns its unique integer id.
     *
     * @param hitLimit maximum number of hits before the hit-limit callback fires
     * @param userData caller-supplied payload (e.g. {@code SnapshotBreakpoint}); passed
     *                 verbatim to the hit-limit callback, never inspected here
     * @return the assigned breakpoint id (stable for the lifetime of the registration)
     */
    public static int registerBreakpoint(int hitLimit, Object userData) {
        int id = nextId.getAndIncrement();
        states.put(id, new BreakpointState(hitLimit, userData));
        return id;
    }

    /**
     * Attaches a condition factory to a previously registered breakpoint.
     * Called once per breakpoint at class-transformation time.
     *
     * @param id      the breakpoint id returned by {@link #registerBreakpoint}
     * @param factory a function that accepts captured local-variable values and
     *                returns a {@link BooleanSupplier} that evaluates the condition
     */
    public static void registerConditionFactory(int id, Function<Object[], BooleanSupplier> factory) {
        BreakpointState state = states.get(id);
        if (state != null) state.conditionFactory = factory;
    }

    /**
     * Unregisters a breakpoint, removing all associated state.
     *
     * @param id the breakpoint id to remove
     */
    public static void removeBreakpoint(int id) {
        states.remove(id);
    }

    /**
     * Creates a condition instance for the given breakpoint by invoking its registered factory.
     *
     * @param id   the breakpoint id
     * @param args captured local-variable values to pass to the factory
     * @return a fresh {@link BooleanSupplier} that evaluates the condition
     * @throws IllegalStateException if no breakpoint or no factory is registered for {@code id}
     */
    public static BooleanSupplier createConditionInstance(int id, Object[] args) {
        BreakpointState state = states.get(id);
        if (state == null) {
            throw new IllegalStateException("No breakpoint registered with id=" + id);
        }
        Function<Object[], BooleanSupplier> factory = state.conditionFactory;
        if (factory == null) {
            throw new IllegalStateException("No condition factory for breakpoint id=" + id);
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
     * @param id the breakpoint id
     * @return {@code true} if the hit should be processed; {@code false} if the limit
     *         was already reached and the hit should be skipped
     */
    public static boolean incrementAndCheckHitLimit(int id) {
        BreakpointState state = states.get(id);
        if (state == null) return false;
        int count = state.hitCount.incrementAndGet();
        if (count > state.hitLimit) return false;
        if (count == state.hitLimit) {
            Consumer<Object> callback = onHitLimitReached;
            if (callback != null) callback.accept(state.userData);
        }
        return true;
    }

    /**
     * Registers the callback that fires when a breakpoint's hit count reaches its limit.
     * The callback receives {@link BreakpointState#userData} directly.
     * <p>
     * Should be called before any breakpoint is registered, so that no hit-limit event
     * can fire before the callback is in place.
     *
     * @param callback invoked (on the hitting thread) with the breakpoint's {@code userData}
     */
    public static void setOnHitLimitReached(Consumer<Object> callback) {
        onHitLimitReached = callback;
    }
}
