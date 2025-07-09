/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.annotations

import org.jetbrains.lincheck.util.LoggingLevel
import java.lang.annotation.Inherited

/**
 * This annotation should be added to a test class to specify the logging level.
 * By default, [LoggingLevel.WARN] is used.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
@Inherited
@Deprecated(
    level = DeprecationLevel.WARNING,
    message = "Use org.jetbrains.lincheck.datastructures.LogLevel instead.",
)
annotation class LogLevel(val value: LoggingLevel)
