import org.jctools.maps.NonBlockingHashMapLong
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param

class NonBlockingHashMapLongTest : AbstractConcurrentMapTest<NonBlockingHashMapLong<Int>>(NonBlockingHashMapLong())