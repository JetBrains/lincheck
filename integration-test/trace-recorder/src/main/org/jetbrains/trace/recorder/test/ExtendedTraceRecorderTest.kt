/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test

/**
 * Marker interface for JUnit 4 Categories to mark extended trace-recorder integration tests.
 *
 * Usage:
 *
 *  @org.junit.experimental.categories.Category(ExtendedTraceRecorderTest::class)
 *  class MyExtendedTest : AbstractTraceRecorderIntegrationTest() { ... }
 */
interface ExtendedTraceRecorderTest
