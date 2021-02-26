package org.jetbrains.kotlinx.lincheck.distributed

import java.util.concurrent.TimeUnit

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
     * Returns the identifier of the process of the exact class.
     * E.g.: if there are several clients and one server, the client
     * can get the server id.
     *
     * @param cls is a class which instance is to be identified
     * @param i is the index
     * @return
     */
    fun getAddress(cls: Class<out Node<Message>>, i: Int): Int

    fun getNumberOfNodeType(cls: Class<out Node<Message>>) : Int

    /**
     * Sends the specified [message] to the process [receiver] (from 0 to [numberOfNodes]).
     */
    suspend fun send(message: Message, receiver: Int)

    /**
     * Sends the specified [message] to all processes (from 0 to
     * [numberOfNodes]).
     */
    suspend fun broadcast(message: Message) {
        for (i in 0 until numberOfNodes) {
            if (i == nodeId) {
                continue
            }
            send(message, i)
        }
    }

    val log : MutableList<Log>

    /**
     * Sends local message. Can be used to point out that some action is completed
     * (e.g. message is delivered to a user, or lock is acquired). After the end of the execution all local messages are available and can be checked by the user.
     */
    fun sendLocal(message: Message)

    val events: List<Event>
}

sealed class Event
data class MessageSentEvent<Message>(val message: Message, val sender: Int, val receiver: Int, val id: Int) : Event()
data class MessageReceivedEvent<Message>(val message: Message, val sender: Int, val receiver: Int, val id: Int) : Event()
data class LocalMessageSentEvent<Message>(val message: Message, val sender: Int) : Event()
data class ProcessFailureEvent(val processId: Int) : Event()
data class ProcessRecoveryEvent(val processId: Int) : Event()

fun <Message, Log> Environment<Message, Log>.correctProcesses() = (0 until numberOfNodes).subtract(events.filterIsInstance<ProcessFailureEvent>().map { it.processId })
fun <Message, Log> Environment<Message, Log>.sentMessages(processId: Int = nodeId) = events.filterIsInstance<MessageSentEvent<Message>>().filter { it.sender == processId }
fun <Message, Log> Environment<Message, Log>.receivedMessages(processId: Int = nodeId) = events.filterIsInstance<MessageReceivedEvent<Message>>().filter { it.receiver == processId }
fun <Message, Log> Environment<Message, Log>.localMessages(processId: Int = nodeId) = events.filterIsInstance<LocalMessageSentEvent<Message>>().filter { it.sender == processId }.map { it.message }
fun <Message> List<Message>.isDistinct(): Boolean = distinctBy { System.identityHashCode(it) } == this
fun <Message, Log> Environment<Message, Log>.isCorrect() = correctProcesses().contains(nodeId)