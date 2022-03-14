[//]: # (title: Write your first test — tutorial)

This tutorial demonstrates how to use IntelliJ IDEA for creating a test using basic Lincheck API. You'll learn how to set
up a project and try out different testing modes.

## Create a project

1. Open a Kotlin project in IntelliJ IDEA. If you don't have one, [create a new project](https://kotlinlang.org/docs/jvm-get-started.html).

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
   Your new Lincheck test will check whether this counter is thread-safe.

## Add required dependencies

Add Lincheck as a dependency to your project:

1. Open the `build.gradle(.kts)` file and make sure that you have `mavenCentral()` in the list of repositories.
2. Add the following dependencies to the Gradle configuration:

   <tabs group="build-script">
   <tab title="Kotlin" group-key="kotlin">
   
   ```kotlin
   repositories {
       mavenCentral()
   }
   
   dependencies {
       // Lincheck dependency
       testImplementation("org.jetbrains.kotlinx:lincheck:2.13")
   
       // This dependency will allow you to work with kotlin.test and JUnit:
       testImplementation("junit:junit:4.12")
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
       testImplementation "org.jetbrains.kotlinx:lincheck:2.13"
   
       // This dependency will allow you to work with kotlin.test and JUnit:
       testImplementation "junit:junit:4.12"
   }
   ```
   </tab>
   </tabs>

## Write and run the test

1. In the `src/test/kotlin` directory, create a `CounterTest.kt` file and add the following code:

   ```kotlin
   import org.jetbrains.kotlinx.lincheck.annotations.Operation
   import org.jetbrains.kotlinx.lincheck.check
   import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
   import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
   import org.junit.Test
   
   class CounterTest {
       private val c = Counter() // initial state
   
       // operations on the Counter
       @Operation
       fun inc() = c.inc()
   
       @Operation
       fun get() = c.get()
   
       @Test // JUnit
       fun test() = StressOptions().check(this::class) // the magic button
   }
   ```

   This is a basic Lincheck test that automatically:

   * Generates several random concurrent scenarios
   * Examines each of them performing a lot of scenario invocations
   * Verifies that each of the observed invocation results is correct
   
2. Run the test above, and you will see the following error:

   ```text
   = Invalid execution results =
   Parallel part:
   | inc(): 1 | inc(): 1 |
   ```

Here, Lincheck found an execution that violates atomicity of the `Counter` — two concurrent increments ended
with the same result `1`. It means that one increment was lost, and the behavior of the counter is incorrect.

## Trace the invalid execution

Besides the invalid execution results, it is also possible to find the exact interleaving that leads to the error. This
feature is accessible with the [model checking](testing-strategies.md#model-checking) testing strategy, which examines many different interleavings with a bounded
number of context switches.

1. To switch the testing strategy, replace the `options` type from `StressOptions()` to `ModelCheckingOptions()`.
   The updated `CounterTest` class will look like this:

   ```kotlin
   import org.jetbrains.kotlinx.lincheck.annotations.Operation
   import org.jetbrains.kotlinx.lincheck.check
   import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
   import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
   import org.junit.Test
   
   class CounterTest {
       private val c = Counter()
   
       @Operation
       fun getAndInc() = c.getAndInc()
   
       @Operation
       fun get() = c.get()
   
       @Test
       fun test() = ModelCheckingOptions().check(this::class)
   }
   ```

2. Re-run the test. You will get the execution trace leading to incorrect results:

   ```text
   = Invalid execution results =
   Parallel part:
   | inc(): 1 | inc(): 1 |
   = The following interleaving leads to the error =
   Parallel part trace:
   |                      | inc()                                                 |
   |                      |   inc(): 1 at CounterTest.inc(CounterTest.kt:11)     |
   |                      |     value.READ: 0 at Counter.inc(Counter.kt:5)  |
   |                      |     switch                                            |
   | inc(): 1             |                                                       |
   |   thread is finished |                                                       |
   |                      |     value.WRITE(1) at Counter.inc(Counter.kt:5) |
   |                      |     value.READ: 1 at Counter.inc(Counter.kt:5)  |
   |                      |   result: 1                                           |
   |                      |   thread is finished                                  |
   ```

   In this trace, the following list of events have occurred:

   1. **T2:** The second thread reads the current value of the counter (`value.READ: 0`) and pauses.
   2. **T1:** The first thread executes `inc()`, which returns `1`, and finishes.  
   3. **T2:** The second thread increments the previously read value of the counter and incorrectly updates it to `1`,
  returning `1` as a result.

> Get the full code of the example [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/CounterTest.kt).
>
{type="note"}

## What's next

Choose [your testing strategy and configure test execution](testing-strategies.md).