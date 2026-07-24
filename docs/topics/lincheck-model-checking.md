[//]: # (title: Model checking)
[//]: # (description: Learn how model checking works in Lincheck and its limitations.)

To test the code using the model checking strategy, Lincheck inserts explicit thread-switch instructions at points of 
shared-memory access (`read` and `write`) or at synchronization points, such as lock acquisition and release, 
`park`/`unpark`, `wait`/`notify`, and others. This approach enables Lincheck to controllably explore the execution 
schedules of a program and find the ones that lead to incorrect results.

When testing concurrent code using model checking, Lincheck makes sure that the exploration of the execution 
schedules is:
* [Deterministic](#deterministic-exploration) – each invocation of a model checking test returns the same result if 
  the input data has not changed.
* [Bounded](#bounded-exploration) – each test explores only a [limited number of execution schedules](lincheck-testing-strategies-options.md#scenario-generation).
  Otherwise, the number of possible execution schedules would exponentially grow with the size of the program, which significantly increases testing times.

Compared to stress testing, model checking enables Lincheck to collect execution traces and guarantees bug reproduction 
for failed tests. For a more detailed comparison, see the table in the [Testing strategies](lincheck-testing-strategies.md)
article.

## Deterministic exploration

Model checking in Lincheck requires that, given the same execution schedule, the code under test produces the same 
results if the input data has not changed. Deterministic execution in model checking is what enables Lincheck to 
collect execution traces and guarantees bug reproduction for failed tests, which means that any non-deterministic
code prevents model checking from working properly.

If an execution produces a non-deterministic result, Lincheck reports an error:

```text
Non-determinism found. Probably caused by non-deterministic code (WeakHashMap, Object.hashCode, etc).
== Reporting the first execution without execution trace ==
= Invalid execution results =
| -------- |
| Thread 1 |
| -------- |
| inc(): 2 |
| -------- |

== Reporting the second execution ==
= Invalid execution results =
| -------- |
| Thread 1 |
| -------- |
| inc(): 3 |
| -------- |
```

Some sources of non-determinism are controlled by Lincheck, while others are either restricted for usage or 
might produce an unexpected test failure.

### Controlled sources of non-determinism

When running a test with the model checking strategy, Lincheck controls the following sources of non-determinism:

* **Thread-switching** – instead of relying on the JVM for thread-switching, Lincheck inserts explicit thread-switch 
  instructions at points of shared-memory access (`read` and `write`) or at synchronization points, such as lock 
  acquisition and release, `park`/`unpark`, `wait`/`notify`, and others.
* **Random number generators** – Lincheck fixes the random seed.
* **Identity hash codes** – Lincheck fixes the [identity hash codes](https://docs.oracle.com/javase/8/docs/api/java/lang/System.html#identityHashCode-java.lang.Object) 
  of objects.
* **Time API calls** – Lincheck intercepts the time API calls and [returns deterministic results](#time-api-calls).
* **Global variables** – Lincheck resets the values of global variables between invocations in model checking tests:

  ```kotlin
  @TestMethodOrder(MethodOrderer.OrderAnnotation::class)
  class GlobalVariableResetTest {
      companion object {
          private var atomicInt = AtomicInteger(0)
      }
  
      @Test
      @Order(1)
      fun modelCheckingTest() = Lincheck.runConcurrentTest {
          val t1 = thread { atomicInt.getAndIncrement() }
          val t2 = thread { atomicInt.getAndIncrement() }
  
          t1.join()
          t2.join()
  
          check(atomicInt.get() == 2)
      }
  
      @Test
      @Order(2)
      fun resetAfterModelCheckingTest() {
          // Verify `atomicInt` has been reset to 0 after `modelCheckingTest()`
          check(atomicInt.get() == 0)
      }
  
      @Test
      @Order(3)
      fun regularIncTest() {
          atomicInt.getAndIncrement()
          check(atomicInt.get() == 1)
      }
  
      @Test
      @Order(4)
      fun valuePersistsAfterRegularIncTest() {
          // Verify `atomicInt` still holds 1 after `regularIncTest()`
          check(atomicInt.get() == 1)
      }
  }
  ```

### Uncontrolled sources of non-determinism

Lincheck does not control the following sources of non-determinism:

* **Thread-local variables** – Lincheck does not reset thread-local variables between the invocations of a test as it 
  does with global variables. See an example of an error caused by the usage of thread-local variables and a workaround 
  in a [dedicated section](#thread-local-variables).
* **Weak references** – Lincheck does not control when a garbage collector removes objects that are only referenced 
  by weak references. Calling `get()` on such objects produces non-deterministic results. The test might pass successfully 
  despite the use of weak references, but if Lincheck encounters an [inconsistency between the runs of the same test](#deterministic-exploration),
  it will raise a non-determinism error.
* **I/O API calls** – Lincheck does not support calls to I/O APIs, including operations on files and sockets. 
  Calling I/O APIs leads to `java.lang.IllegalStateException`. See an example in a [dedicated section](#i-o-api-calls).

## Bounded exploration

The number of possible execution schedules grows exponentially with the size of the program, which increases testing 
times. Lincheck circumvents this problem by limiting the [number of explored execution schedules](lincheck-testing-strategies-options.md#scenario-generation).
If the number of possible execution schedules exceeds the specified limit, Lincheck stops the exploration. 
In such cases, Lincheck tries to evenly analyze logically different schedules:

* Lincheck applies context bounding by first exploring schedules with one preemptive context switch, then two, and so on.
* When choosing the next schedule to explore, Lincheck prioritizes schedules with context switches in new locations.

  For example, see how Lincheck models execution schedules with a single context switch in a two-thread scenario:

  ![A diagram of execution schedules modeled by Lincheck for the same concurrent scenario.](model-checking.svg){width=700}

  Because Lincheck starts by modeling a schedule with a context switch in the first thread, the next modeled schedule 
  is more likely to have a context switch in the second thread. This continues until Lincheck either reaches the limit 
  of explored schedules or exhausts all possible schedules.

## Known limitations and workarounds

The model checking strategy has the following known limitations.

### Relaxed Java memory model

> Vote for the related issue and track its progress on [GitHub](https://github.com/JetBrains/lincheck/issues/370).
>
{style="tip"}

Model checking requires Lincheck to assume a
[sequentially consistent memory model](https://en.wikipedia.org/wiki/Sequential_consistency) of the execution.
This means that Lincheck does not simulate and cannot catch bugs related to instruction reordering, memory cache 
behavior, and other similar effects under the relaxed [Java memory model](https://en.wikipedia.org/wiki/Java_memory_model).

Most concurrency bugs can be found even under this assumption, however, Lincheck can miss some bugs caused by low-level 
effects. For example, a missing `@Volatile` modifier might produce a bug caused by store buffer or a compiler reordering, 
which cannot be caught by Lincheck’s model checker:

```kotlin
class RelaxedMemoryModelTest {
    var x = 0 // Not @Volatile
    var y = 0 // Not @Volatile

    @Test
    fun modelCheckingTest() = Lincheck.runConcurrentTest {
        thread {
            x = 1
            y = 1
        }
        thread {
            if (y == 1 && x == 0) {
                // Code in this block might be executed on real hardware because of
                // store buffer and compiler reordering.
                // Lincheck cannot model this behavior with model checking.
                error("Unreachable under sequential consistency")
           }
        }
    }
}
```

#### Workaround

If you want to test concurrent code without the assumption of a sequentially consistent memory model, Lincheck provides 
a [stress testing strategy](lincheck-testing-strategies.md#stress-testing) for concurrent data structures.

### Threads created outside the scenario (coroutines, `ForkJoinPool`)

> Vote for the related issue and track its progress on [GitHub](https://github.com/JetBrains/lincheck/issues/388).
>
{style="tip"}

Lincheck can only track the threads created inside a concurrent scenario. It can miss bugs occurring in externally 
created threads, such as when using [coroutines](https://kotlinlang.org/docs/coroutine-context-and-dispatchers.html) or
Java's [`ForkJoinPool`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ForkJoinPool.html).

#### Workaround {id="workaround-externally-created-threads"}

Local dispatchers/thread pools guarantee that Lincheck can track the lifecycle and activity of threads in a 
concurrent scenario.

<tabs>
 <tab id="coroutines" title="Coroutines">
     <code-block lang="Kotlin">
class FixedThreadPoolTest {
    @Test
    fun test() = Lincheck.runConcurrentTest {
        val dispatcher = Executors.newFixedThreadPool(nThreads).asCoroutineDispatcher()
        runBlocking(dispatcher) {
            val coro = launch() {
                while (isActive) { /* ... */ }
            }
            coro.cancel()
            coro.join()
        }
    }
}
</code-block>
 </tab>
 <tab id="forkjoinpool" title="ForkJoinPool">
     <code-block lang="Kotlin">
class FixedThreadPoolTest {
    @Test
    fun test() = Lincheck.runConcurrentTest {
        val executorService = Executors.newFixedThreadPool(nThreads)
        try {
            val task = object : Runnable { /* ... */ }
            val future1 = executorService.submit(task)
            val future2 = executorService.submit(task)
            future1.get()
            future2.get()
        } finally {
            executorService.shutdown()
        }
    }
}
</code-block>
 </tab>
</tabs>

### Thread-local variables

> Vote for the related issue and track its progress on [GitHub](https://github.com/JetBrains/lincheck/issues/571).
>
{style="tip"}

Lincheck does not reset thread-local variables during multiple invocations of the same scenario (unlike it does 
with [global variables](#controlled-sources-of-non-determinism)). This leads to inconsistencies between the runs of the same test.

Example:

```kotlin
class ThreadLocalVariableTest {
    @Test
    fun modelCheckingTest() = Lincheck.runConcurrentTest {
        var counter = getLocalCounter()
        var t = thread { counter.getAndIncrement() }
        t.join()
        check(counter.get() == 1)
    }

    private fun getLocalCounter() = localCounter.get()
}

// Using ThreadLocal to create a variable leads to a failed test
private val localCounter: ThreadLocal<AtomicInteger> = ThreadLocal.withInitial {
    AtomicInteger(0)
}
```

This test fails with an error because the value of the counter accumulates across scenario invocations:

```text
| ---------------------------------------------------------------------------------------- |
|                   Main Thread                   |                Thread 1                |
| ---------------------------------------------------------------------------------------- |
| getLocalCounter(): AtomicInteger#1              |                                        |
| thread(block = Lambda#1): Thread#1              |                                        |
| switch (reason: waiting for Thread 1 to finish) |                                        |
|                                                 | run()                                  |
|                                                 |   counter ➜ AtomicInteger#1            |
|                                                 |   AtomicInteger#1.getAndIncrement(): 2 |
| Thread#1.join()                                 |                                        |
| counter.element ➜ AtomicInteger#1               |                                        |
| AtomicInteger#1.get(): 3                        |                                        |
| ---------------------------------------------------------------------------------------- |
```

#### Workaround {id="workaround-thread-local-variables"}

Create thread-local variables manually by storing values in a `ConcurrentHashMap` with thread IDs as key values:

```kotlin
class ThreadLocalVariableTest {
    val threadLocalCounters = ConcurrentHashMap<Long, AtomicInteger>()
  
    // ...

    private fun getLocalCounter() = threadLocalCounters.computeIfAbsent(Thread.currentThread().id) {
        AtomicInteger(0)
    }
}
```

Because `threadLocalCounters` is an instance field of the test class, Lincheck resets it between invocations, 
avoiding the accumulation problem.

### Weak references

> Vote for the related issue and track its progress on [GitHub](https://github.com/JetBrains/lincheck/issues/279).
>
{style="tip"}

Lincheck does not control when a garbage collector removes objects that are only referenced by weak references.
Calling `get()` on such objects produces non-deterministic results. The test might pass successfully despite the use
of weak references, but if Lincheck encounters an [inconsistency between the runs of the same test](#deterministic-exploration), 
it will raise a non-determinism error.

### Time API calls

> Vote for the related issue and track its progress on [GitHub](https://github.com/JetBrains/lincheck/issues/390).
>
{style="tip"}

Lincheck models calls of `java.lang.System.nanoTime()` and `java.lang.System.currentTimeMillis()` by always returning
a predefined constant to [prevent inconsistencies between the runs of the same test](#deterministic-exploration).

This approach might not correctly model timeouts, elapsed-time comparisons, rate-limiting, or other intended behavior.

### I/O API calls

> Vote for the related issue and track its progress on [GitHub](https://github.com/JetBrains/lincheck/issues/1027).
>
{style="tip"}

Lincheck does not support calls to I/O APIs, including operations on files and sockets to [prevent inconsistencies 
between the runs of the same test](#deterministic-exploration).

Calling I/O APIs leads to `java.lang.IllegalStateException`:

```kotlin
class FilesCreateTempFileTest {
    @Operation
    fun operation(): List<String> = List(10) {
        val tempFile = Files.createTempFile("test-prefix", ".txt")
        require(Files.exists(tempFile)) { "File was not created: $tempFile" }
        tempFile.toString()
    }

    // The test fails with the following error message:
    // "java.lang.IllegalStateException: File operations are not supported in Lincheck"
    @Test
    fun modelChecking() = ModelCheckingOptions().check(this::class)
}
```

### Virtual threads

> Vote for the related issue and track its progress on [GitHub](https://github.com/JetBrains/lincheck/issues/261).
>
{style="tip"}

Support for [virtual threads](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html) has not been 
verified and is not guaranteed. Consider using traditional threads for scenarios requiring model checking until support 
is verified.

## See also

* Learn how to [test any concurrent code](lincheck-testing-arbitrary-code.md) with model checking.
* Learn how to use model checking to [test a concurrent data structure](lincheck-how-to-test-data-structures.md).
* Learn how to [configure a testing strategy](lincheck-testing-strategies-options.md) in Lincheck.
* Learn how [model checking compares to stress testing](lincheck-testing-strategies.md).

