[//]: # (title: Sequential specification)

### Sequential specification

To be sure that the algorithm provides correct sequential behavior, you can define its _sequential specification_
by writing a straightforward sequential implementation of the testing data structure.

> This feature also allows writing a single test instead of writing sequential and concurrent tests separately.
>
{type="tip"}

To provide a sequential specification of the algorithm for verification:

1. Implement a sequential version of all the testing methods.
2. Pass the class with sequential implementation to the `sequentialSpecification` option:

   ```kotlin
   StressOptions().sequentialSpecification(SequentialQueue::class)
   ```

For example, here is the test to check correctness of `j.u.c.ConcurrentLinkedQueue` 
from the Java standard library.

```kotlin
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import java.util.*
import java.util.concurrent.*

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
   val s = LinkedList<Int>()

   fun add(x: Int) = s.add(x)
   fun poll(): Int? = s.poll()
}
```

### State equivalency

In the beginning of the test output, you are likely see an advice to specify the state equivalence relation on your sequential
specification in order to make the verification faster.

The reason is that during verification, Lincheck builds a transition graph, invoking operations of the provided sequential
implementation: the states represent the data structure states and the transitions are labeled
with operations and the corresponding results. Several transition sequences may lead to the same state. 
For example, applying `poll()` after `add(2)` leads back to the initial state of the queue.

When you define the equivalency relation between the states of the data structure by implementing `equals()`
and `hashCode()` methods on its sequential implementation (by default, the testing data structure serves its purpose), 
the number of states in the transition graph decrease significantly, speeding up the verification process.

Lincheck provides the following API to do that:

1. Make the test class extend `VerifierState`.
2. Override `extractState()` function: it should define the state of a data structure.

   > `extractState()` is called once and can modify the data structure.
   >
   {type="note"}

3. Turn on the `requireStateEquivalenceImplCheck` option:

   ```kotlin
   StressOptions().requireStateEquivalenceImplCheck(true)
   ```

Defining state equivalence for `ConcurrentLinkedQueueTest` looks like this:

```kotlin
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import java.util.*
import java.util.concurrent.*

class ConcurrentLinkedQueueTest {
   private val s = ConcurrentLinkedQueue<Int>()

   @Operation
   fun add(value: Int) = s.add(value)

   @Operation
   fun poll(): Int? = s.poll()

   @Test
   fun stressTest() = StressOptions()
      .sequentialSpecification(SequentialQueue::class.java)
      .requireStateEquivalenceImplCheck(true)
      .check(this::class)
}

class SequentialQueue : VerifierState() {
   val q = LinkedList<Int>()

   fun add(x: Int) = q.add(x)
   fun poll(): Int? = q.poll()

   override fun extractState(): Any {
      val elements = mutableListOf<Int>()
      while(q.size != 0) {
         elements.add(q.poll())
      }
      return elements.toString()
   }
}
```

> Get the full code of the examples [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/ConcurrentLinkedQueueTest.kt).
>
{type="note"}
