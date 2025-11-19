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
 * Marker interface for JUnit 4 Categories to mark extended trace-recorder Kotlin Compiler integration tests.
 *
 * Usage:
 *
 *  ```kotlin
 *  @Category(KotlinCompilerTraceRecorderTest::class)
 *  class MyKotlinCompilerTest : AbstractTraceRecorderIntegrationTest() { ... }
 *  ```
 */
interface KotlinCompilerTraceRecorderTest

/**
 * Marker interface for JUnit 4 Categories to mark extended trace-recorder Ktor integration tests.
 *
 * Usage:
 *
 *  ```kotlin
 *  @Category(KtorCompilerTraceRecorderTest::class)
 *  class MyKtorCompilerTest : AbstractTraceRecorderIntegrationTest() { ... }
 *  ```
 */
interface KtorTraceRecorderTest
