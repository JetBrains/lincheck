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

import java.util.concurrent.Callable;
import java.util.function.Function;

public class JavaResult {
    private final boolean success;
    private final Object result;
    private final Throwable t;
    
    public Object getOrThrow() throws Throwable {
        if (success) {
            return result;
        } else {
            throw t;
        }
    }
    
    public static Object getFromOrThrow(JavaResult result) throws Throwable {
        return result.getOrThrow();
    }
    
    private JavaResult(boolean success, Object result, Throwable t) {
        this.success = success;
        this.result = result;
        this.t = t;
    }
    
    public static JavaResult fromSuccess(Object result) {
        return new JavaResult(true, result, null);
    }
    
    public static JavaResult fromFailure(Throwable t) {
        return new JavaResult(false, null, t);
    }
    
    public static JavaResult fromCallable(Callable<Object> callable) {
        try {
            return fromSuccess(callable.call());
        } catch (Throwable t) {
            return fromFailure(t);
        }
    }
    
    public JavaResult map(Function<Object, Object> f) {
        if (success) {
            return JavaResult.fromSuccess(f.apply(result));
        } else {
            return this;
        }
    }
}
