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

/**
 * Dynamically sized stack-based pool of {@link ResultInterceptor} objects.
 * It is used to optimize allocations of {@link ResultInterceptor} objects,
 * by re-using the interceptor object stored in the pool.
 * <p>
 *
 * The pool is organized as a stack to match nested method call order.
 * Result interceptors are taken from and returned into the pool in stack (LIFO) order.
 * The top of the stack (corresponding to the most recent, deepest method call)
 * is located at the last index of the array,
 * the bottom of the stack (corresponding to the earliest method call)
 * is located at the index 0.
 * <p>
 *
 * The pool starts with an initial capacity of {@link #MIN_SIZE}
 * and grows by 2x when exceeded. When the used size drops to 1/4 of the capacity,
 * the pool shrinks by 2x, but never below the minimum size.
 * <p>
 *
 * Lazily allocated on first use.
 */
class ResultInterceptorPool {

    /**
     * The minimum size of the pool.
     */
    private static final int MIN_SIZE = 64;

    /**
     * The backing array storing pooled {@link ResultInterceptor} instances.
     * Null by default, lazily allocated on first use.
     */
    private ResultInterceptor[] pool = null;

    /**
     * The index of the next available {@code ResultInterceptor} in the pool.
     */
    private int index = -1;

    /**
     * Takes a {@link ResultInterceptor} from the pool.
     * If the pool is full, it is expanded to 2x its current capacity.
     *
     * @return a recycled {@link ResultInterceptor} instance, or {@code null} if no recycled instance is available.
     */
    public ResultInterceptor take() {
        if (pool == null) {
            pool = new ResultInterceptor[MIN_SIZE];
            index = 0;
        }
        if (index >= pool.length) {
            grow();
        }
        return pool[index++];
    }

    /**
     * Returns a {@link ResultInterceptor} to the pool for re-use.
     * The interceptor is {@linkplain ResultInterceptor#reset() reset} before being returned to the pool.
     *
     * @param interceptor the interceptor to return to the pool.
     */
    public void give(ResultInterceptor interceptor) {
        if (pool == null || index <= 0) return;

        interceptor.reset();
        pool[--index] = interceptor;
        if (pool.length > MIN_SIZE && index <= pool.length / 4) {
            shrink();
        }
    }

    private void grow() {
        int newSize = pool.length * 2;
        ResultInterceptor[] newPool = new ResultInterceptor[newSize];
        System.arraycopy(pool, 0, newPool, 0, pool.length);
        pool = newPool;
    }

    private void shrink() {
        int newSize = Math.max(pool.length / 2, MIN_SIZE);
        ResultInterceptor[] newPool = new ResultInterceptor[newSize];
        System.arraycopy(pool, 0, newPool, 0, index);
        pool = newPool;
    }
}
