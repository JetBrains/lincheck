package org.jetbrains.kotlinx.lincheck.distributed

/*
/**
 * Environment interface for communication with other processes.
 */
interface Environment<Message> {
    /**
     * Identifier of this node (from 0 to [nodes]).
     */
    val nodeId: Int

    /**
     * The total number of nodes in the system.
     */
    val nodes: Int

    /**
     * Returns identifiers of nodes of the exact class [cls].
     **/

    /**
     * Sends the specified [message] to the process [receiver] (from 0 to [nodes]).
     */
    fun send(message: Message, receiver: Int)

    /**
     * Sends the specified [message] to all processes (from 0 to [nodes]).
     * If [skipItself] is true, doesn't send the message to itself.
     */
    fun broadcast(message: Message, skipItself: Boolean = true) {
        for (i in 0 until nodes) {
            if (i == nodeId && skipItself) {
                continue
            }
            send(message, i)
        }
    }

    fun broadcastToGroup(message: Message, cls: Class<out Node<Message, DB>>, skipItself: Boolean = true) {
        for (i in getAddressesForClass(cls)!!) {
            if (i == nodeId && skipItself) continue
            send(message, i)
        }
    }

    /**
     * Persistent storage for a current node.
     */
    val database: DB

    /**
     * Runs the specified [block] of code with a specified timeout and finishes if timeout was exceeded.
     * The execution will not be finish until the block is executed ot timeout is exceeded.
     */
    suspend fun <T> withTimeout(ticks: Int, block: suspend CoroutineScope.() -> T): T?

    /**
     * Can be used as an alternative to [kotlinx.coroutines.delay]. The execution will not be finished until the program is resumed.
     */
    suspend fun sleep(ticks: Int)

    /**
     * Sets a timer with the specified [name].
     * Timer executes function [f] periodically each [ticks] time, until the execution is over.
     * Throws [IllegalArgumentException] if timer with name [name] already exists.
     */
    fun setTimer(name: String, ticks: Int, f: () -> Unit)

    /**
     * Cancels timer with the name [name].
     * Throws [IllegalArgumentException] if timer with name [name] doesn't exist.
     */
    fun cancelTimer(name: String)

    /**
     * Records an internal event [org.jetbrains.kotlinx.lincheck.distributed.event.InternalEvent]. Can be used for debugging purposes.
     * Can store any object as [attachment].
     */
    fun recordInternalEvent(attachment: Any)
}
*/
inline fun <reified NodeType, Message> Environment<Message>.getAddresses(): List<Int> {
    TODO()
}
