package org.jetbrains.kotlinx.lincheck.distributed

import org.jetbrains.kotlinx.lincheck.distributed.event.Event

/**
 * Interface for a single node in a distributed algorithm.
 */
interface Node<Message, DB> {
    /**
     * Called when a new [message][message] from [sender][sender] arrives.
     */
    fun onMessage(message: Message, sender: Int)

    /**
     * Called when the node restarts after crash.
     */
    fun recover() {}

    /**
     * Called when the node receives notification that [iNode] is not available.
     * It happens when [iNode] crashed or the nodes were partitioned.
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

    /**
     * Called in the end of the execution.
     * Can be used for validation.
     * [events] is the list of all events which occurred in the system during the execution.
     * [databases] are databases of all nodes in the system.
     */
    fun validate(events: List<Event>, databases: List<DB>) {}
}

class CrashError : Exception()
