import tests.*

val tests = listOf<AbstractLincheckTest>(
    ConcurrentHashMapTest(),
    ConcurrentLinkedDequeTest(),
    LockFreeTaskQueueTest(),
    SemaphoreTest(),
    //    QueueSynchronizerTest(),
    MutexTest(),
    NonBlockingHashMapLongTest(),
    ConcurrentRadixTreeTest(),
    SnapTreeTest(),
    LogicalOrderingAVLTest(),
    CATreeTest(),
    ConcurrencyOptimalMapTest()
)

fun main() {
    for (test in tests) {
        println("Running ${test::class.simpleName}")
        // ...
    }
}