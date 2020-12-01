package org.jetbrains.kotlinx.lincheck.distributed

import java.util.concurrent.TimeUnit

/**
 * Environment interface for communication with other processes.
 */
interface Environment {
    /**
     * Identifier of this node (from 0 to [numberOfNodes]).
     */
    val nodeId: Int

    /**
     * The total number of nodes in the system.
     */
    val numberOfNodes: Int

    /**
     * Returns the identifier of the process of the exact class.
     * E.g.: if there are several clients and one server, the client
     * can get the server id.
     *
     * @param cls is a class which instance is to be identified
     * @param i is the index
     * @return
     */
    fun getAddress(cls : Class<*>, i : Int) : Int

    /**
     * @param timer
     * @param time
     * @param timeUnit
     */
    fun setTimer(timer : String, time : Int, timeUnit : TimeUnit = TimeUnit.MILLISECONDS)

    /**
     * @param timer
     */
    fun cancelTimer(timer : String)

    /**
     * Sends the specified [message] to the process [destId] (from 0 to [numberOfNodes]).
     */
    fun send(message: Message, receiver : Int)

    /**
     * Sends the specified [message] to all processes (from 0 to
     * [numberOfNodes]).
     *
     * @param message is a message to be sent
     */
    fun broadcast(message: Message) {
        for (i in 0 until numberOfNodes) {
            send(message, i)
        }
    }

    /**
     *
     */
    fun sendLocal(message : Message)

    /**
     *
     */
    fun checkLocalMessages(atMostOnce : Boolean = false, atLeastOnce : Boolean = false, preserveOrder : Boolean = false)

    /**
     *
     */
    val messages : List<Message>

    /**
     *
     */
    val processes : List<ProcessExecution>

    /**
     *
     */
    val processExecution : ProcessExecution?
}

/**
 *
 */
data class ProcessExecution(
        val id : Int,
        val isAlive : Boolean,
        val sentMessages : List<Message>,
        val receivedMessages : List<Message>,
        val localMessages : List<Message>)
