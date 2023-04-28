import SnapTree.SnapTreeMap
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param

class SnapTreeTest : AbstractConcurrentMapTest<SnapTreeMap<Long, Int>>(SnapTreeMap()) {
    // this operation is disabled for SnapTree, since it's bugged even in the sequential case
    override fun removeIf(key: Long, value: Int): Boolean { return false }

    @Operation(handleExceptionsAsResult = [NoSuchElementException::class])
    fun firstKey(): Long = map.firstKey()

    @Operation(handleExceptionsAsResult = [NoSuchElementException::class])
    fun lastKey(): Long = map.lastKey()

    @Operation
    fun lowerKey(@Param(name = "key") key: Long): Long? = map.lowerKey(key)

    @Operation
    fun higherKey(@Param(name = "key") key: Long): Long? = map.higherKey(key)

//    override fun ModelCheckingOptions.customize(): ModelCheckingOptions =
//        iterations(400).threads(3).actorsPerThread(3)
            // uncomment the next lines to reproduce the bug faster
//            .addCustomScenario {
//                initial {
//                    actor(SnapTreeTest::putIfAbsent, 2.toLong(), 4)
//                    actor(SnapTreeTest::putIfAbsent, 4.toLong(), 2)
//                }
//                parallel {
//                    thread {
//                        actor(SnapTreeTest::putIfAbsent, 6.toLong(), 4)
//                        actor(SnapTreeTest::remove, 4.toLong())
//                    }
//                    thread {
//                        actor(SnapTreeTest::lastKey)
//                    }
//                }
//            }
}