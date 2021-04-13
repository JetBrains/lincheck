package org.jetbrains.kotlinx.lincheck.distributed

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.distributed.queue.FastQueue
import java.util.concurrent.TimeUnit

/**
 * Interface for a single node in a distributed algorithm.
 */
interface Node<Message> {
    /**
     * Called when a new message [message] arrives.
     */
    suspend fun onMessage(message: Message, sender: Int)

    suspend fun recover() {}

    suspend fun onNodeUnavailable(nodeId: Int) {}

    suspend fun onStart() {}

    suspend fun onScenarioFinish() {}

    fun stateRepresentation() : String = ""
}

class CrashError : Exception()
