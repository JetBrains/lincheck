[//]: # (title: Progress guarantees)

Many concurrent algorithms provide non-blocking progress guarantees, such as lock-freedom and wait-freedom. As they are
usually non-trivial, it's easy to add a bug that blocks the algorithm. Lincheck can help you find liveness bugs using
the model checking strategy.

To check the progress guarantee of the algorithm, enable the `checkObstructionFreedom` option in `ModelCheckingOptions()`:

```kotlin
ModelCheckingOptions().checkObstructionFreedom()
```

Create a `ConcurrentMapTest.kt` file.
Then add the following test to detect that `ConcurrentHashMap::put(key: K, value: V)` from the Java standard library is a blocking operation:

```kotlin
import java.util.concurrent.*
import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.datastructures.*
import org.junit.*

class ConcurrentHashMapTest {
    private val map = ConcurrentHashMap<Int, Int>()

    @Operation
    fun put(key: Int, value: Int) = map.put(key, value)

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .actorsBefore(1) // To init the HashMap
        .actorsPerThread(1)
        .actorsAfter(0)
        .minimizeFailedScenario(false)
        .checkObstructionFreedom()
        .check(this::class)
}
```

Run the `modelCheckingTest()`. You should get the following result:

```text
= Obstruction-freedom is required but a lock has been found =
| ---------------------- |
|  Thread 1  | Thread 2  |
| ---------------------- |
| put(1, -1) |           |
| ---------------------- |
| put(2, -2) | put(3, 2) |
| ---------------------- |

---
All operations above the horizontal line | ----- | happen before those below the line
---

The following interleaving leads to the error:
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
|                                         Thread 1                                         |                                         Thread 2                                         |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
|                                                                                          | put(3, 2)                                                                                |
|                                                                                          |   put(3,2) at ConcurrentHashMapTest.put(ConcurrentMapTest.kt:11)                         |
|                                                                                          |     putVal(3,2,false) at ConcurrentHashMap.put(ConcurrentHashMap.java:1006)              |
|                                                                                          |       table.READ: Node[]@1 at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1014)      |
|                                                                                          |       tabAt(Node[]@1,0): Node@1 at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1018) |
|                                                                                          |       MONITORENTER at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1031)              |
|                                                                                          |       tabAt(Node[]@1,0): Node@1 at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1032) |
|                                                                                          |       next.READ: null at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1046)           |
|                                                                                          |       switch                                                                             |
| put(2, -2)                                                                               |                                                                                          |
|   put(2,-2) at ConcurrentHashMapTest.put(ConcurrentMapTest.kt:11)                        |                                                                                          |
|     putVal(2,-2,false) at ConcurrentHashMap.put(ConcurrentHashMap.java:1006)             |                                                                                          |
|       table.READ: Node[]@1 at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1014)      |                                                                                          |
|       tabAt(Node[]@1,0): Node@1 at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1018) |                                                                                          |
|       MONITORENTER at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1031)              |                                                                                          |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
```

Now let's add a test for the non-blocking `ConcurrentSkipListMap<K, V>`, expecting the test to pass successfully:

```kotlin
class ConcurrentSkipListMapTest {
    private val map = ConcurrentSkipListMap<Int, Int>()

    @Operation
    fun put(key: Int, value: Int) = map.put(key, value)

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .checkObstructionFreedom()
        .check(this::class)
}
```

> The common non-blocking progress guarantees are (from strongest to weakest):
> 
> * **wait-freedom**, when each operation is completed in a bounded number of steps no matter what other threads do.
> * **lock-freedom**, which guarantees system-wide progress so that at least one operation is completed in a bounded number of
>   steps while a particular operation may be stuck.
> * **obstruction-freedom**, when any operation is completed in a bounded number of steps if all the other threads pause.
>
{style="tip"}

At the moment, Lincheck supports only the obstruction-freedom progress guarantees. However, most real-life liveness bugs
add unexpected blocking code, so the obstruction-freedom check will also help with lock-free and wait-free algorithms.

> * Get the [full code of the example](https://github.com/JetBrains/lincheck/blob/master/src/jvm/test-lincheck-integration/org/jetbrains/lincheck_test/guide/ConcurrentMapTest.kt).
> * See [another example](https://github.com/JetBrains/lincheck/blob/master/src/jvm/test-lincheck-integration/org/jetbrains/lincheck_test/guide/ObstructionFreedomViolationTest.kt)
>   where the Michael-Scott queue implementation is tested for progress guarantees.
>
{style="note"}

## Next step

Learn how to [specify the sequential specification](sequential-specification.md) of the testing algorithm explicitly,
improving the Lincheck tests robustness.