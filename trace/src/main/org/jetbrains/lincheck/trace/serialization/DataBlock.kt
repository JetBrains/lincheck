package org.jetbrains.lincheck.trace.serialization


internal class DataBlock(
    physicalStart: Long,
    physicalEnd: Long,
    accDataSize: Long
) {
    constructor(start: Long, accDataSize: Long) : this(start, UNKNOWN_PHYSICAL_END, accDataSize)

    /** Offset of the block's `BLOCK_START` byte. */
    val physicalStart: Long

    /** Exclusive end of the block's whole footprint in the file: header, data and the `BLOCK_END` byte. */
    var physicalEnd: Long
        private set

    /**
     * Accumulated data size: sum of all data blocks' sizes in this thread before this block.
     */
    val accDataSize: Long

    val physicalDataStart: Long get() = physicalStart + BLOCK_HEADER_SIZE
    val logicalDataStart: Long get() = accDataSize
    val logicalDataEnd: Long get() = accDataSize + dataSize

    val dataSize: Long get() = physicalEnd - physicalStart - BLOCK_HEADER_SIZE - BLOCK_FOOTER_SIZE

    init {
        require(physicalStart >= 0) { "start must be non-negative" }
        require(physicalStart < physicalEnd) { "block cannot be empty" }
        require(accDataSize >= 0) { "accumulated data size cannot be negative" }
        this.physicalStart = physicalStart
        this.physicalEnd = physicalEnd
        this.accDataSize = accDataSize
    }

    /**
     * For usage with [List.binarySearch]: zero for the block containing the physical [offset],
     * a positive value for the blocks before it and a negative one for the blocks after it.
     *
     * The searched range is the block's whole footprint, so it also contains the offset of the `BLOCK_END` byte.
     * That is where a reader stops after consuming the block's last data byte,
     * and it stands for the same logical offset as the start of the next block's data,
     * which [compareWithLogicalOffset] resolves to that next block.
     */
    fun compareWithPhysicalOffset(offset: Long): Int =
        if (offset < physicalStart) +1
        else if (offset >= physicalEnd) -1
        else 0

    /**
     * For usage with [List.binarySearch]: zero for the block containing the logical [offset],
     * a positive value for the blocks before it and a negative one for the blocks after it.
     *
     * The block's logical range is half-open, so a logical offset on a block boundary resolves to the next block,
     * i.e. to the start of its data rather than to the end of this one — the only spelling one can read forward from.
     */
    fun compareWithLogicalOffset(offset: Long): Int =
        if (offset < logicalDataStart) +1
        else if (offset >= logicalDataEnd) -1
        else 0

    fun updateEnd(newPhysicalEnd: Long) {
        require(physicalStart < newPhysicalEnd) { "block cannot be empty" }
        check(physicalEnd == UNKNOWN_PHYSICAL_END) { "block cannot be updated twice" }
        physicalEnd = newPhysicalEnd
    }
}

internal typealias BlockList = MutableList<DataBlock>

internal fun BlockList.addNewPartialBlock(start: Long) {
    require(isEmpty() || last().physicalEnd <= start) {
        "Blocks in the list must not overlap: last block ends at ${last().physicalEnd}, new starts at $start "
    }
    add(DataBlock(start, dataSize()))
}

internal fun BlockList.addNewBlock(start: Long, end: Long) {
    require(isEmpty() || last().physicalEnd <= start) {
        "Blocks in the list must not overlap: last block ends at ${last().physicalEnd}, new starts at $start "
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

/** Physical end of a block that is still being read, and whose real end is therefore not known yet. */
private const val UNKNOWN_PHYSICAL_END: Long = Long.MAX_VALUE