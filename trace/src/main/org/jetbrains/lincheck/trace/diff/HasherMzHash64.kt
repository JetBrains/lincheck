/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.diff

/**
 * It is "from scratch" implementation of mzHash64 Strong, fast, simple, non-cryptography hash function.
 *
 * Algorithm is described here:
 * https://github.com/matteo65/mzHash64
 *
 * This implementation doesn't use any seed.
 *
 * Original algorithm was released under Apache-2.0 license, according to provided repo.
 */

internal class HasherMzHash64 {
    private companion object {
        const val START: Long = -2390164861889055616L // 0xDED46DB8C47B7480L
        const val MUL: Long = -1632365092264590397 // 0xE958AC98E3D243C3L
    }

    private var hash: Long = START

    fun add(v: Byte): HasherMzHash64 {
        updateHash(v.toInt())
        return this
    }

    fun add(v: Boolean): HasherMzHash64 {
        updateHash(if (v) 1 else 0)
        return this
    }

    fun add(v: Char): HasherMzHash64 {
        val i = v.code
        updateHash((i      ) and 0xff)
        updateHash((i shr 8) and 0xff)
        return this
    }

    fun add(v: Short): HasherMzHash64 {
        updateHash((v.toInt()      ) and 0xff)
        updateHash((v.toInt() shr 8) and 0xff)
        return this
    }

    fun add(v: Int): HasherMzHash64 {
        updateHash((v       ) and 0xff)
        updateHash((v shr  8) and 0xff)
        updateHash((v shr 16) and 0xff)
        updateHash((v shr 24) and 0xff)
        return this
    }

    fun add(v: Long): HasherMzHash64 {
        updateHash(((v       ) and 0xffffffffL).toInt())
        updateHash(((v shr 32) and 0xffffffffL).toInt())
        return this
    }

    fun add(v: String): HasherMzHash64 {
        v.forEach {
            val i = it.code
            updateHash((i      ) and 0xff)
            updateHash((i shr 8) and 0xff)
        }
        return this
    }

    // fun add(v: Any): Hasher = add(v.hashCode())

    fun <T> add(v: Collection<T>): HasherMzHash64 {
        v.forEach { add(it.hashCode()) }
        return this
    }

    fun finish(): Long {
        val h = hash
        hash = START
        return h
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun updateHash(v: Int) {
        val h = hash
        hash = MUL * (v.toLong() xor (h shl 8) xor (h shr 8))
    }
}