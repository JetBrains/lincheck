[//]: # (title: Stress testing and model checking)

Lincheck provides two testing strategies: stress testing and model checking.
Learn what happens under the hood of both the testing strategies 
and the test structure in general with the `Counter` example from the previous section:

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

1. Create a new `Counter` instance as an initial state.
2. List operations on the `Counter` and mark them with the `@Operation` annotation.
3. Specify the stress testing strategy using the corresponding `StressOptions()` class.
4. Invoke the `StressOptions.check(..)` function to start the testing.

The code is presented below:

```kotlin
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.*

class Counter {
    @Volatile
    private var value = 0

    fun inc(): Int = ++value
    fun get() = value
}

class CounterTest {
    private val c = Counter() // initial state

    @Operation
    fun inc() = c.inc()

    @Operation
    fun get() = c.get()

    @Test // run the test
    fun stressTest() = StressOptions().check(this::class)
}
```

### How the stress testing works

First, Lincheck generates a set of concurrent scenarios using the operations marked with `@Operation`.
After that, it launches native threads, synchronizing them at the start to guarantee that they start simultaneously,
and executes each of the scenarios on these native threads multiple times in the hope to hit an interleaving that produces
incorrect results.

The figure below shows a high-level scheme of how a generated scenarios may execute.

![Stress execution of the Counter](counter-stress.png){width=700}

## Model checking
The main practical concern regarding stress testing is that you may spend 
countless hours trying to understand how to reproduce the discovered bug.
To help with this procedure, Lincheck supports bounded model checking, which
automatically provides an interleaving that reproduces the found bug.

### Write a model checking test

To change the stress testing strategy to the model checking one, 
you simply need to replace `StressOptions()` with `ModelCheckingOptions()`.
See the full code of the resulting test below.

```kotlin
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.junit.*

class Counter {
    @Volatile
    private var value = 0

    fun inc(): Int = ++value
    fun get() = value
}

class CounterTest {
    private val c = Counter() // initial state

    // operations on the Counter
    @Operation
    fun inc() = c.inc()

    @Operation
    fun get() = c.get()

    @Test // run the test
    fun modelCheckingTest() = ModelCheckingOptions().check(this::class)
}
```

> To use model checking strategy for Java 9 and later, add the following JVM properties:
>
> ```text
> --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
> --add-exports java.base/jdk.internal.util=ALL-UNNAMED
> ```
>
> They are required if the testing code uses classes from `java.util` package since
> some of them use `jdk.internal.misc.Unsafe` or similar internal classes under the hood.
>
{type="tip"}

### How the model checking works
In our experience, most bugs in complicated concurrent algorithms can be reproduced using the sequential consistency memory model. 
Simultaneously, the model-checking approaches for weak memory models are very complicated, so Lincheck uses a bounded model checking 
under the _sequential consistency memory model_. 

In short, Lincheck starts by studying all interleavings with one context switch but does this evenly, 
trying to explore a variety of interleavings simultaneously. This way, we increase the total coverage 
if the number of available invocations is not sufficient to cover all the interleavings. Once all the 
interleavings with one context switch have been reviewed, Lincheck starts examining interleavings with 
two context switches, and repeats the process, increasing the number of context switches each time, 
until the available invocations exceed the maximum or all possible interleavings are covered. 
This strategy increases the testing coverage and allows you to find an incorrect schedule with 
the lowest number of context switches possible, marking a significant improvement for bug investigation. 

Lincheck inserts switch points into the testing code to control the execution. These points identify 
where a context switch can be performed. The interesting switch point locations are shared memory accesses, 
such as field and array element reads or updates in the JVM, as well as `wait/notify` and `park/unpark` calls.
To insert a switch point, Lincheck transforms the testing code via the ASM framework and adds internal function 
invocations before each access. The transformation is performed on the fly.

As the model checking strategy fully controls the execution, Lincheck is able to provide a trace 
that leads to the invalid interleaving, which is extremely helpful in practice.
Please see the example of such a trace for the incorrect execution of the `Counter` in
[Write your first test](introduction.md#trace-the-invalid-execution) tutorial section.

## Which testing strategy is better?
The _model checking strategy_ is preferable for finding bugs under the sequentially consistent memory model since it
ensures better coverage and provides a failing execution trace if an error is found.

Although _stress testing_ does not guarantee any coverage, it is still helpful to check algorithms for bugs introduced by
low-level effects, such as a missed `volatile` modifier. Stress testing is also a great help in discovering very rare
bugs that require many context switches to reproduce, and it is not possible to analyze all of them due to the current
restrictions in the model checking strategy.

## Configure the testing strategy

To configure the testing strategy, set options in the `<TestingMode>Options` class.

Set the options for scenario generation and execution for the `CounterTest`:

```kotlin
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

class Counter {
    @Volatile
    private var value = 0

    fun inc(): Int = ++value
    fun get() = value
}

class CounterTest {
    private val c = Counter()

    @Operation
    fun inc() = c.inc()

    @Operation
    fun get() = c.get()

    @Test
    fun stressTest() = StressOptions() // stress testing options
        .actorsBefore(2) // number of operations before the parallel part
        .threads(2) // number of threads in the parallel part
        .actorsPerThread(2) // number of operations in each thread of the parallel part
        .actorsAfter(1) // number of operations after the parallel part
        .iterations(100) // generate 100 random concurrent scenarios
        .invocationsPerIteration(1000) // run each generated scenario 1000 times
        .check(this::class) // run the test
}
```

If you run `stressTest()` again, Lincheck generates scenarios similar to the one below: 
two operations before the parallel part, two threads with two operations in each after that 
followed by a single operation in the end.

```text 
Init part:
[inc(), inc()]
Parallel part:
| get() | inc() |
| inc() | get() |
Post part:
[inc()]
```

Similarly, you are able to configure your model checking tests.

## Scenario Minimization

You may already have noticed that detected errors are usually
represented with a scenario smaller than specified in the test configuration.
Lincheck tries to minimize the error, greedy removing an operation
while it is possible to keep the test to fail.
The listing below shows the minimized scenario for the counter test above.

```text
= Invalid execution results =
Parallel part:
| inc(): 1 | inc(): 1 |
```

To turn off the scenario minimization feature, add `minimizeFailedScenario(false)`
to the `[Stress,ModelChecking]Options` configuration. It is easier to analyze
smaller scenarios, so minimization is enabled by default.


## Logging data structure states
Another feature that is extremely useful for debugging is _state logging_.
When analyzing an interleaving that leads to an error, you usually draw
how the data structure changes on a sheet of paper, re-drawing the state
after each two-three of events. To automize the procedure, you can provide
a special method that returns a `String` representation of the data structure,
so Lincheck prints the state representation after each event in the interleaving
that modifies the data structure. The corresponding method should not take arguments
and should be marked with a special `@StateRepresentation` annotation.

> The method should be thread-safe, non-blocking, and never modify the data structure.
>
{type="note"}

Regarding the `Counter` example, its `String` representation is simply the value of the counter.
Thus, to print the counter states in the trace, we need to add the following
`stateRepresentation` function to the `CounterTest`:

```kotlin
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.junit.Test

class Counter {
    @Volatile
    private var value = 0

    fun inc(): Int = ++value
    fun get() = value
}

class CounterTest {
    private val c = Counter()

    @Operation
    fun inc() = c.inc()

    @Operation
    fun get() = c.get()
    
    @StateRepresentation
    fun stateRepresentation() = c.get().toString()
    
    @Test
    fun modelCheckingTest() = ModelCheckingOptions().check(this::class)
}
```

Run the `modelCheckingTest()` now, and check the states of the `Counter` 
printed at the switch points that modify the counter state (they start with `STATE:`):

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

In case of stress testing, Lincheck prints the state representation
right before and after the parallel part of the scenario as well as at its very end.

> * Get the full code of the examples [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/CounterTest.kt)
> * Get more test examples [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/StackTest.kt)
>
{type="note"}

## What's next

* Learn how to [configure arguments passed to the operations](operation-arguments.md) and when it is useful.
* See how to optimize and increase coverage of the model checking strategy via [modular testing](modular-testing.md).