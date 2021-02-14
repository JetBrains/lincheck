package org.jetbrains.kotlinx.lincheck.distributed

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.distributed.queue.FastQueue
import java.util.concurrent.TimeUnit

/**
 * Interface for a single node in a distributed algorithm.
 */
interface Node<Message> {
    companion object {
        const val TICK_TIME = 1
    }

    /**
     * Called when a new message arrives.
     * @param message is a message from another node
     */
    suspend fun onMessage(message : Message, sender : Int)

    fun recover() {}

    suspend fun onNodeUnavailable(nodeId : Int) {}

    suspend fun <T> withTimeout(ticks: Int, block: suspend CoroutineScope.() -> T): T? =
        withTimeout(ticks * TICK_TIME, block)
}

/*
abstract class BlockingReceiveNodeImp<Message> : Node<Message> {
    private val messageQueue = FastQueue<Pair<Message, Int>>()

    override fun onMessage(message: Message, sender : Int) {
        messageQueue.put(Pair(message, sender))
    }

    fun receive(timeout : Long, timeUnit: TimeUnit = TimeUnit.MILLISECONDS) : Pair<Message, Int>? {
        return runBlocking {
            // launch a new coroutine in background and continue
            // non-blocking delay for 1 second (default time unit is ms)
            // print after delay
            withContext(Dispatchers.Default) { // launch a new coroutine in background and continue
                delay(timeout) // non-blocking delay for 1 second (default time unit is ms)
                messageQueue.poll() // print after delay
            }
        }
    }

    fun receive() : Pair<Message, Int> {
        while (true) {
            return messageQueue.poll() ?: continue
        }
    }
}
*/

interface RecoverableNode<Message, Log> : Node<Message> {
    fun recover(logs : List<Log>)
}