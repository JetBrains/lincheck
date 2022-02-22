[//]: # (title: Modular testing)

It is common to use linearizable data structures as building blocks in the implementation of other data structures.
The number of all possible interleavings for non-trivial algorithms is usually enormous.

To reduce the number of redundant interleavings, you can use the modular testing option supported by the model checking mode.
Modular testing allows treatment operations of the internal data structures to be atomic.

Consider the `MultiMap` class backed with a `java.util.concurrent.ConcurrentHashMap` example:

```kotlin
import java.util.concurrent.ConcurrentHashMap

//sampleStart
class MultiMap {
    val map = ConcurrentHashMap<Int, List<Int>>()

    // adds the value to the list by the given key
    // contains the race :(
    fun add(key: Int, value: Int) {
        val list = map[key]
        if (list == null) {
            map[key] = listOf(value)
        } else {
            map[key] = list + value
        }
    }

    fun get(key: Int) = map.get(key)
}
//sampleEnd
```

It's guaranteed that `java.util.concurrent.ConcurrentHashMap` is linearizable, and all its operations are atomic. You
can specify this guarantee by setting the `addGuarantee` option in the `ModelCheckingOptions()` of your test:

```kotlin
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.junit.Test

class MultiMap {
    val map = ConcurrentHashMap<Int, List<Int>>()

    // adds the value to the list by the given key
    // contains the race :(
    fun add(key: Int, value: Int) {
        val list = map[key]
        if (list == null) {
            map[key] = listOf(value)
        } else {
            map[key] = list + value
        }
    }

    fun get(key: Int) = map.get(key)
}

@Param(name = "key", gen = IntGen::class, conf = "1:2")
class MultiMapTest {
    private val map = MultiMap()

    @Operation
    fun add(@Param(name = "key") key: Int, value: Int) = map.add(key, value)

    @Operation
    fun get(@Param(name = "key") key: Int) = map.get(key)

    //sampleStart
    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .addGuarantee(forClasses(ConcurrentHashMap::class.qualifiedName!!).allMethods().treatAsAtomic())
        // Note, that with atomicity guarantees set, all possible interleaving in the MultiMap can be examined,
        // you can ensure the test to pass when the number of invocations is set to the max value.
        // Otherwise, if you try to examine all interleaving without atomic guarantees for ConcurrentHashMap,
        // the test will most probably fail with the lack of memory
        // because of the huge amount of possible context switches to be checked.
        .invocationsPerIteration(Int.MAX_VALUE)
        .check(this::class)
    //sampleEnd
}
```

> Get the full code [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/MultiMapTest.kt).
>
{type="note"}

## What's next

Learn how to configure test execution if the contract of the data structure sets some [constraints](constraints.md),
for example, single-consumer queues.