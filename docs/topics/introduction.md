[//]: # (title: Write your first test with Lincheck)

This tutorial demonstrates how to set up the Lincheck framework and use its basic API.  
You will create a new IntelliJ IDEA project with an incorrect concurrent counter implementation 
and write your first Lincheck test for it, finding and analyzing the bug after that.


## Create a project

1. Open an existing Kotlin project in IntelliJ IDEA or [create a new one](https://kotlinlang.org/docs/jvm-get-started.html).

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
   Further, your will write a Lincheck test to check whether the counter is thread-safe.

## Add required dependencies

1. Open the `build.gradle(.kts)` file and make sure that `mavenCentral()` is added to the repository list.

2. Add the following dependencies to the Gradle configuration:

   <tabs group="build-script">
   <tab title="Groovy" group-key="groovy">
   
   ```groovy
   repositories {
       mavenCentral()
   }
   
   dependencies {
       // Lincheck dependency
       testImplementation "org.jetbrains.kotlinx:lincheck:2.14.1"
       // This dependency will allow you to work with kotlin.test and JUnit
       testImplementation "junit:junit:4.13"
   }
   ```
   </tab>
   </tabs>

## Write a Lincheck test

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
       private val c = Counter() // initial state
   
       // operations on the Counter
       @Operation
       fun inc() = c.inc()
   
       @Operation
       fun get() = c.get()
   
       @Test // JUnit
       fun stressTest() = StressOptions().check(this::class) // the magic button
   }
   ```

2. Congratulations! This is your first Lincheck test for the counter implementation above. 
   In short, it automatically 
   (1) generates several random concurrent scenarios with the specified `inc()` and `dec()` operations,
   (2) performs a lot of invocations for each generated scenario, and
   (3) verifies that each of the invocation results is correct.


## Run the test

Now it is time to run the test. When you do it, you will see the following error:

   ```text
   = Invalid execution results =
   Parallel part:
   | inc(): 1 | inc(): 1 |
   ```

   Lincheck found an execution that violates the counter atomicity — two concurrent increments ended
   with the same result `1`, showing that one of the increment has been lost.

## Trace the invalid execution

In addition to the invalid execution results, finding the exact interleaving that leads to the error is also possible with Lincheck.
The feature is accessible with the [model checking](testing-strategies.md#model-checking) testing mode 
that examines numerous executions with a bounded number of context switches.

1. To switch the testing strategy from stress testing to model checking, 
   replace `StressOptions()` with `ModelCheckingOptions()`.
   The updated `BasicCounterTest` implementation is presented below:

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

2. Run the test again and get the execution trace that leads to the incorrect results:

   ```text
   = Invalid execution results =
   Parallel part:
   | inc(): 1 | inc(): 1 |
   = The following interleaving leads to the error =
   Parallel part trace:
   |                      | inc()                                                 |
   |                      |   inc(): 1 at BasicCounterTest.inc(BasicCounterTest.kt:11)     |
   |                      |     value.READ: 0 at Counter.inc(Counter.kt:5)  |
   |                      |     switch                                            |
   | inc(): 1             |                                                       |
   |   thread is finished |                                                       |
   |                      |     value.WRITE(1) at Counter.inc(Counter.kt:5) |
   |                      |     value.READ: 1 at Counter.inc(Counter.kt:5)  |
   |                      |   result: 1                                           |
   |                      |   thread is finished                                  |
   ```

   According to the trace, the following events occur:

   **T2:** The second thread starts the `inc()` operation, reading the current counter value (`value.READ: 0`) and pausing.

   **T1:** The first thread executes `inc()`, which returns `1`, and finishes.  

   **T2:** The second thread resumes and increments the previously obtained counter value, incorrectly updating the counter to `1`.

> Get the full code [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/BasicCounterTest.kt).
>
{type="note"}

## What's next

[Stress testing and model checking strategies](testing-strategies.md).