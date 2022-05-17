[//]: # (title: Results verification)

After each scenario invocation, Lincheck verifies outcome results for correctness.
The standard safety property for concurrent algorithms is _linearizability_, 
which implies that each operation in the execution takes place 
atomically, in some order consistent with the "happens-before" relation. 
Essentially, Lincheck tries to find such an order.

In order to check whether the results satisfy linearizability, Lincheck tries 
to explain them with some sequential execution which does not reorder operations in threads 
and does not violate the happens-before order constructed during the execution.

Lincheck defines the sequential semantics via building a transition graph: 
the states represent the data structure states and the transitions are labeled 
with operations and the corresponding results. 
A sequence of operations application is valid according to the sequential semantics 
iff there exists a finite path of the transitions labeled by these operations in the transition graph.

For example, consider checking if results of one of the possible executions of the stack are linearizable. On the
left of the scheme, you can see the execution results, and on the right, there is a part of the LTS for the stack 
that is built to verify those results. Here are the steps of verification:

1. **T2:** Apply `push(2)` from the second thread to the empty stack (the initial state).
2. **T2:** Try to complete the second thread, but the following `pop()` invocation returns the previously pushed `2`
   instead of `1` from the results.
3. **T1:** Switch to the first thread and apply `push(1)`.
4. **T2:** Switch back to the second thread and execute `pop()`, which now returns `1` as expected.

![Execution results of the Stack](stack_lts.png){width=700}

This way, Lincheck successfully found a sequential history that produces the given results, showing that they are
correct. At the same time, if none of the explored sequential histories explains the given result, Lincheck reports an
error.

### Sequential specification

During verification, you sequentially apply operations of the tested algorithm by default.

To be sure that the algorithm provides correct sequential behavior, you can define its _sequential specification_
by writing a simple sequential implementation of the algorithm.

> This also allows writing a single test instead of writing sequential and concurrent tests separately.
>
{type="tip"}

To provide a sequential specification of the algorithm for verification:

1. Implement a sequential version of all the testing methods.
2. Pass the class with sequential implementation to the `sequentialSpecification` option:

   ```kotlin
   StressOptions().sequentialSpecification(SequentialStack::class)
   ```

For example, provide a sequential specification for the concurrent stack implementation with non-linearizable `size`:

```kotlin
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.*
import java.util.*
import java.util.concurrent.atomic.*
import kotlin.NoSuchElementException

class Stack<T> {
   private val top  = AtomicReference<Node<T>?>(null)
   private val _size = AtomicInteger(0)

   fun push(value: T) {
      while (true) {
         val cur = top.get()
         val newTop = Node(cur, value)
         if (top.compareAndSet(cur, newTop)) { // try to add
            _size.incrementAndGet() // <-- INCREMENT SIZE
            return
         }
      }
   }

   fun popOrNull(): T? {
      while (true) {
         val cur = top.get() ?: return null // is stack empty?
         if (top.compareAndSet(cur, cur.next)) { // try to retrieve
            _size.decrementAndGet() // <-- DECREMENT SIZE
            return cur.value
         }
      }
   }

   val size: Int get() = _size.get()
}
class Node<T>(val next: Node<T>?, val value: T)

class StackTest {
   private val s = Stack<Int>()

   @Operation
   fun push(value: Int) = s.push(value)

   @Operation
   fun popOrNull() = s.popOrNull()

   @Operation
   fun size() = s.size

   class SequentialStack {
      val s = LinkedList<Int>()

      fun push(x: Int) = s.push(x)
      fun popOrNull() = s.pollFirst()
      fun size() = s.size
   }

   @Test
   fun stressTest() = StressOptions()
      .sequentialSpecification(SequentialStack::class.java)
      .check(this::class)
}
```

### State equivalency

Before the output of every test, you can see advice to specify the state equivalence relation on your sequential
specification to make the verification faster.

To make verification faster, Lincheck builds a transition graph, invoking operations of the provided sequential
implementation, and several transition sequences may lead to the same state. For example, applying `pop()` after the
first `push(2)` leads back to the initial state.

So if you define the equivalency relation between the states of a data structure by implementing `equals()`
and `hashCode()` methods on the test class, the number of states in the transition graph will decrease, which will speed
up verification.

Lincheck provides the following way to do that:

1. Make the test class extend `VerifierState`.
2. Override `extractState()` function: it should define the state of a data structure.

   > `extractState()` is called once and can modify the data structure.
   >
   {type="note"}

3. Turn on the `requireStateEquivalenceImplCheck` option:

   ```kotlin
   StressOptions().requireStateEquivalenceImplCheck(true)
   ```

Defining state equivalence for `StackTest1` looks like this:

```kotlin
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import java.util.concurrent.atomic.*
import kotlin.NoSuchElementException

class Stack<T> {
   private val top  = AtomicReference<Node<T>?>(null)
   private val _size = AtomicInteger(0)

   fun push(value: T) {
      while (true) {
         val cur = top.get()
         val newTop = Node(cur, value)
         if (top.compareAndSet(cur, newTop)) { // try to add
            _size.incrementAndGet() // <-- INCREMENT SIZE
            return
         }
      }
   }

   fun popOrNull(): T? {
      while (true) {
         val cur = top.get() ?: return null // is stack empty?
         if (top.compareAndSet(cur, cur.next)) { // try to retrieve
            _size.decrementAndGet() // <-- DECREMENT SIZE
            return cur.value
         }
      }
   }

   val size: Int get() = _size.get()
}
class Node<T>(val next: Node<T>?, val value: T)

class StackTest1 : VerifierState() {
   private val s = Stack<Int>()

   @Operation
   fun push(value: Int) = s.push(value)

   @Operation
   fun popOrNull() = s.popOrNull()

   @Operation
   fun size() = s.size

   override fun extractState(): String {
      val elements = mutableListOf<Int>()
      while(s.size != 0) {
         elements.add(s.pop()!!)
      }
      return elements.toString()
   }
   
   @Test
   fun stressTest() = StressOptions()
      .requireStateEquivalenceImplCheck(true)
      .check(this::class)
}
```

### Happens-before relation

During execution Lincheck constructs the happens-before order of operations: 
it tracks the number of completed operations in each of the parallel threads 
seen at the beginning of the current operation.
You can see these numbers in brackets against the operation. Run the following test.

```kotlin
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import java.util.concurrent.atomic.*
import kotlin.NoSuchElementException

class Stack<T> {
   private val top  = AtomicReference<Node<T>?>(null)
   private val _size = AtomicInteger(0)

   fun push(value: T) {
      while (true) {
         val cur = top.get()
         val newTop = Node(cur, value)
         if (top.compareAndSet(cur, newTop)) { // try to add
            _size.incrementAndGet() // <-- INCREMENT SIZE
            return
         }
      }
   }

   fun popOrNull(): T? {
      while (true) {
         val cur = top.get() ?: return null // is stack empty?
         if (top.compareAndSet(cur, cur.next)) { // try to retrieve
            _size.decrementAndGet() // <-- DECREMENT SIZE
            return cur.value
         }
      }
   }

   val size: Int get() = _size.get()
}
class Node<T>(val next: Node<T>?, val value: T)

class StackTest2 {
   private val s = Stack<Int>()

   @Operation
   fun push(value: Int) = s.push(value)

   @Operation
   fun popOrNull() = s.popOrNull()

   @Operation
   fun size() = s.size
   
   @Test
   fun modelCheckinglTest() = ModelCheckingOptions()
      .actorsBefore(0)
      .actorsAfter(0)
      .actorsPerThread(3)
      .check(this::class)
}
```

You will get the output like this:

```text 
= Invalid execution results =
Parallel part:
| popOrNull(): 2          | push(2): void       |
| push(6):     void [1,-] |                     |
| size():      0    [2,-] |                     |
---
values in "[..]" brackets indicate the number of completed operations 
in each of the parallel threads seen at the beginning of the current operation
---

= The following interleaving leads to the error =
Parallel part trace:
|                      | push(2)                                                             |
|                      |   push(2) at StackTest2.push(StackTest.kt:75)                       |
|                      |     get(): null at Stack.push(StackTest.kt:38)                      |
|                      |     compareAndSet(null,Node@1): true at Stack.push(StackTest.kt:40) |
|                      |     switch                                                          |
| popOrNull(): 2       |                                                                     |
| push(6): void        |                                                                     |
| size(): 0            |                                                                     |
|   thread is finished |                                                                     |
|                      |     incrementAndGet(): 1 at Stack.push(StackTest.kt:41)             |
|                      |   result: void                                                      |
|                      |   thread is finished                                                |
```

1. **T2:** The second thread pushes `2` on the stack and switches its execution
   to the first thread before incrementing the size.
2. **T1:** The first thread pops the element `2` and decrements the size, `size == -1`.
2. **T1:** The first thread pushes `6` and increments the size, `size == 0`.
2. **T1:** The values in brackets against `size()` operation `[2, -]` indicate that by the time of `size()` invocation
   no operations from the second thread have completed yet, so it returns `0`.

### Handling exception as a result

Your implementation may throw an exception according to the contract. You can define potentially thrown exceptions as
legal results via listing them in the `handleExceptionsAsResult` option of the `@Operation` annotation over the
corresponding operation.

For example `pop()` operation on the stack may throw `NoSuchElementException`. Define this exception as a legal result
of `pop()` invocation the following way:

```kotlin
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import java.util.concurrent.atomic.*
import kotlin.NoSuchElementException

class Stack<T> {
   private val top  = AtomicReference<Node<T>?>(null)
   private val _size = AtomicInteger(0)

   fun push(value: T) {
      while (true) {
         val cur = top.get()
         val newTop = Node(cur, value)
         if (top.compareAndSet(cur, newTop)) { // try to add
            _size.incrementAndGet() // <-- INCREMENT SIZE
            return
         }
      }
   }

   fun pop(): T? {
      while (true) {
         val cur = top.get() ?: throw NoSuchElementException() // is stack empty?
         if (top.compareAndSet(cur, cur.next)) { // try to retrieve
            _size.decrementAndGet() // <-- DECREMENT SIZE
            return cur.value
         }
      }
   }

   val size: Int get() = _size.get()
}
class Node<T>(val next: Node<T>?, val value: T)

class StackTest3 {
   private val s = Stack<Int>()

   @Operation
   fun push(value: Int) = s.push(value)

   @Operation(handleExceptionsAsResult = [NoSuchElementException::class])
   fun pop() = s.pop()

   @Operation
   fun size() = s.size

   @Test
   fun stressTest() = StressOptions().check(this::class)
}
```

### Validation of invariants

It's also possible to validate the data structure invariants, implemented with functions that can be executed multiple
times during execution when there is no running operation in an intermediate state. For example, they are invoked in the
stress mode at the beginning and end of the parallel execution.

Validation functions are marked with a special `@Validate` annotation, have no argument, and do not return anything. In
case the testing data structure is in an invalid state, they should throw an exception.

Consider, for example, the part of the test for a linked list, which supports concurrent removals. A typical invariant
is that "removed nodes should not be reachable when all the operations are completed". The validation
function `checkNoRemovedNodes` checks this invariant, throwing an exception if violated.

```kotlin
class MyLockFreeListTest {
    private val list = MyLockFreeList<Int>()

    @Validate
    fun checkNoRemovedNodesInTheList() = check(!list.hasRemovedNodes()) {
        "The list contains logically removed nodes while all the operations are completed: $list"
    }
    //...
}
```

> Get the full code of the examples [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/StackTest.kt).
>
{type="note"}