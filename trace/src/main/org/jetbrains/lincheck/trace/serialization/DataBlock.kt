package org.jetbrains.lincheck.trace.serialization

internal class DataBlock(
    physicalStart: Long,
    physicalEnd: Long,
    accDataSize: Long
) {
    constructor(start: Long, accDataSize: Long) : this(start, Long.MAX_VALUE, accDataSize)

    val physicalStart: Long
    var physicalEnd: Long
        private set

    /**
     * Accumulated data size: sum of all data blocks' sizes in this thread before this block.
     */
    val accDataSize: Long

    val physicalDataStart: Long get() = physicalStart + BLOCK_HEADER_SIZE
    val logicalDataStart: Long get() = accDataSize
    val logicalDataEnd: Long get() = accDataSize + dataSize

    val size: Long get() = physicalEnd - physicalStart
    val dataSize: Long get() = physicalEnd - physicalStart - BLOCK_HEADER_SIZE

    init {
        require(physicalStart >= 0) { "start must be non-negative" }
        require(physicalStart < physicalEnd) { "block cannot be empty" }
        require(accDataSize >= 0) { "accumulated data size cannot be negative" }
        this.physicalStart = physicalStart
        this.physicalEnd = physicalEnd
        this.accDataSize = accDataSize
    }

    fun coversPhysicalOffset(offset: Long): Boolean = offset in physicalStart ..< physicalEnd

    fun coversLogicalOffset(offset: Long): Boolean = offset in logicalDataStart ..< logicalDataStart + dataSize

    /**
     * For usage with [List.binarySearch]
     *
     * Function that returns zero when called on the list element being searched.
     * On the elements coming before the target element, the function must return negative values;
     * on the elements coming after the target element, the function must return positive values.
     */
    fun compareWithPhysicalOffset(offset: Long): Int =
        if (offset < physicalStart) +1
        else if (offset >= physicalEnd) -1
        else 0

    fun compareWithLogicalOffset(offset: Long): Int =
        if (offset < accDataSize) +1
        else if (offset >= accDataSize + dataSize) -1
        else 0

    fun updateEnd(newPhysicalEnd: Long) {
        require(physicalStart < newPhysicalEnd) { "block cannot be empty" }
        check(physicalEnd == Long.MAX_VALUE) { "block cannot be updated twice" }
        physicalEnd = newPhysicalEnd
    }
}

internal typealias BlockList = MutableList<DataBlock>

internal fun BlockList.addNewPartialBlock(start: Long) {
    require(isEmpty() || last().physicalEnd < start) {
        "Start offsets of blocks in the list must increase: last block ends at ${last().physicalEnd}, new starts at $start "
    }
    add(DataBlock(start, dataSize()))
}

internal fun BlockList.addNewBlock(start: Long, end: Long) {
    require(isEmpty() || last().physicalEnd < start) {
        "Start offsets of blocks in the list must increase: last block ends at ${last().physicalEnd}, new starts at $start "
    }
    add(DataBlock(start, end, dataSize()))
}

internal fun BlockList.fixLastBlock(end: Long) {
    check(isNotEmpty()) { "Cannot fix last block in empty list" }
    last().updateEnd(end)
}

internal fun BlockList.dataSize(): Long {
    return if (isEmpty()) 0 else last().accDataSize + last().dataSize
}