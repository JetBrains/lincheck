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
@Test
fun modelCheckingTest() = ModelCheckingOptions()
        .addGuarantee(forClasses(ConcurrentHashMap::class).allMethods().treatAsAtomic())
        .check(this::class)
```

> Get the full code [here](https://github.com/Kotlin/kotlinx-lincheck/blob/guide/src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/MultiMapTest.kt).
>
{type="note"}

## What's next

Learn how to configure test execution if the contract of the data structure sets some [constraints](constraints.md),
for example, single-consumer queues.