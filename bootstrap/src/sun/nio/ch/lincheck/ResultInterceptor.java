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
