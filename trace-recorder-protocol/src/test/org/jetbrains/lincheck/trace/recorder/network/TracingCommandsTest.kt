/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.recorder.network

import org.jetbrains.lincheck.trace.recorder.network.websocket.handleMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class TracingCommandsTest {
    @Test
    fun recordingCommandsRetainTheirWireFormat() {
        val calls = mutableListOf<String>()
        val commands = object : TracingCommands {
            override fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean) {
                calls += "file:$traceDumpFilePath:$packTrace"
            }
            override fun startNetworkTracing() { calls += "network" }
            override fun stopTracing() { calls += "stop" }
        }
        commands.handleMessage("startFileTracing:recording.trace:true")
        commands.handleMessage("startNetworkTracing")
        commands.handleMessage("stopTracing")
        commands.handleMessage("addBreakpoints:unsupported")
        commands.handleMessage("startFileTracing:missing-pack-argument")
        commands.handleMessage(null)
        assertEquals(listOf("file:recording.trace:true", "network", "stop"), calls)
    }
}
