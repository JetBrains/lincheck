[//]: # (title: Stress testing and model checking)

Lincheck offers two testing strategies: stress testing and model checking. Learn what happens under the hood of both
approaches using the `Counter` we coded in the `BasicCounterTest.kt` file in the [previous step](introduction.md):

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

Create a concurrent stress test for the `Counter`, following these steps:

1. Create the `CounterTest` class.
2. In this class, add the field `c` of the `Counter` type, creating an instance in the constructor.
3. List the counter operations and mark them with the `@Operation` annotation, delegating their implementations to `c`.
4. Specify the stress testing strategy using `StressOptions()`.
5. Invoke the `StressOptions.check()` function to run the test.

The resulting code will look like this:

```kotlin
import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.datastructures.*
import org.junit.*

class CounterTest {
    private val c = Counter() // Initial state
    
    // Operations on the Counter
    @Operation
    fun inc() = c.inc()

    @Operation
    fun get() = c.get()

    @Test // Run the test
    fun stressTest() = StressOptions().check(this::class)
}
```

### How stress testing works {initial-collapse-state="collapsed" collapsible="true"}

At first, Lincheck generates a set of concurrent scenarios using the operations marked with `@Operation`. Then it launches
native threads, synchronizing them at the beginning to guarantee that operations start simultaneously. Finally, Lincheck
executes each scenario on these native threads multiple times, expecting to hit an interleaving that produces incorrect results.

The figure below shows a high-level scheme of how Lincheck may execute generated scenarios:

![Stress execution of the Counter](counter-stress.png){width=700}

## Model checking

The main concern regarding stress testing is that you may spend hours trying to understand how to reproduce the found bug.
To help you with that, Lincheck supports bounded model checking, which automatically provides an interleaving for
reproducing bugs.

A model checking test is constructed the same way as the stress test. Just replace the `StressOptions()`
that specify the testing strategy with `ModelCheckingOptions()`.

### Write a model checking test

To change the stress testing strategy to model checking, replace `StressOptions()` with `ModelCheckingOptions()` in your
test:

```kotlin
import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.datastructures.*
import org.junit.*

class CounterTest {
    private val c = Counter() // Initial state

    // Operations on the Counter
    @Operation
    fun inc() = c.inc()

    @Operation
    fun get() = c.get()

    @Test // Run the test
    fun modelCheckingTest() = ModelCheckingOptions().check(this::class)
}
```

### How model checking works {initial-collapse-state="collapsed" collapsible="true"}

Most bugs in complicated concurrent algorithms can be reproduced with classic interleavings, switching the execution from
one thread to another. Besides, model checkers for weak memory models are very complicated, 
so Lincheck uses a bounded model checking under the _sequential consistency memory model_.

In short, Lincheck analyzes all interleavings, starting with one context switch, then two, continuing the
process until the specified number of interleaving is examined. This strategy allows finding an incorrect schedule with
the lowest possible number of context switches, making further bug investigation easier. 

To control the execution, Lincheck inserts special switch points into the testing code. These points identify where
a context switch can be performed. Essentially, these are shared memory accesses, such as field and array element reads
or updates in the JVM, as well as `wait/notify` and `park/unpark` calls. To insert a switch point, Lincheck transforms
the testing code on the fly using the ASM framework, adding internal function invocations to the existing code.

As the model checking strategy controls the execution, Lincheck can provide the trace that leads to the invalid
interleaving, which is extremely helpful in practice. You can see the example of trace for the incorrect execution of the `Counter` in
the [Write your first test with Lincheck](introduction.md#trace-the-invalid-execution) tutorial.

## Which testing strategy is better?

The _model checking strategy_ is preferable for finding bugs under the sequentially consistent memory model since it
ensures better coverage and provides a failing execution trace if an error is found.

Although _stress testing_ doesn't guarantee any coverage, checking algorithms for bugs introduced by low-level effects,
such as a missed `volatile` modifier, is still helpful. Stress testing is also a great help in discovering rare
bugs that require many context switches to reproduce, and it's impossible to analyze them all due to the current
restrictions in the model checking strategy.

## Configure the testing strategy

To configure the testing strategy, set options in the `<TestingMode>Options` class.

1. Set the options for scenario generation and execution for the `CounterTest`:

    ```kotlin
    import org.jetbrains.lincheck.*
    import org.jetbrains.lincheck.datastructures.*
    import org.junit.*
    
    class CounterTest {
        private val c = Counter()
    
        @Operation
        fun inc() = c.inc()
    
        @Operation
        fun get() = c.get()
    
        @Test
        fun stressTest() = StressOptions() // Stress testing options:
            .actorsBefore(2) // Number of operations before the parallel part
            .threads(2) // Number of threads in the parallel part
            .actorsPerThread(2) // Number of operations in each thread of the parallel part
            .actorsAfter(1) // Number of operations after the parallel part
            .iterations(100) // Generate 100 random concurrent scenarios
            .invocationsPerIteration(1000) // Run each generated scenario 1000 times
            .check(this::class) // Run the test
    }
    ```

2. Run `stressTest()` again, Lincheck will generate scenarios similar to the one below:

   ```text 
   | ------------------- |
   | Thread 1 | Thread 2 |
   | ------------------- |
   | inc()    |          |
   | inc()    |          |
   | ------------------- |
   | get()    | inc()    |
   | inc()    | get()    |
   | ------------------- |
   | inc()    |          |
   | ------------------- |
   ```

   Here, there are two operations before the parallel part, two threads for each of the two operations,
   followed after that by a single operation in the end.

You can configure your model checking tests in the same way.

## Scenario minimization

You may already have noticed that detected errors are usually represented with a scenario smaller than the specified
in the test configuration. Lincheck tries to minimize the error, actively removing an operation
while it's possible to keep the test from failing.

Here's the minimized scenario for the counter test above:

```text
= Invalid execution results =
| ------------------- |
| Thread 1 | Thread 2 |
| ------------------- |
| inc()    | inc()    |
| ------------------- |
```

As it's easier to analyze smaller scenarios, scenario minimization is enabled by default. To disable this feature,
add `minimizeFailedScenario(false)` to the `[Stress, ModelChecking]Options` configuration.

## Logging data structure states

Another useful feature for debugging is _state logging_. When analyzing an interleaving that leads to an error,
you usually draw the data structure changes on a sheet of paper, changing the state after each event.
To automize this procedure, you can provide a special method that returns a `String` representation of the data structure,
so Lincheck prints the state representation after each event in the interleaving that modifies the data structure.

For this, define a method that doesn't take arguments and is marked with the `@StateRepresentation` annotation.
The method should be thread-safe, non-blocking, and never modify the data structure.

1. In the `Counter` example, the `String` representation is simply the value of the counter. Thus, to print the counter
states in the trace, add the `stateRepresentation()` function to the `CounterTest`:

    ```kotlin
    import org.jetbrains.lincheck.*
    import org.jetbrains.lincheck.datastructures.*
    import org.junit.Test

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

2. Run the `modelCheckingTest()` now and check the states of the `Counter` 
   printed at the switch points that modify the counter state (they start with `STATE:`):

    ```text
    = Invalid execution results =
    | ------------------- |
    | Thread 1 | Thread 2 |
    | ------------------- |
    | STATE: 0            |
    | ------------------- |
    | inc(): 1 | inc(): 1 |
    | ------------------- |
    | STATE: 1            |
    | ------------------- |
    
    The following interleaving leads to the error:
    | -------------------------------------------------------------------- |
    | Thread 1 |                         Thread 2                          |
    | -------------------------------------------------------------------- |
    |          | inc()                                                     |
    |          |   inc(): 1 at CounterTest.inc(CounterTest.kt:10)          |
    |          |     value.READ: 0 at Counter.inc(BasicCounterTest.kt:10)  |
    |          |     switch                                                |
    | inc(): 1 |                                                           |
    | STATE: 1 |                                                           |
    |          |     value.WRITE(1) at Counter.inc(BasicCounterTest.kt:10) |
    |          |     STATE: 1                                              |
    |          |     value.READ: 1 at Counter.inc(BasicCounterTest.kt:10)  |
    |          |   result: 1                                               |
    | -------------------------------------------------------------------- |
    ```

In case of stress testing, Lincheck prints the state representation right before and after the parallel part of the scenario,
as well as at the end.

> * Get the [full code of these examples](https://github.com/JetBrains/lincheck/tree/master/src/jvm/test-lincheck-integration/org/jetbrains/lincheck_test/guide/CounterTest.kt)
> * See more [test examples](https://github.com/JetBrains/lincheck/tree/master/src/jvm/test/org/jetbrains/lincheck_test/guide)
>
{style="note"}

## Next step

Learn how to [configure arguments passed to the operations](operation-arguments.md) and when it can be useful.
