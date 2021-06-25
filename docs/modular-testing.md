## Modular testing

It is a common pattern to use linearizable data structures as building blocks in the implementation of other data structures.
The number of all possible interleavings for non-trivial algorithms usually is enormous.

To reduce the amount of redundant interleavings we can use modular testing option supported by the model checking mode.
Modular testing allows to treat operations of the internal data structures to be atomic.

Consider, the `MultiMap` backed with a `java.util.concurrent.ConcurrentHashMap` from the [previous section](#parameter-generation.md).
We can guarantee that `java.util.concurrent.ConcurrentHashMap` is linearizable and all it's operations are atomic.

We can specify this guarantee by setting the `addGuarantee` option in the `ModelCheckingOptions()` of your test.
See the example below: 

```kotlin
@Test
fun modelCheckingTest() = ModelCheckingOptions()
        .addGuarantee(forClasses(ConcurrentHashMap::class).allMethods().treatAsAtomic())
        .check(this::class)
```

## To sum up 

In this section you have learnt how to optimize testing of data structures that are implemented using other correct data structures.
> Get the full code [here](../src/jvm/test/org/jetbrains/kotlinx/lincheck/test/guide/MultiMapTest.kt).

In [the next section](constraints.md) you will learn how to configure test execution if the contract of the data structure sets
some constraints (for example single-consumer queues).