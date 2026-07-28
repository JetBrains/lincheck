[//]: # (title: Progress guarantees)
[//]: # (description: Learn how to check an algorithm for obstruction-freedom in Lincheck.)

Many concurrent algorithms provide [non-blocking progress guarantees](https://en.wikipedia.org/wiki/Non-blocking_algorithm), 
such as wait-freedom, lock-freedom, or obstruction-freedom.

Lincheck only supports verification of obstruction-freedom. However, because lock-free and wait-free algorithms 
are also obstruction-free, any violation of obstruction-freedom also indicates a violation of those stronger guarantees.

Use the `checkObstructionFreedom` option to verify the obstruction-freedom guarantee of the program:

```kotlin
```
{src="kotlinx-lincheck/NonBlockingGuaranteesTests.kt" include-symbol="ConcurrentSkipListMapTest.modelCheckingTest"}

> The `checkObstructionFreedom` option is only available for the
> [model checking](lincheck-testing-strategies.md#model-checking) strategy.
>
{style="warning"}

Lincheck verifies obstruction-freedom by checking whether a thread can make progress when all other threads are paused. 
If a thread's execution gets [stuck in a loop](lincheck-testing-strategies-options.md#stalled-execution-detection), 
Lincheck reports an active lock.

If certain functions are intentionally blocking, you can mark them with
[`@Operation(blocking = true)`](lincheck-operation-execution-options.md#blocking-operations) to prevent false positives.

## Example: test `ConcurrentHashMap` for obstruction-freedom

In this example, you will test the `put()` function of a `ConcurrentHashMap` structure.

1. Create a `ConcurrentHashMapTest.kt` file.
2. Create a test class for `ConcurrentHashMap`, declare the `put()` function and a test 
   function with the `checkObstructionFreedom()` option enabled:

   ```kotlin
   ```
   {src="kotlinx-lincheck/NonBlockingGuaranteesTests.kt" include-symbol="ConcurrentHashMapTest"}
   
   The [`threads`](lincheck-testing-strategies-options.md#scenario-generation) and [`actorsPerThread`](lincheck-testing-strategies-options.md#scenario-generation) 
   options are used to reduce the number of potential execution scenarios. These options do not change the pass/fail 
   state of the test, but they significantly reduce the testing time. 

3. Run the test. It should fail with the following report:

   ```text
   = The algorithm should be non-blocking, but an active lock is detected =
   | --------------------- |
   | Thread 1  | Thread 2  |
   | --------------------- |
   | put(1, 0) | put(1, 1) |
   | --------------------- |
   
   The following interleaving leads to the error:
   | -------------------------------------------------------------------------------------------------------------- |
   |                                          Thread 1                                          |     Thread 2      |
   | -------------------------------------------------------------------------------------------------------------- |
   | put(1, 0): <hung>                                                                          |                   |
   |   map.put(1, 0)                                                                            |                   |
   |     putVal(1, 0, false)                                                                    |                   |
   |       spread(1): 1                                                                         |                   |
   |       table ➜ null                                                                         |                   |
   |       loop(1 iterations) at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1016)          |                   |
   |         <iteration 1>                                                                      |                   |
   |           initTable()                                                                      |                   |
   |             loop(1 iterations) at ConcurrentHashMap.initTable(ConcurrentHashMap.java:2293) |                   |
   |             table ➜ null                                                                   |                   |
   |             switch                                                                         |                   |
   |                                                                                            | put(1, 1): <hung> |
   | -------------------------------------------------------------------------------------------------------------- |
   ```

4. Add the `blocking = true` option to the `put()` function annotation:

   ```kotlin
   ```
   {src="kotlinx-lincheck/NonBlockingGuaranteesTests.kt" include-symbol="ConcurrentHashMapWithBlockingTest.put"}

5. Rerun the test. It should pass successfully.

## Example: test `ConcurrentSkipListMap` for obstruction-freedom

In this example, you will test the `put()` function of a non-blocking `ConcurrentSkipListMap` structure.

1. Create a `ConcurrentSkipListMapTest.kt` file.
2. Create a test class for `ConcurrentSkipListMap`, declare the `put()` function and a test function with 
   the `checkObstructionFreedom()` option enabled:

   ```kotlin
   ```
   {src="kotlinx-lincheck/NonBlockingGuaranteesTests.kt" include-symbol="ConcurrentSkipListMapTest"}

3. Run the test. It should pass successfully.

## See also

* [Configuring argument generation constraints](lincheck-argument-generation-constraints.md)
* [Configuring operation execution](lincheck-operation-execution-options.md)
* [Validating execution results](lincheck-results-validation.md)


