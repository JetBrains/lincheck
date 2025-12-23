/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package sun.nio.ch.lincheck;

/**
 * Result interceptor allows intercepting the result of a method invocation (or other operations).
 * <br>
 *
 * Result interceptor works in tandem with the instrumentation and the {@link EventTracker} class.
 * When method call interception instrumentation is enabled, the event tracker may use
 * the interceptor object passed to method call tracking injections to intercept the result of the method call.
 * In that case, instead of performing an actual method call,
 * the code will use the intercepted object or exception will be used as a result of the method call.
 * Similar mechanism can be used to intercept other operations, such as field access or array access.
 * <br>
 *
 * The result interception allows implementing various useful features,
 * such as record-replay debugging, or read operations result interception
 * (to model different relaxed memory consistency effects).
 * <br>
 *
 * See {@link Injections#onMethodCall}, {@link Injections#onMethodCallReturn},
 * and {@link Injections#onMethodCallException} for details on result interception protocol.
 */
public class ResultInterceptor {
    private Object interceptedResult = null;
    private Throwable interceptedException = null;

    private Object eventTrackerData = null;

    public void interceptResult(Object result) {
        if (isIntercepted()) throw ResultAlreadyInterceptedException(this);
        interceptedResult = result;
    }

    public void interceptException(Throwable throwable) {
        if (isIntercepted()) throw ResultAlreadyInterceptedException(this);
        interceptedException = throwable;
    }

    public Object getInterceptedResult() {
        return interceptedResult;
    }

    public Throwable getInterceptedException() {
        return interceptedException;
    }

    public boolean isResultIntercepted() {
        return (interceptedResult != null);
    }

    public boolean isExceptionIntercepted() {
        return (interceptedException != null);
    }

    public boolean isIntercepted() {
        return (interceptedResult != null || interceptedException != null);
    }

    public Object getEventTrackerData() {
        return eventTrackerData;
    }

    public void setEventTrackerData(Object eventTrackerData) {
        this.eventTrackerData = eventTrackerData;
    }

    private static IllegalStateException ResultAlreadyInterceptedException(ResultInterceptor interceptor) {
        String message = "Result is already intercepted by" + getObjectRepresentation(interceptor);
        if (interceptor.interceptedResult != null) {
            message += ": intercepted normal result" + getObjectRepresentation(interceptor.interceptedResult);
        } else if (interceptor.interceptedException != null) {
            message += ": intercepted exception " + getObjectRepresentation(interceptor.interceptedException);
        }
        return new IllegalStateException(message);
    }

    private static String getObjectRepresentation(Object obj) {
        return obj.getClass().getSimpleName() + "@" + Integer.toHexString(obj.hashCode());
    }
}
