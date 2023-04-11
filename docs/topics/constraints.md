[//]: # (title: Data structure constraints)

Some data structures may require a part of operations not to be executed concurrently, such as single-producer
single-consumer queues. Lincheck provides out-of-the-box support for such contracts, generating concurrent scenarios
according to the restrictions.

Consider the [single-consumer queue](https://github.com/JCTools/JCTools/blob/66e6cbc9b88e1440a597c803b7df9bd1d60219f6/jctools-core/src/main/java/org/jctools/queues/atomic/MpscLinkedAtomicQueue.java)
from the [JCTools library](https://github.com/JCTools/JCTools). Let's write a test to check correctness of its `poll()`,
`peek()`, and `offer(x)` operations.

To meet the single-consumer restriction, ensure that all `poll()` and `peek()` consuming operations
are called from a single thread. For that, we can set the `nonParallelGroup` parameter of the 
corresponding `@Operation` annotations to the same value, e.g. `"consumers"`.

Here is the resulting test:

```kotlin
import org.jctools.queues.atomic.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.*

class MPSCQueueTest {
    private val queue = MpscLinkedAtomicQueue<Int>()

    @Operation
    fun offer(x: Int) = queue.offer(x)

    @Operation(nonParallelGroup = "consumers") 
    fun poll(): Int? = queue.poll()

    @Operation(nonParallelGroup = "consumers")
    fun peek(): Int? = queue.peek()

    @Test
    fun stressTest() = StressOptions().check(this::class)

    @Test
    fun modelCheckingTest() = ModelCheckingOptions().check(this::class)
}
```

Here is an example of the scenario generated for this test:

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

Note that all consuming `poll()` and `peek()` invocations are performed from a single thread, thus satisfying the
"single-consumer" restriction.

> [Get the full code](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/MPSCQueueTest.kt).
>
{type="note"}

## Next step

Learn how to [check your algorithm for progress guarantees](progress-guarantees.md) with the model checking strategy.