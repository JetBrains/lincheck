[//]: # (title: Testing blocking data structures)

Lincheck supports testing blocking data structures implemented via [suspending functions](https://github.com/Kotlin/KEEP/blob/master/proposals/coroutines.md#coroutines-overview)
from the Kotlin language. The examples of such data structures from the `kotlinx.coroutines` library are mutexes, semaphores, and channels.

For more information on these data structures, see the [Coroutines guide](https://kotlinlang.org/docs/reference/coroutines/coroutines-guide.html).

### Dual data structures

Some data structures are blocking by design. Consider the synchronous queues (also known as channels in coroutine libraries),
where senders and receivers perform a rendezvous handshake as a part of their protocol when senders wait for receivers
and vice versa.

The execution below is valid for synchronous queues but cannot be linearized by any sequential execution starting from
either `send` or `receive` operation as it will suspend immediately.

![Stress execution of the Counter](channel.png){width=700}

To extend linearizability for blocking data structures, Lincheck uses the dual data structures formalism [described by W.N. Scherer III and M.L. Scott](#what-s-next).

According to the dual data structures formalism, every blocking operation is split into _request_ and _follow-up_ parts
at the suspension point. The request part either suspends the operation invocation and returns a unique ticket `s` or
completes immediately and returns the final result.

The follow-up part is executed when the operation is resumed. It takes the ticket `s` returned by the corresponding
request as an argument. These tickets are necessary to determine which operation should be resumed when having several
waiting senders or receivers.

This way, the synchronous queue execution above can be explained the following way:

1. Register `receive()`s request into the internal waiting queue of the channel.
2. `send(4)` performs a rendezvous with the already registered `receive()` and passes `4` to it.
3. The `receive()` resumes and returns `4`.

![Stress execution of the Counter](dual_ds.png){width=700}

### Test for a blocking data structure

To write a Lincheck test for a blocking data structure, just mark suspending testing functions with a `suspend` modifier.
It will handle suspensions automatically.

Here is the test example for a basic communication primitive, a [rendezvous channel](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-channel/index.html):

```kotlin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Test

@Param(name = "value", gen = IntGen::class, conf = "1:5")
class RendezvousChannelTest {
  private val ch = Channel<Int>()

  @Operation(handleExceptionsAsResult = [ClosedSendChannelException::class])
  suspend fun send(@Param(name = "value") value: Int) = ch.send(value)

  @Operation(handleExceptionsAsResult = [ClosedReceiveChannelException::class])
  suspend fun receive() = ch.receive()

  @Operation
  fun close() = ch.close()

  @Test
  fun stressTest() = StressOptions().check(this::class)
}
```

### State equivalency

Write a test for a [buffered channel](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-channel/index.html)
defining the equivalency relation on the states of a channel.
See the [Results verification](verification.md) section for details.

For a buffered channel, the externally observable state may include:

* Elements from the buffer
* Waiting `send` operations
* Information on whether the channel is closed

Here is the example of the buffered channel external state definition:

```kotlin
override fun extractState(): Any {
    val elements = mutableListOf<Int>()
    while (!ch.isEmpty) elements.add(ch.poll()!!)
    val closed = ch.isClosedForSend
    return elements to closed
}
```

Below is the complete example of a buffered channel test:

```kotlin
import kotlinx.coroutines.channels.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

@Param(name = "value", gen = IntGen::class, conf = "1:5")
class BufferedChannelTest : VerifierState() {
    private val ch = Channel<Int>(3)

    @Operation(handleExceptionsAsResult = [ClosedSendChannelException::class])
    suspend fun send(@Param(name = "value") value: Int) = ch.send(value)

    @Operation(handleExceptionsAsResult = [ClosedReceiveChannelException::class])
    suspend fun receive() = ch.receive()

    @Operation
    fun close() = ch.close()

    override fun extractState(): Any {
        val elements = mutableListOf<Int>()
        while (!ch.isEmpty && !ch.isClosedForReceive) elements.add(ch.poll()!!)
        val closed = ch.isClosedForSend
        return elements to closed
    }

    @Test
    fun stressTest() = StressOptions().check(this::class)
}
```

>Get the full code of the tests for the [rendezvous](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/RendezvousChannelTest.kt)
> and [buffered](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/BufferedChannelTest.kt) channels.
>
{type="note"}

## What's next

* For more information on the dual data structures formalism, 
  which extends linearizability with blocking behaviour, 
  see the corresponding ["Nonblocking concurrent objects with condition synchronization"](https://www.cs.rochester.edu/~scott/papers/2004_DISC_dual_DS.pdf)
  paper by W. Scherer and M. Scott published at DISC'04.
  
* Check [this talk](https://nkoval.com/talks/#lincheck-hydra-2019) 
  at Hydra for more details on the dual data structures verification.