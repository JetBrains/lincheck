[//]: # (title: Data structure constraints)

Some data structures may require some operations to be never executed concurrently, 
such as single-producer single-consumer queues. Lincheck provides an out-of-the-box support
for such contracts, generating concurrent scenarios according to the restrictions.

Consider the [single-consumer queue](https://github.com/JCTools/JCTools/blob/66e6cbc9b88e1440a597c803b7df9bd1d60219f6/jctools-core/src/main/java/org/jctools/queues/atomic/MpscLinkedAtomicQueue.java)
from the  [JCTools library](https://github.com/JCTools/JCTools). 
Let's write a test to check correctness of `poll()`, `peek()` and `offer(x)` operations.

To meet the single-consumer restriction, ensure that all `poll()` and `peek()` consuming operations
are called from a single thread. For that, declare a group of operations for _non-parallel_ execution:

1. Declare `@OpGroupConfig` annotation to create a group of operations for non-parallel execution, name the group,
and set `nonParallel` parameter to true.

2. Specify the group name in the `@Operation` annotation to add all non-parallel operations to this group.

Here is the resulting test:

```kotlin
import org.jctools.queues.atomic.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.*

// declare a group of operations that should not be executed in parallel
@OpGroupConfig(name = "consumer", nonParallel = true)
class MPSCQueueTest {
    private val queue = MpscLinkedAtomicQueue<Int>()

    @Operation
    fun offer(x: Int) = queue.offer(x)

    @Operation(group = "consumer") 
    fun poll(): Int? = queue.poll()

    @Operation(group = "consumer")
    fun peek(): Int? = queue.peek()

    @Test
    fun stressTest() = StressOptions().check(this::class)

    @Test
    fun modelCheckingTest() = ModelCheckingOptions().check(this::class)
}
```

Below is an example of the scenario generated for this test. 
Note that all consuming `poll()` and `peek()` operations are called from a single thread; 
thus, satisfying the "single-consumer" contract.


```text
= Iteration 15 / 100 =
Execution scenario (init part):
[offer(1), offer(4), peek(), peek(), offer(-6)]
Execution scenario (parallel part):
| poll()   | offer(6)  |
| poll()   | offer(-1) |
| peek()   | offer(-8) |
| offer(7) | offer(-5) |
| peek()   | offer(3)  |
Execution scenario (post part):
[poll(), offer(-6), peek(), peek(), peek()]

```


> Get the full code [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/MPSCQueueTest.kt).
>
{type="note"}

## What's next

Learn how to check your algorithm for [progress guarantees](progress-guarantees.md) with the model checking mode.