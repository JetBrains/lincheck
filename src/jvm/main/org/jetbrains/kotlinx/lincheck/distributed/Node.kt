package org.jetbrains.kotlinx.lincheck.distributed

/**
 * Interface for a single node in a distributed algorithm.
 */
interface Node<Message> {
    /**
     * Called when a new [message] from [sender] arrives.
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
}

abstract class NodeWithStorage<Message, Storage>(protected val env: NodeEnvironment<Message>) : Node<Message> {
    private var _storage: Storage? = null
    val storage: Storage
        get() {
            if (_storage == null) _storage = createStorage()
            env.beforeStorageAccess()
            return _storage!!
        }

    // called by Lincheck
    //TODO problems with generic parameters
    internal fun onRecovery(oldStorage: Any?) {
        _storage = oldStorage as Storage?
    }

    abstract fun createStorage(): Storage
}

class CrashError : Exception()
