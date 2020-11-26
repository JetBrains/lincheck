package org.jetbrains.kotlinx.lincheck.distributed

/**
 * Environment interface for communication with other processes.
 */
interface Environment {
    /**
     * Identifier of this process (from 0 to [nProcesses]).
     */
    val processId: Int

    /**
     * The total number of processes in the system.
     */
    val nProcesses: Int

    /**
     * Returns the identifier of the process of the exact class.
     * E.g.: if there are ten clients and one server, the client
     * can get the server id.
     */
    fun getAddress(cls : Class<*>, i : Int) : Int

    /**
     * Sends the specified [message] to the process [destId] (from 0 to [nProcesses]).
     */
    fun send(message: Message, receiver : Int)

    /**
     * Sends the specified [message] to all processes except itself (from 0 to
     * [nProcesses]).
     */
    fun broadcast(message: Message) {
        for (i in 0 until nProcesses) {
            send(message, i)
        }
    }

    fun sendLocal(message : Message)

    fun checkLocalMessages(atMostOnce : Boolean = false, atLeastOnce : Boolean = false, preserveOrder : Boolean = false)

    val messages : List<Message>
    val processes : List<ProcessExecution>
    val processExecution : ProcessExecution?
}

data class ProcessExecution(
        val id : Int,
        val isAlive : Boolean,
        val sentMessages : List<Message>,
        val receivedMessages : List<Message>,
        val localMessages : List<Message>)



