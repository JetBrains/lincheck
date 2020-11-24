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
     * Sends the specified [message] to the process [destId] (from 0 to [nProcesses]).
     */
    fun send(message: Message)

    /**
     * Sends the specified [message] to all processes except itself (from 0 to
     * [nProcesses]).
     */
    fun broadcast(message: Message) {
        for (i in 0 until nProcesses) {
            message.receiver = i
            send(message)
        }
    }
}




