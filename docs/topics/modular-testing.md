[//]: # (title: Modular testing)

When constructing new algorithms, it is common to use linearizable data structures as building blocks.
However, these linearizable data structures are typically non-trivial, significantly increasing
the number of possible interleavings. Intuitively, it would be better to treat the operations of
such already correct underlying data structures as atomic ones, checking only meaningful interleavings
and increasing the testing quality. Lincheck makes it is possible by providing the *modular testing* feature
for the model checking.

Consider the `MultiMap` implementation below, 
which bases on top of the state-of-the-art `j.u.c.ConcurrentHashMap`:

```kotlin
import java.util.concurrent.*

class MultiMap<K, V> {
    val map = ConcurrentHashMap<K, List<V>>()

    // adds the value to the list by the given key
    // contains the race :(
    fun add(key: K, value: V) {
        val list = map[key]
        if (list == null) {
            map[key] = listOf(value)
        } else {
            map[key] = list + value
        }
    }

    fun get(key: K): List<V> = map[key] ?: emptyList()
}
```

It is already guaranteed that `j.u.c.ConcurrentHashMap` is linearizable, so its operations can be considered as atomic. 
You can specify this guarantee via the `addGuarantee` option in the `ModelCheckingOptions()` in your test:

```kotlin
import java.util.concurrent.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.junit.*

class MultiMap<K,V> {
    val map = ConcurrentHashMap<K, List<V>>()

    // adds the value to the list by the given key
    // contains the race :(
    fun add(key: K, value: V) {
        val list = map[key]
        if (list == null) {
            map[key] = listOf(value)
        } else {
            map[key] = list + value
        }
    }

    fun get(key: K): List<V> = map[key] ?: emptyList()
}

@Param(name = "key", gen = IntGen::class, conf = "1:2")
class MultiMapTest {
    private val map = MultiMap<Int, Int>()

    @Operation
    fun add(@Param(name = "key") key: Int, value: Int) = map.add(key, value)

    @Operation
    fun get(@Param(name = "key") key: Int) = map.get(key)

    @Test
    fun modularTest() = ModelCheckingOptions()
        .addGuarantee(forClasses(ConcurrentHashMap::class).allMethods().treatAsAtomic())
        // Note that with the atomicity guarantees set, Lincheck can examine all possible interleavings,
        // so the test successfully passes when the number of invocations is set to `Int.MAX_VALUE`
        // If you comment the line above, the test takes a lot of time and likely fails with `OutOfMemoryError`.
        .invocationsPerIteration(Int.MAX_VALUE)
        .check(this::class)
}
```

> Get the full code [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/MultiMapTest.kt).
>
{type="note"}

## What's next

Learn how to test data structures that set access [constraints](constraints.md) on the execution, 
such as single-producer single-consumer queues.