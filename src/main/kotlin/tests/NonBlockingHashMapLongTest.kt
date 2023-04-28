package tests

import org.jctools.maps.NonBlockingHashMapLong

class NonBlockingHashMapLongTest : AbstractConcurrentMapTest<NonBlockingHashMapLong<Int>>(NonBlockingHashMapLong())