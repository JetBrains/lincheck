[//]: # (title: Testing strategies: stress testing and model checking)

Lincheck provides two testing strategies: stress testing and model checking. They are preferable for different use-cases.

Learn about the test structure and what happens under the hood for both testing strategies using this example of
the `Counter`:

```kotlin
class Counter {
    @Volatile
    private var value = 0

    fun inc(): Int = ++value
    fun get() = value
}
```

## Stress testing

### Write a stress test

Follow this structure of the stress test for the `Counter`:

1. Create an instance of the `Counter` as an initial state.
2. List operations defined on the `Counter` and mark them with the `@Operation` annotation.
3. Specify the stress testing strategy using the corresponding `StressOptions()` class.
4. Invoke the `StressOptions().check()` on the testing class to run the analysis.

The full code of the test will look like this:

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

    @Test // run the test
    fun stressTest() = StressOptions().check(this::class)
}
```

### How stress testing works

At first, some parallel execution scenario is generated from the operations that were marked with the `@Operation`
annotation.

Then Lincheck starts real threads, actively synchronizes them to guarantee that the operations are performed in
parallel, and executes the operations repeating the `run` many times to hit an interleaving that produces an
incorrect result.

This is a high-level picture of how one of the stress executions for the `Counter` may be performed.

![Stress execution of the Counter](counter-stress.png){width=700}

## Model checking

When you find the incorrect execution of your algorithm using the stress testing strategy, you can still spend hours
trying to figure out how this incorrect execution could happen. This process is automated with the model checking
strategy.

### Write a model checking test

A model checking test is constructed the same way as the stress test. Just replace the `StressOptions()`
that specify the testing strategy with `ModelCheckingOptions()`.

Find below the `modelCheckingTest()` function that will run the test using the model checking strategy:

```kotlin
@Test
fun modelCheckingTest() = ModelCheckingOptions().check(this::class)
```

> To use model checking strategy for Java 9 and later, add the following JVM properties:
>
> ```text
> --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
> --add-exports java.base/jdk.internal.util=ALL-UNNAMED
> ```
>
> They are required if the testing code uses classes from `java.util` package since some of them use `jdk.internal.misc.Unsafe`
> or similar internal classes under the hood.
>
{type="tip"}

### How model checking works

Model checking strategy is provided for the algorithms under _the sequential consistency memory model_. In the model
checking strategy, Lincheck examines many different interleavings within a bounded number of context switches.

Interesting switch point locations in the code include: shared memory accesses, such as field and array element reads or
updates in JVM, synchronization primitives like lock acquisition and release, `park` / `unpark` (stops and resumes the
specified thread, respectively), `wait`/`notify`, and some others.

Model checking strategy controls the execution. Therefore, Lincheck can provide the trace that leads to the invalid
interleaving, and it's extremely helpful in practice.

You can see the example of trace for the incorrect execution of the `Counter` in
the [Write your first test](introduction.md#Trace-the-invalid-execution) tutorial.

## Which testing strategy to choose?

The _model checking strategy_ is preferable for finding bugs under the sequentially consistent memory model since it
ensures better coverage and provides a failing execution trace if an error is found.

Although _stress testing_ doesn't guarantee any coverage, it's still helpful to check algorithms for bugs introduced by
low-level effects, such as a missed `volatile` modifier. Stress testing is also a great help in discovering very rare
bugs that require many context switches to reproduce, and it's not possible to analyze all of them due to the current
restrictions in the model checking strategy.

## Configure the testing strategy

To configure the testing strategy, set options in the `<TestingMode>Options` class.

Set the options for scenario generation and execution for the `CounterTest`:

```kotlin
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

//sampleStart
class CounterTest {
    private val c = Counter()

    @Operation
    fun inc() = c.inc()

    @Operation
    fun get() = c.get()

    @Test
    fun stressTest() = StressOptions() // stress testing options
        .actorsBefore(2) // Init part
        .threads(2).actorsPerThread(2) // Parallel part
        .actorsAfter(1) // Post part
        .minimizeFailedScenario(false) // Turn off minimization of the invalid scenario
        .invocationsPerIteration(1000) // Run each scenario 1000 times
        .check(this::class)
}
//sampleEnd
```

If you run `stressTest()` again, the output will look like this:

```text 
= Invalid execution results =
Init part:
[inc(): 1, inc(): 2]
Parallel part:
| get(): 2 | inc(): 3       |
| inc(): 3 | get(): 3 [2,1] |
Post part:
[inc(): 4]
---
values in "[..]" brackets indicate the number of completed operations 
in each of the parallel threads seen at the beginning of the current operation
---
```

Note that the `minimizeFailedScenario` option is turned off to see the test failure on the scenario that was originally
generated according to the set options.

However, most bugs can be reproduced with fewer operations than in the initially generated scenario, and it's a lot
easier to debug the minimal invalid scenario. So, the `minimizeFailedScenario` option is enabled by default.

The minimized invalid execution for this example is:

```text
= Invalid execution results =
Parallel part:
| inc(): 1 | inc(): 1 |
```

## Logging data structure states

Another feature useful for debugging is state logging. For this, define a method returning a `String`
representation of the data structure marked with `@StateRepresentation` in the test class.

> This method should be thread-safe, non-blocking, and not modify the data structure.
>
{type="note"}

The `String` representation of the `Counter` is just its value. Add the following code to the `CounterTest`:

```kotlin
@StateRepresentation
fun counterReperesentation() = c.get().toString()
```

For stress testing, the state representation is requested after the init, post-execution, and parallel parts. For model checking, the state representation may be printed after each read or write event.

Run the `modelCheckingTest()` now, and check the state of the `Counter` printed at the switch points (`STATE:<value>`):

```text
= Invalid execution results =
STATE: 0
Parallel part:
| inc(): 1 | inc(): 1 |
STATE: 1
= The following interleaving leads to the error =
Parallel part trace:
|                      | inc()                                                |
|                      |   inc(): 1 at CounterTest.inc(CounterTest.kt:42)     |
|                      |     value.READ: 0 at Counter.inc(CounterTest.kt:35)  |
|                      |     switch                                           |
| inc(): 1             |                                                      |
| STATE: 1             |                                                      |
|   thread is finished |                                                      |
|                      |     value.WRITE(1) at Counter.inc(CounterTest.kt:35) |
|                      |     STATE: 1                                         |
|                      |     value.READ: 1 at Counter.inc(CounterTest.kt:35)  |
|                      |   result: 1                                          |
|                      |   thread is finished                                 |
```

> * Get the full code of examples from the section [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/CounterTest.kt)
> * Get more test examples [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/StackTest.kt)
>
{type="note"}

## What's next

* Learn how to [configure arguments passed to test operations](parameter-generation.md) and when it may be useful.
* See how to optimize and increase coverage of the model checking strategy using [modular testing](modular-testing.md).
* Learn how to use Lincheck for testing [blocking data structures](blocking-data-structures.md).