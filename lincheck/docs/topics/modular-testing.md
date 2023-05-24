[//]: # (title: Modular testing)

When constructing new algorithms, it's common to use existing data structures as building blocks.
As these data structures are typically non-trivial, the number of possible interleavings increases significantly.

If you consider such underlying data structures to be correct and treat their operations as atomic, you can check only
meaningful interleavings, thus increasing the testing quality. Lincheck makes it possible with the _modular testing_
feature available for the [model checking strategy](testing-strategies.md#model-checking).

Consider the `MultiMap` implementation below, which is based on top of the modern `j.u.c.ConcurrentHashMap`:

```kotlin
import java.util.concurrent.*

class MultiMap<K, V> {
    val map = ConcurrentHashMap<K, List<V>>()

    // Adds the value to the list by the given key
    // Contains the race :(
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

It is already guaranteed that `j.u.c.ConcurrentHashMap` is linearizable, so its operations can be considered atomic. 
You can specify this guarantee with the `addGuarantee` option in the `ModelCheckingOptions()` in your test:

```kotlin
import java.util.concurrent.*
import org.jetbrains.kotlinx.lincheck_custom.annotations.*
import org.jetbrains.kotlinx.lincheck_custom.check
import org.jetbrains.kotlinx.lincheck_custom.paramgen.*
import org.jetbrains.kotlinx.lincheck_custom.strategy.managed.*
import org.jetbrains.kotlinx.lincheck_custom.strategy.managed.modelchecking.*
import org.junit.*

class MultiMap<K,V> {
    val map = ConcurrentHashMap<K, List<V>>()

    // Adds the value to the list by the given key
    // Contains the race :(
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

> [Get the full code](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/MultiMapTest.kt).
>
{type="note"}

## Next step

Learn how to test data structures that set [access constraints on the execution](constraints.md), 
such as single-producer single-consumer queues.