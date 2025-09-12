/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Runs the given [block] while temporarily redirecting System.err to a tee stream that:
 * - captures everything written to it into a String buffer, and
 * - forwards all output to the original System.err simultaneously.
 *
 * Returns a Pair of the block's result and the captured stderr output as String.
 */
fun <T> withStdErrTee(block: () -> T): Pair<T, String> {
    val originalErr: PrintStream = System.err
    val capture = ByteArrayOutputStream()
    val tee = PrintStream(TeeOutputStream(originalErr, capture), true)

    return try {
        System.setErr(tee)
        val result = block()
        result to capture.toString()
    } catch (t: Throwable) {
        // Ensure we still capture whatever was printed before rethrowing
        throw t
    } finally {
        try {
            tee.flush()
        } catch (_: Throwable) {
        }
        System.setErr(originalErr)
        try {
            capture.flush()
        } catch (_: Throwable) {
        }
    }
}

/**
 * A minimal OutputStream that duplicates all writes to two underlying streams.
 */
private class TeeOutputStream(
    private val first: OutputStream,
    private val second: OutputStream
) : OutputStream() {
    override fun write(b: Int) {
        first.write(b)
        second.write(b)
    }

    override fun write(b: ByteArray) {
        first.write(b)
        second.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        first.write(b, off, len)
        second.write(b, off, len)
    }

    override fun flush() {
        first.flush()
        second.flush()
    }

    override fun close() {
        // Do not close the original System.err stream; just flush both.
        flush()
    }
}
