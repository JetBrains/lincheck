/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */


/**
 * Utility class for computing bounded hash values.
 *
 * Source: https://github.com/rohanpadhye/JQF/blob/master/fuzz/src/main/java/edu/berkeley/cs/jqf/fuzz/util/Hashing.java
 */
object Hashing {
    /**
     * Returns a bounded hashed value with one input.
     *
     * @param x     the input to hash
     * @param bound the upper bound
     * @return a pseudo-uniformly distributed value in [0, bound)
     */
    fun hash(x: Long, bound: Int): Int {
        return knuth(x, bound)
    }

    /**
     * Returns a bounded hashed value with two inputs.
     *
     * @param x     the first input to hash
     * @param y     the second input to hash
     * @param bound the upper bound
     * @return a pseudo-uniformly distributed value in [0, bound)
     */
    fun hash1(x: Long, y: Long, bound: Int): Int {
        return knuth(x * 31 + y, bound)
    }

    private fun cap(x: Long, bound: Int): Int {
        var res = (x % bound).toInt()
        if (res < 0) {
            res += bound
        }
        return res
    }

    /**
     * Compute knuth's multiplicative hash.
     *
     *
     * Source: Donald Knuth's *The Art of Computer Programming*,
     * Volume 3 (2nd edition), section 6.4, page 516.
     *
     * @param x     the input value to hash
     * @param bound the upper bound
     * @return the hash value
     */
    internal fun knuth(x: Long, bound: Int): Int {
        return cap(x * 2654435761L, bound)
    }
}

