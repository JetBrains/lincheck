package org.jetbrains.kotlinx.lincheck.distributed

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
    fun getAddressesForClass(cls: Class<out Node<Message>>): List<Int>

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

    /**
     * Logs for current node.
     */
    val log: MutableList<Log>

    /**
     * Test execution events for all nodes (including sending and receiving messages,
     * node failures, logs and etc.)
     * Should be called only in validation functions to check the invariants.
     */
    val events: List<Event>

    /**
     * Causes the execution to await until the signal is received.
     */
    suspend fun await()

    /**
     * Resumes the awaiting execution.
     */
    fun signal()
}

sealed class Event
data class MessageSentEvent<Message>(val message: Message, val sender: Int, val receiver: Int, val id: Int) : Event()
data class MessageReceivedEvent<Message>(val message: Message, val sender: Int, val receiver: Int, val id: Int) :
    Event()

data class LogEvent<Log>(val message: Log, val sender: Int) : Event()
data class ProcessFailureEvent(val processId: Int) : Event()
data class ProcessRecoveryEvent(val processId: Int) : Event()

fun <Message, Log> Environment<Message, Log>.correctProcesses() =
    (0 until numberOfNodes).subtract(events.filterIsInstance<ProcessFailureEvent>().map { it.processId })

fun <Message, Log> Environment<Message, Log>.sentMessages(processId: Int = nodeId) =
    events.filterIsInstance<MessageSentEvent<Message>>().filter { it.sender == processId }

fun <Message, Log> Environment<Message, Log>.receivedMessages(processId: Int = nodeId) =
    events.filterIsInstance<MessageReceivedEvent<Message>>().filter { it.receiver == processId }

fun <Message> List<Message>.isDistinct(): Boolean = distinctBy { System.identityHashCode(it) } == this
fun <Message, Log> Environment<Message, Log>.isCorrect() = correctProcesses().contains(nodeId)