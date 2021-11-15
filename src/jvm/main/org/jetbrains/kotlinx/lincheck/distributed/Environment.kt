package org.jetbrains.kotlinx.lincheck.distributed

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import org.jetbrains.kotlinx.lincheck.Actor
import kotlin.coroutines.CoroutineContext

/**
 * Environment interface for communication with other processes.
 */
interface Environment<Message, Log> {
    /**
     * Identifier of this node (from 0 to [numberOfNodes]).
     */
    val nodeId: Int

    /**
     * The total number of nodes in the system.
     */
    val numberOfNodes: Int

    /**
     * Returns identifiers of nodes of the exact class [cls].
     **/
    fun getAddressesForClass(cls: Class<out Node<Message, Log>>): List<Int>?

    /**
     * Sends the specified [message] to the process [receiver] (from 0 to [numberOfNodes]).
     */
    fun send(message: Message, receiver: Int)

    /**
     * Sends the specified [message] to all processes (from 0 to
     * [numberOfNodes]).
     */
    fun broadcast(message: Message, skipItself: Boolean = true) {
        for (i in 0 until numberOfNodes) {
            if (i == nodeId && skipItself) {
                continue
            }
            send(message, i)
        }
    }

    /**
     * Persistent storage for a current node.
     */
    val database: Log

    /**
     * Runs the specified [block] of code with a specified timeout and finishes if timeout was exceeded.
     * The execution will not be finish until the block is executed ot timeout is exceeded.
     */
    suspend fun withTimeout(ticks: Int, block: suspend () -> Unit): Boolean

    /**
     * Can be used as a safe [kotlinx.coroutines.delay]. The execution will not be finished until the program is resumed.
     */
    suspend fun sleep(ticks: Int)

    /**
     * Sets a timer with the specified [name].
     * Timer executes function [f] periodically each [ticks] time,
     * until the execution is over.
     */
    fun setTimer(name: String, ticks: Int, f: suspend () -> Unit)

    /**
     * Cancels timer with the name [name].
     */
    fun cancelTimer(name: String)

    /**
     * Records an internal event [InternalEvent]. Can be used for debugging purposes.
     * [message] is stored in [InternalEvent.message].
     */
    fun recordInternalEvent(attachment: Any)
}
