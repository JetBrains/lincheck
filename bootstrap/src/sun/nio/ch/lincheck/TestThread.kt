/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package sun.nio.ch.lincheck

/**
 * When Lincheck runs a test, all threads should be instances of this [TestThread] class.
 * This class provides additional fields and controls for the Lincheck testing framework.
 * It also names the thread based on the test being run for easier debugging and tracking.
 *
 * @param testName The name of the test currently being run.
 * @property threadId The index of the thread.
 * @param block The Runnable object associated with the thread.
 */
class TestThread(
    testName: String?, // nullable only to avoid using `kotlin.jvm.internal.Intrinsics` for the non-nullability check.
    val threadId: Int,
    block: Runnable? // nullable only to avoid using `kotlin.jvm.internal.Intrinsics` for the non-nullability check.
) : Thread(block, "Lincheck-${testName}-$threadId") {

    /**
     * The [EventTracker] for tracking shared memory events in the model checking mode.
     * It is nullable because it is initialized later (`lateinit` is not used for performance reasons).
     */
    @JvmField
    var eventTracker: EventTracker? = null

    /**
     * The currently suspended continuation, if present.
     * It's stored here to provide a handle for resumption during testing.
     *
     * It's necessary to store it in [Any] type, otherwise we would have to load CancellableContinuation class earlier.
     * But actually [suspendedContinuation] is always of [CancellableContinuation] type.
     */
    @JvmField
    var suspendedContinuation: Any? = null

    /**
     * This flag indicates whether the Lincheck is currently running user's code.
     *
     * - When it is `true`, Lincheck is running user's code and analyses it.
     * - When it is `false`, the analysis is disabled.
     */
    @JvmField
    var inTestingCode = false

    /**
     * This flag is used to disable tracking of all code events.
     * If Lincheck enters a code block for which analysis should be disabled,
     * this flag is set to `true`. Notably, such code blocks can be nested,
     * but only the most outer one changes the flag.
     */
    @JvmField
    var inIgnoredSection: Boolean = false
}