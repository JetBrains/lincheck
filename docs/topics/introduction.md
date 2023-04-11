[//]: # (title: Write your first test with Lincheck)

This tutorial demonstrates how to write your first Lincheck test, set up the Lincheck framework, and use its basic API. 
You will create a new IntelliJ IDEA project with an incorrect concurrent counter implementation and write a test for it,
finding and analyzing the bug afterward.

## Create a project

1. Open an existing Kotlin project in IntelliJ IDEA or [create a new one](https://kotlinlang.org/docs/jvm-get-started.html).
When creating a project, use the Gradle build system.
2. In the `src/main/kotlin` directory, open the `main.kt` file.
3. Replace the code in `main.kt` with the following counter implementation:

    ```kotlin
    class Counter {
        @Volatile
        private var value = 0
   
        fun inc(): Int = ++value
        fun get() = value
    }
    ```

    Your Lincheck test will check whether the counter is thread-safe.

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
       testImplementation("org.jetbrains.kotlinx:lincheck:%lincheckVersion%")
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
       testImplementation "org.jetbrains.kotlinx:lincheck:%lincheckVersion%"
       // This dependency allows you to work with kotlin.test and JUnit:
       testImplementation "junit:junit:4.13"
   }
   ```
   </tab>
   </tabs>

## Write and run the test

1. In the `src/test/kotlin` directory, create a `BasicCounterTest.kt` file and add the following code:

   ```kotlin
   import org.jetbrains.kotlinx.lincheck.annotations.*
   import org.jetbrains.kotlinx.lincheck.*
   import org.jetbrains.kotlinx.lincheck.strategy.stress.*
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
   * Generates several random concurrent scenarios with the specified `inc()` and `dec()` operations.
   * Performs a lot of invocations for each of the generated scenarios.
   * Verifies that each invocation result is correct.

2. Run the test above, and you will see the following error:

   ```text
   = Invalid execution results =
   Parallel part:
   | inc(): 1 | inc(): 1 |
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
   import org.jetbrains.kotlinx.lincheck.annotations.*
   import org.jetbrains.kotlinx.lincheck.check
   import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
   import org.jetbrains.kotlinx.lincheck.verifier.*
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
       fun getAndInc() = c.getAndInc()
   
       @Operation
       fun get() = c.get()
   
       @Test
       fun modelCheckingTest() = ModelCheckingOptions().check(this::class)
   }
   ```

2. Run the test again. You will get the execution trace that leads to incorrect results:

   ```text
   = Invalid execution results =
   Parallel part:
   | inc(): 1 | inc(): 1 |
   = The following interleaving leads to the error =
   Parallel part trace:
   |                      | inc()                                                      |
   |                      |   inc(): 1 at BasicCounterTest.inc(BasicCounterTest.kt:11) |
   |                      |     value.READ: 0 at Counter.inc(Counter.kt:5)             |
   |                      |     switch                                                 |
   | inc(): 1             |                                                            |
   |   thread is finished |                                                            |
   |                      |     value.WRITE(1) at Counter.inc(Counter.kt:5)            |
   |                      |     value.READ: 1 at Counter.inc(Counter.kt:5)             |
   |                      |   result: 1                                                |
   |                      |   thread is finished                                       |
   ```

   According to the trace, the following events have occurred:

   * **T2**: The second thread starts the `inc()` operation, reading the current counter value (`value.READ: 0`) and pausing.
   * **T1**: The first thread executes `inc()`, which returns `1`, and finishes.
   * **T2**: The second thread resumes and increments the previously obtained counter value, incorrectly updating the
   counter to `1`.

> [Get the full code](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/BasicCounterTest.kt).
>
{type="note"}

## Test the Java standard library

Let's now find a bug in the standard Java's `ConcurrentLinkedDeque` class. 
The Lincheck test below finds a race between removing and adding an element to the head of the deque:

```kotlin
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.junit.*
import java.util.concurrent.*

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
Init part:
[addLast(4): void]
Parallel part:
| pollFirst(): 4 | addFirst(-4): void       |
|                | peekLast():   4    [-,1] |
---
values in "[..]" brackets indicate the number of completed operations 
in each of the parallel threads seen at the beginning of the current operation
---

= The following interleaving leads to the error =
Parallel part trace:
| pollFirst()                                                                                               |                      |
|   pollFirst(): 4 at ConcurrentDequeTest.pollFirst(ConcurrentDequeTest.kt:39)                              |                      |
|     first(): Node@1 at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:915)                    |                      |
|     item.READ: null at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:917)                    |                      |
|     next.READ: Node@2 at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:925)                  |                      |
|     item.READ: 4 at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:917)                       |                      |
|     prev.READ: null at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:919)                    |                      |
|     switch                                                                                                |                      |
|                                                                                                           | addFirst(-4): void   |
|                                                                                                           | peekLast(): 4        |
|                                                                                                           |   thread is finished |
|     compareAndSet(Node@2,4,null): true at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:920) |                      |
|     unlink(Node@2) at ConcurrentLinkedDeque.pollFirst(ConcurrentLinkedDeque.java:921)                     |                      |
|   result: 4                                                                                               |                      |
|   thread is finished                                                                                      |                      |
```

> [Get the full code](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/ConcurrentLinkedDequeTest.kt).
>
{type="note"}

## Next step

Choose [your testing strategy and configure test execution](testing-strategies.md).

## See also

* [How to generate operation arguments](operation-arguments.md)
* [Popular algorithm constraints](constraints.md)
* [Checking for non-blocking progress guarantees](progress-guarantees.md)
* [Define sequential specification of the algorithm](sequential-specification.md)