[//]: # (title: Progress guarantees)
Many concurrent algorithms provide non-blocking progress guarantees, such as lock- and wait-freedom.
As they are usually non-trivial, it is easy to add a bug that makes the algorithm blocking. 
Fortunately, Lincheck is able to find liveness bugs in the model checking mode.

To check the progress guarantee of the algorithm, you just set the `checkObstructionFreedom`
option in `ModelCheckingOptions()`:

```kotlin
ModelCheckingOptions().checkObstructionFreedom(true)
```

As an example, let's consider `ConcurrentHashMap<K, V>` from the Java standard library.
Here is the Lincheck test to detect that `put(key: K, value: V)` is a blocking operation:

```kotlin
class ConcurrentHashMapTest {
    private val map = ConcurrentHashMap<Int, Int>()

    @Operation
    public fun put(key: Int, value: Int) = map.put(key, value)

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .actorsBefore(1) // to init the HashMap
        .actorsPerThread(1)
        .actorsAfter(0)
        .minimizeFailedScenario(false)
        .checkObstructionFreedom(true)
        .check(this::class)
}
```

Run the `modelCheckingTest` you will and get the following result:

```text
= Obstruction-freedom is required but a lock has been found =
Execution scenario (init part):
[put(2, 6)]
Execution scenario (parallel part):
| put(-6, -8) | put(1, 4) |

= The following interleaving leads to the error =
Parallel part trace:
|                                                                                          | put(1, 4)                                                                                |
|                                                                                          |   put(1,4) at ConcurrentHashMapTest.put(ConcurrentMapTest.kt:34)                         |
|                                                                                          |     putVal(1,4,false) at ConcurrentHashMap.put(ConcurrentHashMap.java:1006)              |
|                                                                                          |       table.READ: Node[]@1 at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1014)      |
|                                                                                          |       tabAt(Node[]@1,0): Node@1 at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1018) |
|                                                                                          |       MONITORENTER at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1031)              |
|                                                                                          |       tabAt(Node[]@1,0): Node@1 at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1032) |
|                                                                                          |       next.READ: null at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1046)           |
|                                                                                          |       switch                                                                             |
| put(-6, -8)                                                                              |                                                                                          |
|   put(-6,-8) at ConcurrentHashMapTest.put(ConcurrentMapTest.kt:34)                       |                                                                                          |
|     putVal(-6,-8,false) at ConcurrentHashMap.put(ConcurrentHashMap.java:1006)            |                                                                                          |
|       table.READ: Node[]@1 at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1014)      |                                                                                          |
|       tabAt(Node[]@1,0): Node@1 at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1018) |                                                                                          |
|       MONITORENTER at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1031)              |                                                                                          |
|                                                                                          |       MONITOREXIT at ConcurrentHashMap.putVal(ConcurrentHashMap.java:1065)               |

```

Now let's write a test for the non-blocking `ConcurrentSkipListMap<K, V>`, expecting the test to pass successfully:

```kotlin
class ConcurrentSkipListMapTest {
    private val map = ConcurrentSkipListMap<Int, Int>()

    @Operation
    public fun put(key: Int, value: Int) = map.put(key, value)

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .checkObstructionFreedom(true)
        .check(this::class)
}
```

The common non-blocking progress guarantees are (in order to weaken):

* **wait-freedom:** each operation completes in a bounded number of steps no matter what other threads do;
* **lock-freedom:** guarantees system-wide progress, so that at least one operation completes in a bounded number of steps, 
                    while a particular operation may be stuck;
* **obstruction-freedom:** – any operation can complete in a bounded number of steps if all the other threads pause.

For now, Lincheck supports only the _obstruction-freedom_ progress guarantee.
As most the real-world liveness bugs introduce unexpected blocking code,
obstruction-freedom check is beneficial for lock-free and wait-free algorithms.

> * Get the full code of the example [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/ConcurrentMapTest.kt).
> * Get another example where we test Michael-Scott queue implementation for progress guarantees [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/ObstructionFreedomViolationTest.kt).
>
{type="note"}

## What's next

Learn how Lincheck [verifies execution results](verification.md) for correctness and 
how to specify the sequential specification of the algorithm explicitly, making the testing more robust.
