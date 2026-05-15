[//]: # (title: Testing strategies)
[//]: # (description: Learn the differences between model checking and stress testing in Lincheck.)

Lincheck provides two strategies for testing concurrent data structures: model checking and stress testing.

In this article, you will learn the difference between the strategies and what to keep in mind when choosing 
a testing strategy.

## Model checking

With model checking, Lincheck simulates possible thread interleavings and reports those that cause incorrect 
behavior.

To use model checking for testing a data structure, declare a test function using `ModelCheckingOptions()`:

```kotlin
@Test
fun modelCheckingTest() = ModelCheckingOptions()
   .check(this::class)
```

When using the model checking strategy, Lincheck inserts explicit thread-switch instructions at points of 
shared-memory access (`read` and `write`) or at synchronization points, such as lock acquisition and release, 
`park`/`unpark`, `wait`/`notify`, and others.

Controlling the thread switching enables Lincheck to:

* Deterministically explore different possible execution schedules of a program.
* Provide detailed execution traces.

Currently, model checking requires Lincheck to assume a 
[sequentially consistent memory model](https://en.wikipedia.org/wiki/Sequential_consistency) of the processor. 
This means that Lincheck does not simulate and cannot catch bugs related to instruction reordering, memory 
cache behavior, and other similar effects under the relaxed 
[Java memory model](https://en.wikipedia.org/wiki/Java_memory_model).

<!-- TODO: uncomment after the article is published
> For more information, see [Model checking](lincheck-model-checking.md).
>
{style=”tip”}
-->

## Stress testing

With stress testing, Lincheck executes each scenario multiple times to increase the chances of finding an error.

To use stress testing, declare a test function using `StressOptions()`:

```kotlin
@Test
fun stressTest() = StressOptions()
   .check(this::class)
```

Unlike with model checking, Lincheck does not control or track thread switches. This makes stress testing 
faster and does not require Lincheck to make any assumptions about the memory model.
However, with stress testing, tests are not reproducible, and Lincheck cannot provide an execution trace.

## Choose a strategy

When choosing a strategy, consider the following:

<table style="both">
    <tr>
        <td></td>
        <td><b>Model checking</b></td>
        <td><b>Stress testing</b></td>
    </tr>
    <tr>
        <td><b>Speed</b></td>
        <td>Slower.</td>
        <td>Faster.</td>
    </tr>
    <tr>
        <td><b>Reproducibility</b></td>
        <td>The tests return exactly the same results if the input data has not changed.</td>
        <td>The tests might return different results as the thread schedules might change from run to run.</td>
    </tr>
    <tr>
        <td><b>Assumptions</b></td>
        <td><list>
            <li>Assumes a sequentially consistent memory model.</li>
            <li>Will miss bugs caused by incorrect behavior outside of that model.</li>
        </list></td>
        <td><list>
            <li>Does not make any assumptions about a memory model.</li>
            <li>Has a chance to catch any incorrect behavior regardless of the underlying cause.</li>
        </list></td>
    </tr>
    <tr>
        <td><b>Verbosity</b></td>
        <td>Reports both a concurrent scenario and an execution trace that led to incorrect behavior.</td>
        <td>Reports only a concurrent scenario.</td>
    </tr>
    <tr>
        <td><b>Standard library coverage</b></td>
        <td><list>
            <li>Does not simulate the behavior of some standard library features, such as weak references.</li>
            <li>Will miss bugs caused by such features.</li>
        </list></td>
        <td>Has a chance to catch a bug caused by the usage of any feature.</td>
    </tr>
</table>

## What’s next

Learn how to [configure a testing strategy](lincheck-testing-strategies-options.md) by customizing scenario 
generation, enabling stalled execution detection, and providing thread-safety guarantees for libraries.

## See also

* [Generating operation arguments](operation-arguments.md)
* [Configuring algorithm constraints](constraints.md)
* [Checking for non-blocking progress guarantees](progress-guarantees.md)
* [Defining sequential specification of the algorithm](sequential-specification.md)