/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.jmx

/**
 * Helper class representing data associated with a JMX notification,
 * such as type, message, timestamp, and optional user-specific data.
 *
 * @property type The type of the notification, identifying its purpose or category.
 * @property message The description or content of the notification.
 * @property timestamp The time when the notification occurred, represented as a Unix timestamp in milliseconds.
 * @property userData Optional additional data associated with the notification, provided as an `Any?` type.
 */
data class JmxNotificationData(
    val type: String,
    val message: String,
    val timestamp: Long,
    val userData: Any? = null,
)