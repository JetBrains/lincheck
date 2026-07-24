[//]: # (title: Operation execution options)
[//]: # (description: Learn about Lincheck’s execution options for concurrent operations.)

Lincheck provides a set of options for controlling how specific operations are executed, such as running 
operations on a single thread, executing an operation only once, marking the operation as blocking, and others.

In this article, you will learn about different execution options and how to set them.

## Single-thread operation groups

Certain operations are required to never run concurrently, such as operations in single-producer single-consumer queues.

To create a group of operations that are never executed in parallel, use the `nonParallelGroup` option when 
declaring the operations:

```kotlin
@Operation(nonParallelGroup = "consumers")
fun poll(): Int? = queue.poll()

@Operation(nonParallelGroup = "consumers")
fun peek(): Int? = queue.peek()

@Operation(nonParallelGroup = "producer")
fun offer(x: Int) = queue.offer(x)

@Operation
fun isEmpty(): Boolean = queue.isEmpty()
```

Lincheck ensures that the operations from the non-parallel group are never executed in parallel with each other. 
However, these operations can still run in parallel with operations outside of the non-parallel group:

```text
| --------------------- |
| Thread 1  | Thread 2  |
| --------------------- |
| poll()    | offer(0)  |
| peek()    | offer(0)  |
| poll()    | isEmpty() |
| poll()    | isEmpty() |
| isEmpty() | isEmpty() |
| --------------------- |
```

## Single-use operations

Use the `runOnce` option to execute the operation only once per test invocation:

```kotlin
@Operation(runOnce = true)
fun foo() = struct.foo()


@Operation
fun buzz() = struct.buzz()
```

An example of a generated scenario:

```text
| ------------------- |
| Thread 1 | Thread 2 |
| ------------------- |
| buzz()   | foo()    |
| buzz()   | buzz()   |
| ------------------- |
```

## Blocking operations

Use the `blocking` option if the operation is intended to block the execution. If the test checks 
for [non-blocking guarantees](lincheck-progress-guarantees.md), Lincheck does not fail a test when the execution stalls 
on an operation marked with the `blocking` option:

```kotlin
@Operation(blocking = true)
fun foo(): Int = struct.foo()
```

## Cancelable operations

Use the `cancellableOnSuspension` option if the operation can be 
[canceled when it suspends](https://kotlinlang.org/docs/cancellation-and-timeouts.html#suspension-points-and-cancellation):

```kotlin
@Operation(cancellableOnSuspension = true)
fun foo(): Int = struct.foo()
```

Consider the following channel test:

```kotlin
@Param(name = "value", gen = IntGen::class, conf = "1:3")
class ChannelCancellableTest {
    private val ch = Channel<Int>()
    
    @Operation
    suspend fun send(@Param(name = "value") value: Int) = ch.send(value)
    
    @Operation(cancellableOnSuspension = true)
    suspend fun receive() = ch.receive()
  
  
    @Test
    fun test() = ModelCheckingOptions()
        .iterations(50)
        .invocationsPerIteration(1000)
        // Report the scenarios even if the test has not failed
        .logLevel(LoggingLevel.INFO)
        .check(this::class)
}
```

<table>
<tr><td><code>cancellableOnSuspension = false</code></td><td><code>cancellableOnSuspension = true</code></td></tr>
<tr>
<td>If <code>receive()</code> is suspended, it stays suspended for the rest of the scenario:
<code-block lang="text">
| ----------------------------------- |
|      Thread 1    |      Thread 2    |
| ----------------------------------- |
| send(3)          | send(3) + cancel |
| receive()        | send(2) + cancel |
| receive()        | receive()        |
| receive()        | receive()        |
| send(1) + cancel | receive()        |
| ----------------------------------- |
</code-block>
</td>
<td>Lincheck explores the scenarios where <code>receive()</code> is suspended and then gets canceled, 
which better models real-life coroutine code:
<code-block lang="text">
| --------------------------------------- |
|      Thread 1      |      Thread 2      |
| --------------------------------------- |
| send(3)            | send(3) + cancel   |
| receive() + cancel | send(2) + cancel   |
| receive() + cancel | receive()          |
| receive() + cancel | receive() + cancel |
| send(1) + cancel   | receive() + cancel |
| --------------------------------------- |
</code-block>
</td>
</tr>
</table>

### Prompt cancellation

If `cancellableOnSuspension` is enabled and the operation should support
[prompt cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html#handle-values-safely-when-canceling-coroutines), 
you can also set `promptCancellation` to `true`:

```kotlin
@Operation(cancellableOnSuspension = true, promptCancellation = true)
fun foo(): Int = struct.foo()
```

Consider the following channel test:

```kotlin
@Param(name = "value", gen = IntGen::class, conf = "1:3")
class PromptCancellationTest {
    private val ch = Channel<Int>()
    
    @Operation
    suspend fun send(@Param(name = "value") value: Int) = ch.send(value)
    
    @Operation(cancellableOnSuspension = true, promptCancellation = true)
    suspend fun receive() = ch.receive()
    
    @Test
    fun test() = ModelCheckingOptions()
        .iterations(50)
        .invocationsPerIteration(1000)
        // Report the scenarios even if the test has not failed
        .logLevel(LoggingLevel.INFO)
        .check(this::class)
}
```

<table>
<tr><td><code>promptCancellation = false</code></td><td><code>promptCancellation = true</code></td></tr>
<tr>
<td>Lincheck can only try to cancel the operation <b>after</b> it has actually been suspended. If the operation 
was resumed before Lincheck attempts cancellation, the cancellation fails and the operation completes normally:
<code-block lang="text">
| --------------------------------------- |
|      Thread 1      |      Thread 2      |
| --------------------------------------- |
| send(2)            | send(2)            |
| send(2) + cancel   | receive() + cancel |
| receive() + cancel | receive()          |
| receive()          | send(3) + cancel   |
| send(2)            | receive()          |
| --------------------------------------- |
</code-block>
</td>
<td>Lincheck can cancel the operation even if it <b>has already resumed</b>, but didn't have a chance to run. 
This models the real-life behavior where a coroutine can be canceled between the moment it is resumed and the 
moment it actually processes the result:
<code-block lang="text">
| ---------------------------------------------- |
|      Thread 1      |         Thread 2          |
| ---------------------------------------------- |
| send(2)            | send(2)                   |
| send(2) + cancel   | receive() + cancel        |
| receive() + cancel | receive() + prompt_cancel |
| receive()          | send(3) + cancel          |
| send(2)            | receive()                 |
| ---------------------------------------------- |
</code-block>
</td>
</tr>
</table>

## See also

* [Checking for non-blocking progress guarantees](lincheck-progress-guarantees.md)
* [Defining sequential specification of the algorithm](lincheck-results-validation.md)