package org.jetbrains.kotlinx.lincheck.distributed

import java.util.concurrent.TimeUnit

/**
 * Environment interface for communication with other processes.
 */
interface Environment<Message> {
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
    fun getAddress(cls: Class<*>, i: Int): Int

    /**
     * @param timer
     * @param time
     * @param timeUnit
     */
    fun setTimer(timer: String, time: Int, timeUnit: TimeUnit = TimeUnit.MILLISECONDS)

    /**
     * @param timer
     */
    fun cancelTimer(timer: String)

    /**
     * Sends the specified [message] to the process [destId] (from 0 to [numberOfNodes]).
     */
    fun send(message: Message, receiver: Int)

    /**
     * Sends the specified [message] to all processes (from 0 to
     * [numberOfNodes]).
     */
    fun broadcast(message: Message) {
        for (i in 0 until numberOfNodes) {
            send(message, i)
        }
    }

    /**
     * Sends local message. Can be used to point out that some action is completed
     * (e.g. message is delivered to a user, or lock is acquired). After the end of the execution all local messages are available and can be checked by the user.
     */
    fun sendLocal(message: Message)

    val events: List<Event>
}

sealed class Event
class MessageSentEvent<Message>(val message: Message, val sender: Int, val receiver: Int, val id: Int) : Event()
class MessageReceivedEvent<Message>(val message: Message, val sender: Int, val receiver: Int, val id: Int) : Event()
class LocalMessageSentEvent<Message>(val message: Message, val sender: Int, val id: Int) : Event()
class ProcessFailureEvent(val processId: Int) : Event()
class ProcessRecoveryEvent(val processId: Int) : Event()
class TimerEvent(val processId: Int, val timer: String) : Event()

fun <Message> Environment<Message>.correctProcesses() = (0 until numberOfNodes).subtract(events.filterIsInstance<ProcessFailureEvent>().map { it.processId })
fun <Message> Environment<Message>.sentMessages(processId: Int = nodeId) = events.filterIsInstance<MessageSentEvent<Message>>().filter { it.sender == processId }
fun <Message> Environment<Message>.receivedMessages(processId: Int = nodeId) = events.filterIsInstance<MessageReceivedEvent<Message>>().filter { it.receiver == processId }
fun <Message> Environment<Message>.localMessages(processId: Int = nodeId) = events.filterIsInstance<LocalMessageSentEvent<Message>>().filter { it.sender == processId }.map { it.message }
fun <Message> List<Message>.isDistinct(): Boolean = distinctBy { System.identityHashCode(it) } == this
fun <Message> Environment<Message>.isCorrect() = correctProcesses().contains(nodeId)