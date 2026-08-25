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

/**
 * Glue between the bytecode injected into the method under tracing and the actual tracing code.
 * <br>
 *
 * A call to {@link #startTracing} is injected as the first instruction of the method under tracing,
 * and a call to {@link #stopTracing} on each of its exit points
 * (normal exit via {@code return} or exceptional exit via {@code throw} or a propagated exception).
 * <br>
 *
 * This class lives in the bootstrap jar, like every other injection target:
 * the method under tracing belongs to the application, whose class loader cannot see
 * the isolated class loader holding the javaagent payload.
 * The tracing logic itself stays in the payload behind {@link Handler}.
 */
public class TracingInjections {

    private static volatile Handler handler = null;

    /** Installs the payload-side implementation; called by the javaagent during its startup. */
    public static void setHandler(Handler tracingHandler) {
        handler = tracingHandler;
    }

    public static void startTracing(int startingCodeLocationId) {
        Handler tracingHandler = handler;
        if (tracingHandler == null) return;
        tracingHandler.startTracing(startingCodeLocationId);
    }

    public static void stopTracing() {
        Handler tracingHandler = handler;
        if (tracingHandler == null) return;
        tracingHandler.stopTracing();
    }

    /**
     * Payload-side implementation of the tracing entry-point injections.
     *
     * @see TracingInjections
     */
    public interface Handler {

        /**
         * Starts tracing; called on entry to the method under tracing.
         *
         * @param startingCodeLocationId code location of the method under tracing.
         */
        void startTracing(int startingCodeLocationId);

        /**
         * Stops tracing and dumps the collected trace; called on each exit point of the method under tracing.
         */
        void stopTracing();
    }
}
