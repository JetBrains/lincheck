package org.jetbrains.kotlinx.lincheck.distributed

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Interface for a single node in a distributed algorithm.
 */
interface Node<Message> {
    /**
     * Called when a new message arrives.
     * @param message is a message from another node
     */
    fun onMessage(message : Message, sender : Int)

    /**
     * Called if the [timer] expires. The timer can be set using the environment
     * @see Environment#setTimer(String, Int, TimeUnit)
     */
    fun onTimer(timer : String) {}

    /**
     * Called before a process is recovered from failure. Used to define process state after failure
     * (e.g. default values should be set for all fields).
     */
    fun beforeRecovery() {}
}


abstract class BlockingReceiveNodeImp<Message> : Node<Message> {
    private val messageQueue = LinkedBlockingQueue<Message>()

    override fun onMessage(message: Message, sender : Int) {
        messageQueue.add(message)
    }

    fun receive(timeout : Long, timeUnit: TimeUnit = TimeUnit.MILLISECONDS) : Message? {
        return messageQueue.poll(timeout, timeUnit)
    }

    fun receive() : Message? {
        return messageQueue.poll()
    }
}