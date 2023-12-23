/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

// we need to use some "legal" package for the bootstrap class loader
@file:Suppress("PackageDirectoryMismatch")

package sun.nio.ch.lincheck

import java.util.concurrent.atomic.AtomicInteger

/**
 * When Lincheck runs a test, all threads should be instances of this [TestThread] class.
 * This class provides additional fields and controls for the Lincheck testing framework.
 * It also names the thread based on the test being run for easier debugging and tracking.
 *
 * Note: This class needs to be loaded in the bootstrap class loader, as the transformation requires it.
 *
 * @param testName The name of the test currently being run.
 * @property threadNumber The index of the thread.
 * @param block The Runnable object associated with the thread.
 */
 class TestThread(
    testName: String,
    val threadNumber: Int,
    block: Runnable
) : Thread(block, "Lincheck-${testName}-$threadNumber") {

    /**
     * The [SharedEventsTracker] for tracking shared memory events in the model checking mode.
     * It is nullable because it is initialized later (`lateinit` is not used for performance reasons).
     */
    @JvmField
    var sharedEventsTracker: SharedEventsTracker? = null

    /**
     * The currently suspended continuation, if present.
     * It's stored here to provide a handle for resumption during testing.
     */
    @JvmField
    var suspendedContinuation: Any? = null

    /**
     * This flag indicates whether the Lincheck is currently running user's code.
     *
     * - When it is `true`, Lincheck is running user's code and analyzes it.
     * - When it is `false`, the analysis is disabled.
     */
    @JvmField
    var inTestingCode = false

    /**
     * This flag is used to control the analysis during user's executions.
     * If Lincheck enters a code block for which analysis should be disabled,
     * this flag is set to `true`. Notably, such code blocks can be nested,
     * but only the most outer one changes the flag.
     */
    @JvmField
    var inIgnoredSection: Boolean = false
}