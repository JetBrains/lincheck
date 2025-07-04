[//]: # (title: Data structure constraints)

Some data structures may require a part of operations not to be executed concurrently, such as single-producer
single-consumer queues. Lincheck provides out-of-the-box support for such contracts, generating concurrent scenarios
according to the restrictions.

Consider the [single-consumer queue](https://github.com/JCTools/JCTools/blob/66e6cbc9b88e1440a597c803b7df9bd1d60219f6/jctools-core/src/main/java/org/jctools/queues/atomic/MpscLinkedAtomicQueue.java)
from the [JCTools library](https://github.com/JCTools/JCTools). Let's write a test to check correctness of its `poll()`,
`peek()`, and `offer(x)` operations.

In your `build.gradle(.kts)` file, add the JCTools dependency:

   <tabs group="build-script">
   <tab title="Kotlin" group-key="kotlin">

   ```kotlin
   dependencies {
       // jctools dependency
       testImplementation("org.jctools:jctools-core:%jctoolsVersion%")
   }
   ```

   </tab>
   <tab title="Groovy" group-key="groovy">

   ```groovy
   dependencies {
       // jctools dependency
       testImplementation "org.jctools:jctools-core:%jctoolsVersion%"
   }
   ```
   </tab>
   </tabs>

To meet the single-consumer restriction, ensure that all `poll()` and `peek()` consuming operations
are called from a single thread. For that, we can set the `nonParallelGroup` parameter of the 
corresponding `@Operation` annotations to the same value, e.g. `"consumers"`.

Here is the resulting test:

```kotlin
import org.jctools.queues.atomic.*
import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.datastructures.*
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
| --------------------- |
| Thread 1  | Thread 2  |
| --------------------- |
| poll()    |           |
| poll()    |           |
| peek()    |           |
| peek()    |           |
| peek()    |           |
| --------------------- |
| offer(-1) | offer(0)  |
| offer(0)  | offer(-1) |
| peek()    | offer(-1) |
| offer(1)  | offer(1)  |
| peek()    | offer(1)  |
| --------------------- |
| peek()    |           |
| offer(-2) |           |
| offer(-2) |           |
| offer(2)  |           |
| offer(-2) |           |
| --------------------- |
```

Note that all consuming `poll()` and `peek()` invocations are performed from a single thread, thus satisfying the
"single-consumer" restriction.

> [Get the full code](https://github.com/JetBrains/lincheck/blob/master/common/src/test-lincheck-integration/org/jetbrains/lincheck_test/guide/MPSCQueueTest.kt).
>
{style="note"}

## Next step

Learn how to [check your algorithm for progress guarantees](progress-guarantees.md) with the model checking strategy.