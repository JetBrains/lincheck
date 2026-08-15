[//]: # (title: Testing arbitrary code)
[//]: # (description: Learn how to test any concurrent code with Lincheck's `runConcurrentTest()` function.)

Lincheck provides a `runConcurrentTest()` function to test arbitrary concurrent code.

The `runConcurrentTest()` function executes a block of concurrent code multiple times and uses model checking
to explore its potential execution schedules.

To test concurrent code with Lincheck:

1. Create a test class:

   ```kotlin
   ```
   { src="kotlinx-lincheck/CounterTestWithInvocations.kt" include-lines="8,24" }

2. Create a test function as a member function using `runConcurrentTest()`:

   ```kotlin
   ```
   { src="kotlinx-lincheck/CounterTestWithInvocations.kt" include-symbol="CounterTestWithInvocations.test" }

   > The function parameter is optional; it specifies the number of execution schedules to explore.
   > The default value is `10_000`.
   >
   {style="tip"}

3. Run the test. If it fails, Lincheck generates a report with an execution schedule that leads to incorrect behavior.

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

## Example: test `ConcurrentHashMap` functions

Consider this test for `ConcurrentHashMap` functions:

```kotlin
```
{ src="kotlinx-lincheck/ConcurrentHashMapDeadlockTest.kt" }

The test fails due to Lincheck finding an execution schedule that leads to a deadlock:

1. Thread 2 maps `key2` to the bucket at index 1, places a lock on this bucket, and
   starts executing `computeIfAbsent("key1")`. The execution switches from Thread 2 to
   Thread 1 **before** Thread 2 maps `key1` and locks the bucket with `key1`.
2. Thread 1 maps `key1` to the bucket at index 0, places a lock on this bucket, and
   starts executing `computeIfAbsent("key2")`. Thread 1 maps `key2` to the bucket at
   index 1 and tries to lock the bucket, but it is already locked by Thread 2.
   The execution switches from Thread 1 to Thread 2.
3. Thread 2 tries to lock the bucket with `key1`, but it is already locked by Thread 1.

Both threads are locked, so the execution has encountered a deadlock.

![A screenshot of the Lincheck report for the failed test.](concurrenthashmapdeadlock.png){thumbnail="true" width=700}

## What’s next

Learn how to [test data structures using Lincheck](lincheck-how-to-test-data-structures.md).

## See also

* [Model checking in Lincheck](lincheck-model-checking.md)
* [Lincheck in Kotlin Multiplatform projects](lincheck-kmp.md)