[//]: # (title: Overview)
[//]: # (description: Lincheck is a framework for testing concurrent code on the JVM. Lincheck explores the potential) 
[//]: # (thread interleavings in your code to find the ones that lead to incorrect behavior.)

Lincheck is a framework for testing concurrent code on the JVM. When running tests, Lincheck explores the potential 
thread interleavings of the program and reports the ones that lead to incorrect behavior.

> In [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform/get-started.html) projects, you can use Lincheck 
> to test code only on the JVM.
{style="note"}

A concurrency test in Lincheck only requires you to list the operations for each thread and the expected assertions. 
Lincheck handles the rest:

```kotlin
class CounterTest {
    @Test // Test function declaration
    fun test() = Lincheck.runConcurrentTest {
        var counter = 0


        // Increments the counter concurrently
        val t1 = thread { counter++ }
        val t2 = thread { counter++ }


        // Waits for the threads to finish
        t1.join()
        t2.join()


        // Checks that both increments have been applied
        assertEquals(2, counter)
    }
}
```

If the test fails, Lincheck provides the thread interleaving and the thread switch points that led to an error:

```text
| ------------------------------------------------------------------------------- |
|                   Main Thread                   |   Thread 1    |   Thread 2    |
| ------------------------------------------------------------------------------- |
| thread(block = Lambda#2): Thread#1              |               |               |
| thread(block = Lambda#3): Thread#2              |               |               |
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
| counter.element ➜ 1                             |               |               |
| assertEquals(2, 1): threw AssertionFailedError  |               |               |
| ------------------------------------------------------------------------------- |
```

## How Lincheck works

Each time the JVM runs concurrent code, the execution order of the operations across threads might change.
For example, an operation can be interrupted by another operation in a different thread. This is not an error by 
itself, but can lead to an error if the code has concurrency bugs.

![The image compares the execution scenario of a program to execution schedules. In the first execution schedule, 
operations are performed one after the other. In the second execution schedule, the first operation is interrupted 
by the second operation.](scenario-vs-schedule.png){ width="700" }

> An _execution scenario_ defines how operations are distributed across the threads and the order of execution 
> within each thread.
> 
> An _execution schedule_ (also called _thread interleaving_) defines the order of execution for all operations across all threads.
{style="tip"}

Lincheck implements two testing strategies to find execution schedules that lead to incorrect behavior:
* **Model checking**. Lincheck controls scheduling by inserting explicit thread switch instructions into the program. 
  These instructions are placed at synchronization points or shared memory accesses. Model checking enables Lincheck 
  to generate an exact execution trace that leads to an error.
* **Stress testing**. The operating system controls scheduling. Lincheck executes each scenario multiple times to 
  increase the chances of finding an error.

## Explore Lincheck

* Learn Lincheck features step-by-step in the [Lincheck getting started](lincheck-getting-started.md).
* Learn about the declarative approach to testing concurrent data structures in the [Testing strategies](testing-strategies.md) article.

## Learn more

* "How we test concurrent algorithms in Kotlin Coroutines" by Nikita Koval: [Video](https://youtu.be/jZqkWfa11Js). 
  KotlinConf 2023
* "Lincheck: Testing concurrency on the JVM" workshop by Maria Sokolova: 
  [Part 1](https://www.youtube.com/watch?v=YNtUK9GK4pA), [Part 2](https://www.youtube.com/watch?v=EW7mkAOErWw). 
  Hydra 2021
* "Lincheck: A Practical Framework for Testing Concurrent Data Structures on JVM" by Nikita Koval et al.: 
  [Paper](https://nikitakoval.org/publications/cav23-lincheck.pdf). 2023