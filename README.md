# Lincheck

[![JetBrains official project](https://jb.gg/badges/official.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)
[![License: MPL 2.0](https://img.shields.io/badge/License-MPL_2.0-brightgreen.svg)](https://opensource.org/licenses/MPL-2.0)

Lincheck is a practical and user-friendly framework for writing deterministic and robust concurrent tests on JVM. 
When detecting an error, 
Lincheck provides a reproducible execution trace an ability to debug it step-by-step in IntelliJ IDEA.

> **⚠️ Lincheck 3.0 Changes️:** 
> * Lincheck now focuses on testing arbitrary concurrent code 
    still providing declarative API for testing concurrent data structures
> * The API has been moved from `org.jetbrains.kotlinx.lincheck` to `org.jetbrains.lincheck`
> * The artifact is now published to Maven Central under the `org.jetbrains.lincheck` group ID

## Quick Start

### 1. Add Lincheck dependency
To use Lincheck in your project, you first need to add it as a dependency. If you use Gradle, add the following lines to `build.gradle.kts`:

```kotlin
repositories {
   mavenCentral()
}

dependencies {
   // Lincheck dependency
   testImplementation("org.jetbrains.lincheck:lincheck:2.39")
}
```

### 2. Write your first Lincheck test

To write a Lincheck test,
you need to wrap your concurrent logic with the `Lincheck.runConcurrentTest { ... }` function.
Lincheck will automatically study different thread interleavings and report an error
if one leads to a test failure.

As an example, see the Lincheck test for a counter:

```kotlin
@Test // JUnit test
fun test() = Lincheck.runConcurrentTest {
    var counter = 0
    // Increment the counter concurrently
    val t1 = thread { counter++ }
    val t2 = thread { counter++ }
    // Wait for the threads to finish
    t1.join()
    t2.join()
    // Check both increments have been applied
    assertEquals(2, counter)
}
```

### 3. Run the test

If you run the counter test above, it finishes with an error,
with Lincheck providing a step-by-step execution trace to reproduce it.

```
AssertionFailedError: expected:<2> but was:<1>

The following interleaving leads to the error:
| ------------------------------------------------------------------------------- |
|                   Main Thread                   |   Thread 1    |   Thread 2    |
| ------------------------------------------------------------------------------- |
| thread(block = Lambda#1): Thread#1              |               |               |
| thread(block = Lambda#2): Thread#2              |               |               |
| switch (reason: waiting for Thread 1 to finish) |               |               |
|                                                 |               | run()         |
|                                                 |               |   counter ➜ 0 |
|                                                 |               |   switch      |
|                                                 | run()         |               |
|                                                 |   counter ➜ 0 |               |
|                                                 |   counter = 1 |               |
|                                                 |               |   counter = 1 |
| Thread#1.join()                                 |               |               |
| Thread#2.join()                                 |               |               |
| ------------------------------------------------------------------------------- |
```

## Documentation and Presentations

Please see the [official tutorial](https://kotlinlang.org/docs/lincheck-guide.html) that showcases Lincheck features through examples.

You may also be interested in the following resources:

* ["Lincheck: A Practical Framework for Testing Concurrent Data Structures on JVM"](https://link.springer.com/content/pdf/10.1007/978-3-031-37706-8_8.pdf?pdf=inline%20link) paper by N. Koval, A. Fedorov, M. Sokolova, D. Tsitelov, and D. Alistarh published at CAV '23.
* ["How we test concurrent algorithms in Kotlin Coroutines"](https://youtu.be/jZqkWfa11Js) talk by Nikita Koval at KotlinConf '23.
* "Lincheck: Testing concurrency on the JVM" workshop ([Part 1](https://www.youtube.com/watch?v=YNtUK9GK4pA), [Part 2](https://www.youtube.com/watch?v=EW7mkAOErWw)) by Maria Sokolova at Hydra '21.

## Data Structures Testing

Lincheck provides a special API to simplify testing concurrent data structures. 
Instead of describing how to perform tests, you can specify _what to test_
by just declaring all the data structure operations to examine.
After that, Lincheck automatically generates a set of random concurrent scenarios,
examines them using either stress-testing or bounded model checking, and
verifies that the results of each invocation satisfy the required correctness property (linearizability by default).


### Example 

The following Lincheck test easily finds a bug in the standard Java's `ConcurrentLinkedDeque`:

```kotlin
class ConcurrentLinkedDequeTest {
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

    // Run Lincheck in the stress testing mode
    @Test
    fun stressTest() = StressOptions().check(this::class)

    // Run Lincheck in the model checking testing mode
    @Test
    fun modelCheckingTest() = ModelCheckingOptions().check(this::class)
}
```

When running `modelCheckingTest(),` Lincheck provides a short concurrent scenario 
that discovers the bug with a detailed execution trace to reproduce it.

```text
= Invalid execution results =
| ------------------------------ |
|    Thread 1    |    Thread 2   |
| ------------------------------ |
| addLast(1)     |               |
| ------------------------------ |
| pollFirst(): 1 | addFirst(0):  |
|                | peekLast(): 1 |
| ------------------------------ |

---
All operations above the horizontal line | ----- | happen before those below the line
---

The following interleaving leads to the error:
| ------------------------------------------------------- |
|                Thread 1                 |   Thread 2    |
| ------------------------------------------------------- |
| addLast(1)                              |               |
| ------------------------------------------------------- |
| pollFirst(): 1                          |               |
|   deque.pollFirst(): 1                  |               |
|     first(): Node#2                     |               |
|     p.item ➜ null                       |               |
|     p.next ➜ Node#1                     |               |
|     p.item ➜ 1                          |               |
|     first.prev ➜ null                   |               |
|     switch                              |               |
|                                         | addFirst(0)   |
|                                         | peekLast(): 1 |
|     p.item.compareAndSet(1, null): true |               |
|     unlink(Node#1)                      |               |
|   result: 1                             |               |
| ------------------------------------------------------- |
```

## Contributing 

See [Contributing Guidelines](CONTRIBUTING.md).

## Acknowledgements

This is a fork of the [Lin-Check framework](https://github.com/Devexperts/lin-check) by Devexperts.
