[//]: # (title: Write your first test with Lincheck)

This tutorial demonstrates how to write your first Lincheck test, set up the Lincheck framework, and use its basic API. 
You will create a new IntelliJ IDEA project with an incorrect concurrent counter implementation and write a test for it,
finding and analyzing the bug afterward.

## Create a project

Open an existing Kotlin project in IntelliJ IDEA or [create a new one](https://kotlinlang.org/docs/jvm-get-started.html).
When creating a project, use the Gradle build system.

## Add required dependencies

1. Open the `build.gradle(.kts)` file and make sure that `mavenCentral()` is added to the repository list.
2. Add the following dependencies to the Gradle configuration:

   <tabs group="build-script">
   <tab title="Kotlin" group-key="kotlin">

   ```kotlin
   repositories {
       mavenCentral()
   }
   
   dependencies {
       // Lincheck dependency
       testImplementation("org.jetbrains.lincheck:lincheck:%lincheckVersion%")
       // This dependency allows you to work with kotlin.test and JUnit:
       testImplementation("junit:junit:4.13")
   }
   ```

   </tab>
   <tab title="Groovy" group-key="groovy">
   
   ```groovy
   repositories {
       mavenCentral()
   }
   
   dependencies {
       // Lincheck dependency
       testImplementation "org.jetbrains.lincheck:lincheck:%lincheckVersion%"
       // This dependency allows you to work with kotlin.test and JUnit:
       testImplementation "junit:junit:4.13"
   }
   ```
   </tab>
   </tabs>

## Write a concurrent counter and run the test

1. In the `src/test/kotlin` directory, create a `BasicCounterTest.kt` file and
   add the following code with a buggy concurrent counter and a Lincheck test for it:

   ```kotlin
   import org.jetbrains.lincheck.*
   import org.jetbrains.lincheck.datastructures.*
   import org.junit.*

   class Counter {
       @Volatile
       private var value = 0

       fun inc(): Int = ++value
       fun get() = value
   }

   class BasicCounterTest {
       private val c = Counter() // Initial state
   
       // Operations on the Counter
       @Operation
       fun inc() = c.inc()
   
       @Operation
       fun get() = c.get()
   
       @Test // JUnit
       fun stressTest() = StressOptions().check(this::class) // The magic button
   }
   ```

   This Lincheck test automatically: 
   * Generates several random concurrent scenarios with the specified `inc()` and `get()` operations.
   * Performs a lot of invocations for each of the generated scenarios.
   * Verifies that each invocation result is correct.

2. Run the test above, and you will see the following error:

   ```text
   = Invalid execution results =
   | ------------------- |
   | Thread 1 | Thread 2 |
   | ------------------- |
   | inc(): 1 | inc(): 1 |
   | ------------------- |
   ```

   Here, Lincheck found an execution that violates the counter atomicity – two concurrent increments ended
   with the same result `1`. It means that one increment has been lost, and the behavior of the counter is incorrect.

## Trace the invalid execution

Besides showing invalid execution results, Lincheck can also provide an interleaving that leads to the error. This
feature is accessible with the [model checking](testing-strategies.md#model-checking) testing strategy,
which examines numerous executions with a bounded number of context switches.

1. To switch the testing strategy, replace the `options` type from `StressOptions()` to `ModelCheckingOptions()`.
The updated `BasicCounterTest` class will look like this:

   ```kotlin
   import org.jetbrains.lincheck.*
   import org.jetbrains.lincheck.datastructures.*
   import org.junit.*
   
   class Counter {
       @Volatile
       private var value = 0

       fun inc(): Int = ++value
       fun get() = value
   }

   class BasicCounterTest {
       private val c = Counter()
   
       @Operation
       fun inc() = c.inc()
   
       @Operation
       fun get() = c.get()
   
       @Test
       fun modelCheckingTest() = ModelCheckingOptions().check(this::class)
   }
   ```

2. Run the test again. You will get the execution trace that leads to incorrect results:

   ```text
   = Invalid execution results =
   | ------------------- |
   | Thread 1 | Thread 2 |
   | ------------------- |
   | inc(): 1 | inc(): 1 |
   | ------------------- |
   
   The following interleaving leads to the error:
   | --------------------------------------------------------------------- |
   | Thread 1 |                          Thread  2                         |
   | --------------------------------------------------------------------- |
   |          | inc()                                                      |
   |          |   inc(): 1 at BasicCounterTest.inc(BasicCounterTest.kt:18) |
   |          |     value.READ: 0 at Counter.inc(BasicCounterTest.kt:10)   |
   |          |     switch                                                 |
   | inc(): 1 |                                                            |
   |          |     value.WRITE(1) at Counter.inc(BasicCounterTest.kt:10)  |
   |          |     value.READ: 1 at Counter.inc(BasicCounterTest.kt:10)   |
   |          |   result: 1                                                |
   | --------------------------------------------------------------------- |
   ```

   According to the trace, the following events have occurred:

   * **T2**: The second thread starts the `inc()` operation, reading the current counter value (`value.READ: 0`) and pausing.
   * **T1**: The first thread executes `inc()`, which returns `1`, and finishes.
   * **T2**: The second thread resumes and increments the previously obtained counter value, incorrectly updating the
   counter to `1`.

> [Get the full code](https://github.com/JetBrains/lincheck/blob/master/common/src/test-lincheck-integration/org/jetbrains/lincheck_test/guide/BasicCounterTest.kt).
>
{style="note"}

## Test the Java standard library

Let's now find a bug in the standard Java's `ConcurrentLinkedDeque` class. 
The Lincheck test below finds a race between removing and adding an element to the head of the deque:

```kotlin
import java.util.concurrent.*
import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.datastructures.*
import org.junit.*

class ConcurrentDequeTest {
    private val deque = ConcurrentLinkedDeque<Int>()

    @Operation
    fun addFirst(e: Int) = deque.addFirst(e)

    @Operation
    fun addLast(e: Int) = deque.addLast(e)

    @Operation
    fun pollFirst() = deque.pollFirst()

    @Operation
    fun pollLast() = deque.pollLast()

    @Operation
    fun peekFirst() = deque.peekFirst()

    @Operation
    fun peekLast() = deque.peekLast()

    @Test
    fun modelCheckingTest() = ModelCheckingOptions().check(this::class)
}
```

Run `modelCheckingTest()`. The test will fail with the following output:

```text
= Invalid execution results =
| ---------------------------------------- |
|      Thread 1     |       Thread 2       |
| ---------------------------------------- |
| addLast(22): void |                      |
| ---------------------------------------- |
| pollFirst(): 22   | addFirst(8): void    |
|                   | peekLast(): 22 [-,1] |
| ---------------------------------------- |

---
All operations above the horizontal line | ----- | happen before those below the line
---
Values in "[..]" brackets indicate the number of completed operations
in each of the parallel threads seen at the beginning of the current operation
---

The following interleaving leads to the error:
| --------------------------------------------------------------------------------------------------------------------------------- |
|                                                Thread 1                                                    |       Thread 2       |
| --------------------------------------------------------------------------------------------------------------------------------- |
| pollFirst()                                                                                                |                      |
|   pollFirst(): 22 at ConcurrentDequeTest.pollFirst(ConcurrentDequeTest.kt:17)                              |                      |
|     first(): Node@1 at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:915)                     |                      |
|     item.READ: null at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:917)                     |                      |
|     next.READ: Node@2 at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:925)                   |                      |
|     item.READ: 22 at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:917)                       |                      |
|     prev.READ: null at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:919)                     |                      |
|     switch                                                                                                 |                      |
|                                                                                                            | addFirst(8): void    |
|                                                                                                            | peekLast(): 22       |
|     compareAndSet(Node@2,22,null): true at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:920) |                      |
|     unlink(Node@2) at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:921)                      |                      |
|   result: 22                                                                                               |                      |
| --------------------------------------------------------------------------------------------------------------------------------- |
```

> [Get the full code](https://github.com/JetBrains/lincheck/blob/master/common/src/test-lincheck-integration/org/jetbrains/lincheck_test/guide/ConcurrentLinkedDequeTest.kt).
>
{style="note"}

## Next step

Choose [your testing strategy and configure test execution](testing-strategies.md).

## See also

* [How to generate operation arguments](operation-arguments.md)
* [Popular algorithm constraints](constraints.md)
* [Checking for non-blocking progress guarantees](progress-guarantees.md)
* [Define sequential specification of the algorithm](sequential-specification.md)