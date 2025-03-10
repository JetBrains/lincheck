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

public class BootstrapResult<T> {
    private final boolean success;
    private final T result;
    private final Throwable t;
    
    public T getOrThrow() throws Throwable {
        if (success) {
            return result;
        } else {
            throw t;
        }
    }
    
    private BootstrapResult(boolean success, T result, Throwable t) {
        this.success = success;
        this.result = result;
        this.t = t;
    }
    
    public static <T> BootstrapResult<T> fromSuccess(T result) {
        return new BootstrapResult<>(true, result, null);
    }
    
    public static <T> BootstrapResult<T> fromFailure(Throwable t) {
        return new BootstrapResult<>(false, null, t);
    }
}
