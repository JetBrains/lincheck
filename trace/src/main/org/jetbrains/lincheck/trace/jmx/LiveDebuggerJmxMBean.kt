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

import org.jetbrains.lincheck.trace.controller.LiveDebuggerNotification
import org.jetbrains.lincheck.trace.controller.TracingNotification
import javax.management.Notification

/**
 * Live debugger JMX MBean interface.
 *
 * Extends the functionality of the tracing JMX MBean by providing
 * operations specific to the live debugger, such as managing breakpoints.
 */
interface LiveDebuggerJmxMBean : TracingJmxMBean {

    /**
     * Adds breakpoints to Live Debugger.
     */
    fun addBreakpoints(breakpoints: List<String>)

    /**
     * Removes breakpoints from Live Debugger.
     */
    fun removeBreakpoints(breakpoints: List<String>)

    companion object {
        /**
         * JMX notification type used to indicate that a breakpoint condition is deemed unsafe.
         *
         * The notification's `userData` is a string `"className:fileName:lineNumber"` identifying the breakpoint.
         */
        const val UNSAFE_BREAKPOINT_CONDITION_NOTIFICATION_TYPE = "breakpoint.unsafeCondition"

        /**
         * JMX notification type sent when a breakpoint reaches its hit limit.
         *
         * The notification's `userData` is a string `"className:fileName:lineNumber"` identifying the breakpoint.
         */
        const val BREAKPOINT_HIT_LIMIT_NOTIFICATION_TYPE = "breakpoint.hitLimitReached"

        fun parseJmxNotification(notification: Notification): TracingNotification? {
            return when (notification.type) {
                UNSAFE_BREAKPOINT_CONDITION_NOTIFICATION_TYPE -> {
                    LiveDebuggerNotification.BreakpointConditionUnsafetyDetected(
                        timestamp = notification.timeStamp,
                        breakpointData = LiveDebuggerNotification.BreakpointData
                            .parseFromString(notification.userData as String)
                            ?: return null,
                    )
                }

                BREAKPOINT_HIT_LIMIT_NOTIFICATION_TYPE -> {
                    LiveDebuggerNotification.BreakpointHitLimitReached(
                        timestamp = notification.timeStamp,
                        breakpointData = LiveDebuggerNotification.BreakpointData
                            .parseFromString(notification.userData as String)
                            ?: return null,
                    )
                }

                else -> null
            }
        }

        fun getJmxNotificationData(notification: TracingNotification): JmxNotificationData? {
            return with(notification) {
                when (this) {
                    is LiveDebuggerNotification.BreakpointConditionUnsafetyDetected -> JmxNotificationData(
                        type = UNSAFE_BREAKPOINT_CONDITION_NOTIFICATION_TYPE,
                        message = "Unsafe breakpoint condition detected",
                        timestamp = timestamp,
                        userData = breakpointData.toString(),
                    )

                    is LiveDebuggerNotification.BreakpointHitLimitReached -> JmxNotificationData(
                        type = BREAKPOINT_HIT_LIMIT_NOTIFICATION_TYPE,
                        message = "Breakpoint hit limit reached",
                        timestamp = timestamp,
                        userData = breakpointData.toString(),
                    )

                    else -> null
                }
            }
        }
    }
}