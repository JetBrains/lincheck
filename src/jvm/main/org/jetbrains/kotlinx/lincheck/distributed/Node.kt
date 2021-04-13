package org.jetbrains.kotlinx.lincheck.distributed

/**
 * Interface for a single node in a distributed algorithm.
 */
interface Node<Message> {
    /**
     * Called when a new message [message] arrives.
     */
    suspend fun onMessage(message: Message, sender: Int)

    /**
     * Called when the node restarts after failure.
     */
    suspend fun recover() {}

    /**
     * Called when the node receives notification about failure of [iNode]
     */
    suspend fun onNodeUnavailable(iNode: Int) {}

    /**
     * Called at the beginning of a node execution.
     */
    suspend fun onStart() {}

    /**
     * Called after all operations for the node are finished.
     * Is not called if the node has no scenario.
     */
    suspend fun onScenarioFinish() {}

    /**
     * Returns a current node state representation.
     */
    fun stateRepresentation(): String = ""
}

class CrashError : Exception()
