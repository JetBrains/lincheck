package org.jetbrains.kotlinx.lincheck.distributed

import org.jetbrains.kotlinx.lincheck.distributed.event.Event

/**
 * Interface for a single node in a distributed algorithm.
 */
interface Node<Message, DB> {
    /**
     * Called when a new message [message] arrives.
     */
    fun onMessage(message: Message, sender: Int)

    /**
     * Called when the node restarts after failure.
     */
    fun recover() {}

    /**
     * Called when the node receives notification about failure of [iNode]
     */
    fun onNodeUnavailable(iNode: Int) {}

    /**
     * Called at the beginning of a node execution.
     */
    fun onStart() {}

    /**
     * Called after all operations for the node are finished.
     * Is not called if the node has no scenario.
     */
    fun onScenarioFinish() {}

    /**
     * Returns a current node state representation.
     */
    fun stateRepresentation(): String = ""

    fun validate(events: List<Event>, logs: List<DB>) {}
}

class CrashError : Exception()
