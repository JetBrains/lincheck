[//]: # (title: Sequential specification)

To be sure that the algorithm provides correct sequential behavior, you can define its _sequential specification_
by writing a straightforward sequential implementation of the testing data structure.

> This feature also allows you to write a single test instead of two separate sequential and concurrent tests.
>
{style="tip"}

To provide a sequential specification of the algorithm for verification:

1. Implement a sequential version of all the testing methods.
2. Pass the class with sequential implementation to the `sequentialSpecification()` option:

   ```kotlin
   StressOptions().sequentialSpecification(SequentialQueue::class)
   ```

For example, here is the test to check correctness of `j.u.c.ConcurrentLinkedQueue` from the Java standard library.

```kotlin
import java.util.*
import java.util.concurrent.*
import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.datastructures.*
import org.junit.*

class ConcurrentLinkedQueueTest {
    private val s = ConcurrentLinkedQueue<Int>()

    @Operation
    fun add(value: Int) = s.add(value)

    @Operation
    fun poll(): Int? = s.poll()
   
    @Test
    fun stressTest() = StressOptions()
        .sequentialSpecification(SequentialQueue::class.java)
        .check(this::class)
}

class SequentialQueue {
    private val s = LinkedList<Int>()

    fun add(x: Int) = s.add(x)
    fun poll(): Int? = s.poll()
}
```

> Get the [full code of the examples](https://github.com/JetBrains/lincheck/blob/master/common/src/test-lincheck-integration/org/jetbrains/lincheck_test/guide/ConcurrentLinkedQueueTest.kt).
>
{style="note"}