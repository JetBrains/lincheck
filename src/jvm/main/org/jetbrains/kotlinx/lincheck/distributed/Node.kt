package org.jetbrains.kotlinx.lincheck.distributed

import org.jetbrains.kotlinx.lincheck.distributed.event.Event

/**
 * Interface for a single node in a distributed algorithm.
 */
interface Node<Message> {
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
     */
    fun validate(events: List<Event>) {}
}

abstract class NodeWithStorage<Message, Storage>(protected val env: Environment<Message>) : Node<Message> {
    private var _storage: Storage? = null
    val storage: Storage
        get() {
            if (_storage == null) _storage = createStorage()
            env.beforeDatabaseAccess()
            return _storage!!
        }

    // called by Lincheck
    //TODO problems with generic parameters
    internal fun onRecovery(oldStorage: Any?) {
        _storage = oldStorage as Storage?
    }

    abstract fun createStorage(): Storage

    /**
     * Called in the end of the execution.
     * Can be used for validation.
     * [events] is the list of all events which occurred in the system during the execution.
     * [storages] are storages of all nodes in the system.
     */
    open fun validate(events: List<Event>, storages: Map<Int, Any>) {}
}

class CrashError : Exception()
