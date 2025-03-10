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

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In Lincheck mode we replace {@link  Random} calls with the same calls to our {@link Random}.
 * However, when the instance is {@link ThreadLocalRandom}, in some JDKs there are its own unique methods,
 * that are added to the {@link Random} in latter JDKs.
 * So, to make fake {@link Random} work for these methods as well, we need to make it implement the methods here.
 */
public class InjectedRandom extends Random {
    public InjectedRandom(long seed) {
        super(seed);
    }
    
    public InjectedRandom() {
        super();
    }
    
    public int nextInt(int origin, int bound) {
        if (bound <= origin) { throw new IllegalArgumentException("bound must be greater than origin"); }
        return (int) nextLong(origin, bound);
    }
    
    public long nextLong(long bound) {
        if (bound <= 0) { throw new IllegalArgumentException("bound must be positive"); }
        long anyLong;
        do {
            anyLong = nextLong();
        } while (anyLong == Long.MIN_VALUE);
        long positiveLong = anyLong >= 0 ? anyLong : -anyLong;
        return positiveLong % bound;
    }
    
    public long nextLong(long origin, long bound) {
        if (bound <= origin) { throw new IllegalArgumentException("bound must be greater than origin"); }
        
        if (bound - origin > 0) {
            // default case
            return origin + nextLong(bound - origin);
        }
        // Overflow
        if (origin < 0 && bound > 0) {
            return origin + nextLong(-origin) + nextLong(bound) + nextLong(2);
        }
        assert origin == Long.MIN_VALUE;
        return nextLong(origin + 1, bound) - nextLong(2);
    }
    
    public double nextDouble(double origin, double bound) {
        if (bound <= origin) { throw new IllegalArgumentException("bound must be greater than origin"); }
        return origin + nextDouble(bound - origin);
    }
    
    public double nextDouble(double bound) {
        if (bound <= 0) { throw new IllegalArgumentException("bound must be positive");}
        return Math.abs(nextDouble()) * bound;
    }
    
    public static ThreadLocalRandom current() {
        return ThreadLocalRandom.current();
    }
}
