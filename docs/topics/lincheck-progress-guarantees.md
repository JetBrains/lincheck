[//]: # (title: Progress guarantees)
[//]: # (description: Learn how to check an algorithm for obstruction-freedom in Lincheck.)

Many concurrent algorithms provide [non-blocking progress guarantees](https://en.wikipedia.org/wiki/Non-blocking_algorithm), 
such as wait-freedom, lock-freedom, or obstruction-freedom.

Lincheck only supports verification of obstruction-freedom. However, because lock-free and wait-free algorithms 
are also obstruction-free, any violation of obstruction-freedom also indicates a violation of those stronger guarantees.

Use the `checkObstructionFreedom` option to verify the obstruction-freedom guarantee of the program:

```kotlin
@Test
fun modelCheckingTest() = ModelCheckingOptions()
   .checkObstructionFreedom()
   .check(this::class)
```

> The `checkObstructionFreedom` option is only available for the
> [model checking](lincheck-testing-strategies.md#model-checking) strategy.
>
{style="warning"}

Lincheck verifies obstruction-freedom by checking whether a thread can make progress when all other threads are paused. 
If a thread's execution gets [stuck in a loop](lincheck-testing-strategies-options.md#stalled-execution-detection), 
Lincheck reports an active lock.

<!-- TODO: replace the paragraph when `lincheck-operation-execution-options.md` is merged 
If certain operations are intentionally blocking, you can mark them with
[`@Operation(blocking = true)`](lincheck-operation-execution-options.md#blocking-operations) to prevent false positives.-->
If certain operations are intentionally blocking, you can mark them with `@Operation(blocking = true)` to 
prevent false positives.

## Example: test `ConcurrentHashMap` for obstruction-freedom

In this example, you will test the `put()` function of a `ConcurrentHashMap` structure.

1. Create a `ConcurrentHashMapTest.kt` file.
2. Create a test class for the `ConcurrentHashMap` structure and declare the `put()` function:

  ```kotlin
  class ConcurrentHashMapTest {
       private val map = ConcurrentHashMap<Int, Int>()

       @Operation
       fun put(key: Int, value: Int) = map.put(key, value)
   }
   ```

3. Declare a test function with the `checkObstructionFreedom()` option enabled:

  ```kotlin
  @Test
   fun modelCheckingTest() = ModelCheckingOptions()
       .checkObstructionFreedom()
       .check(this::class)
   ```

4. Run the test. It should fail with the following report:

  ```text
  = The algorithm should be non-blocking, but an active lock is detected =
   | ---------------------- |
   | Thread 1  |  Thread 2  |
   | ---------------------- |
   | put(1, 0) | put(1, -2) |
   | ---------------------- |
 
   The following interleaving leads to the error:
   | ----------------------------------------------------------------------------------------------------- |
   |                   Thread 1                    |                       Thread 2                        |
   | ----------------------------------------------------------------------------------------------------- |
   | put(1, 0): <hung>                             |                                                       |
   |   map.put(1, 0)                               |                                                       |
   |     putVal(1, 0, false)                       |                                                       |
   |       spread(1): 1                            |                                                       |
   |       table ➜ null                            |                                                       |
   |       initTable()                             |                                                       |
   |         table ➜ null                          |                                                       |
   |         sizeCtl ➜ 0                           |                                                       |
   |         sizeCtl.compareAndSetInt(0, -1): true |                                                       |
   |         table ➜ null                          |                                                       |
   |         switch                                |                                                       |
   |                                               | put(1, -2): <hung>                                    |
   |                                               |   map.put(1, -2)                                      |
   |                                               |     putVal(1, -2, false)                              |
   |                                               |       spread(1): 1                                    |
   |                                               |       table ➜ null                                    |
   |                                               |       initTable()                                     |
   |                                               |         /* The following events repeat infinitely: */ |
   |                                               |     ┌╶> table ➜ null                                  |
   |                                               |     |   sizeCtl ➜ -1                                  |
   |                                               |     |   Thread.yield()                                |
   |                                               |     └╶╶ /* An active lock was detected */             |
   | ----------------------------------------------------------------------------------------------------- |
   ```

5. Add the `blocking = true` option to the `put()` function annotation:

   ```kotlin
   @Operation(blocking = true)
   fun put(key: Int, value: Int) = map.put(key, value)
   ```

6. Rerun the test. It should pass successfully.

## Example: test `ConcurrentSkipListMap` for obstruction-freedom

In this example, you will test the `put()` function of a non-blocking `ConcurrentSkipListMap` structure.

1. Create a `ConcurrentSkipListMapTest.kt` file.
2. Create a test class for the `ConcurrentSkipListMap` structure and declare the `put()` operation:

  ```kotlin
  class ConcurrentSkipListMapTest {
       private val map = ConcurrentSkipListMap<Int, Int>()
  
       @Operation
       fun put(key: Int, value: Int) = map.put(key, value)
   }
   ```

3. Declare a test function with the `checkObstructionFreedom()` option enabled:

  ```kotlin
  @Test
   fun modelCheckingTest() = ModelCheckingOptions()
      .checkObstructionFreedom()
      .check(this::class)
   ```

4. Run the test. It should pass successfully.

## See also

* [Configuring argument generation constraints](operation-arguments.md)
* [Configuring operation execution](constraints.md)
<!-- TODO: add a link when `lincheck-results-validation.md` is merged 
* [Validating execution results](lincheck-results-validation.md) -->


