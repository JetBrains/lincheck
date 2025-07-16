/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.math.min
import kotlin.math.roundToInt

internal data class Range(val start: Long, val end: Long)

internal sealed class RangeIndex {
    abstract operator fun get(id: Int): Range?
    abstract fun addRange(id: Int, start: Long, end: Long)
    abstract fun addStart(id: Int, start: Long)
    abstract fun setEnd(id: Int, end: Long)
    abstract fun finishIndex()

    companion object {
        fun create(): RangeIndex = SQLiteRangeIndex()
    }
}

private const val SQL_BATCH_SIZE = 100000

private class SQLiteRangeIndex: RangeIndex() {
    companion object {
        val TABLE_NAME = "ranges"
        val INIT_STATEMENTS = listOf(
            "CREATE TABLE $TABLE_NAME (" +
                "id INTEGER PRIMARY KEY," +
                "start INTEGER NOT NULL," +
                "end INTEGER NOT NULL" +
            ")"
        )
        val INSERT_SQL = "INSERT INTO $TABLE_NAME (id, start, end) VALUES (?, ? ,?)"
        val UPDATE_SQL = "UPDATE $TABLE_NAME SET end = ? WHERE id = ?"
        val SELECT_SQL = "SELECT start, end FROM $TABLE_NAME WHERE id = ?"
    }

    val connection: Connection = DriverManager.getConnection("jdbc:sqlite:")
    val insert: PreparedStatement
    val update: PreparedStatement
    val select: PreparedStatement

    var batchSize = 0

    init {
        connection.createStatement().use { stmt ->
            INIT_STATEMENTS.forEach { sql -> stmt.executeUpdate(sql) }
        }
        insert = connection.prepareStatement(INSERT_SQL)
        update = connection.prepareStatement(UPDATE_SQL)
        select = connection.prepareStatement(SELECT_SQL)
        connection.autoCommit = false
    }

    override fun get(id: Int): Range? {
        select.setInt(1, id)
        if (!select.execute()) return null
        val rs = select.resultSet
        if (!rs.next()) return null
        return Range(rs.getLong(1), rs.getLong(2))
    }

    override fun addRange(id: Int, start: Long, end: Long) {
        insert.setInt(1, id)
        insert.setLong(2, start)
        insert.setLong(3, end)
        insert.addBatch()
        batchSize++
        if (batchSize == SQL_BATCH_SIZE) {
            insert.executeBatch()
            connection.commit()
            batchSize = 0
        }
    }

    override fun addStart(id: Int, start: Long) {
        insert.setInt(1, id)
        insert.setLong(2, start)
        insert.setLong(3, -1)
        insert.addBatch()
        batchSize++
        if (batchSize == SQL_BATCH_SIZE) {
            insert.executeBatch()
            connection.commit()
            batchSize = 0
        }
    }

    override fun setEnd(id: Int, end: Long) {
        if (batchSize > 0) {
            insert.executeBatch()
            connection.commit()
            batchSize = 0
        }

        update.setInt(1, id)
        update.setLong(2, end)
        update.executeUpdate()
        connection.commit()
    }

    override fun finishIndex() {
        if (batchSize > 0) {
            insert.executeBatch()
            connection.commit()
            batchSize = 0
        }
        connection.autoCommit = true
    }
}

private class HashMapRangeIndex: RangeIndex() {
    private val openRanges = mutableMapOf<Int, Long>()
    private val map = mutableMapOf<Int, Range>()
    private var closed = false

    override operator fun get(id: Int): Range? {
        require(id >= 0) { "Id $id must be non-negative" }
        check(closed) { "Index is not closed properly yet" }
        return map[id]
    }

    override fun addRange(id: Int, start: Long, end: Long) {
        check(!closed) { "Index already closed." }
        require(id >= 0) { "Id $id must be non-negative" }
        require(start >= 0) { "Id $id: Start $start must be non-negative" }
        require(end >= start) { "Id $id: End $end must be larger or equal than start $start" }

        map[id] = Range(start, end)
    }

    override fun addStart(id: Int, start: Long) {
        check(!closed) { "Index already closed." }
        require(id >= 0) { "Id $id must be non-negative" }
        require(start >= 0) { "Id $id: Start $start must be non-negative" }
        check(openRanges[id] == null) { "Id $id must be unique in range index" }
        openRanges[id] = start
    }

    override fun setEnd(id: Int, end: Long) {
        check(!closed) { "Index already closed." }
        val start = openRanges.remove(id)
        check(start != null) { "Id $id must have start already" }
        addRange(id, start, end)
    }

    override fun finishIndex() {
        if (closed) return
        check(openRanges.isEmpty()) { "Index has some non-finished ranges" }
        closed = true
    }
}

private const val INITIAL_SIZE = 1024
private const val QUICKSORT_NO_REC = 16
private const val QUICKSORT_MEDIAN_OF_9 = 128

private class ThreeArraysRangeIndex: RangeIndex() {
    private val openRanges = mutableMapOf<Int, Long>()
    private var ids = IntArray(INITIAL_SIZE)
    private var start = LongArray(INITIAL_SIZE)
    private var end = LongArray(INITIAL_SIZE)
    private var size = 0
    private var closed = false

    override operator fun get(id: Int): Range? {
        require(id >= 0) { "Id $id must be non-negative" }
        check(closed) { "Index is not closed properly yet" }

        val idx = ids.binarySearch(id, 0, size)
        if (idx < 0) return null
        return Range(start[idx], end[idx])
    }

    override fun addRange(id: Int, start: Long, end: Long) {
        check(!closed) { "Index already closed." }
        require(id >= 0) { "Id $id must be non-negative" }
        require(start >= 0) { "Id $id: Start $start must be non-negative" }
        require(end >= start) { "Id $id: End $end must be larger or equal than start $start" }

        ensureCapacity()
        this.ids[size] = id
        this.start[size] = start
        this.end[size] = end
        size++
    }

    override fun addStart(id: Int, start: Long) {
        check(!closed) { "Index already closed." }
        require(id >= 0) { "Id $id must be non-negative" }
        require(start >= 0) { "Id $id: Start $start must be non-negative" }
        check(openRanges[id] == null) { "Id $id must be unique in range index" }
        openRanges[id] = start
   }

    override fun setEnd(id: Int, end: Long) {
        check(!closed) { "Index already closed." }
        val start = openRanges.remove(id)
        check(start != null) { "Id $id must have start already" }
        addRange(id, start, end)
    }

    override fun finishIndex() {
        if (closed) return
        check(openRanges.isEmpty()) { "Index has some non-finished ranges" }
        quickSort(0, size)
        closed = true
    }

    private fun ensureCapacity() {
        if (size < ids.size) return

        val newSize = (ids.size * 1.25).roundToInt()
        ids = ids.copyOf(newSize)
        start = start.copyOf(newSize)
        end = end.copyOf(newSize)
    }

    private fun quickSort(from: Int, to: Int) {
        val len = to - from
        // Insertion sort on smallest arrays
        if (len < QUICKSORT_NO_REC) {
            for (i in from..<to) {
                var j = i
                while (j > from && (ids[j - 1].compareTo(ids[j])) > 0) {
                    swap(j, j - 1)
                    j--
                }
            }
            return
        }

        // Choose a partition element, v
        var m = from + len / 2 // Small arrays, middle element
        var l = from
        var n = to - 1
        if (len > QUICKSORT_MEDIAN_OF_9) { // Big arrays, pseudomedian of 9
            val s = len / 8
            l = med3(l, l + s, l + 2 * s)
            m = med3(m - s, m, m + s)
            n = med3(n - 2 * s, n - s, n)
        }
        m = med3(l, m, n) // Mid-size, med of 3

        // int v = x[m];
        var a = from
        var b = a
        var c = to - 1
        // Establish Invariant: v* (<v)* (>v)* v*
        var d = c
        while (true) {
            var comparison = 0
            while (b <= c && (ids[b].compareTo(ids[m])).also { comparison = it } <= 0) {
                if (comparison == 0) {
                    // Fix reference to pivot if necessary
                    if (a == m) m = b
                    else if (b == m) m = a
                    swap(a++, b)
                }
                b++
            }
            while (c >= b && (ids[c].compareTo(ids[m])).also { comparison = it } >= 0) {
                if (comparison == 0) {
                    // Fix reference to pivot if necessary
                    if (c == m) m = d
                    else if (d == m) m = c
                    swap(c, d--)
                }
                c--
            }
            if (b > c) break
            // Fix reference to pivot if necessary
            if (b == m) m = d
            else if (c == m) m = c
            swap(b++, c--)
        }

        // Swap partition elements back to middle
        var s: Int
        s = min(a - from, b - a)
        swap( from, b - s, s)
        s = min(d - c, to - d - 1)
        swap( b, to - s, s)

        // Recursively sort non-partition-elements
        if (((b - a).also { s = it }) > 1) quickSort(from, from + s)
        if (((d - c).also { s = it }) > 1) quickSort(to - s, to)
    }

    private fun swap(a: Int, b: Int, n: Int) {
        var a = a
        var b = b
        var i = 0
        while (i < n) {
            swap(a, b)
            i++
            a++
            b++
        }
    }
    
    private fun swap(a: Int, b: Int) {
        val i = ids[a]
        ids[a] = ids[b]
        ids[b] = i
        
        val s = start[a]
        start[a] = start[b]
        start[b] = s

        val e = end[a]
        end[a] = end[b]
        end[b] = e
    }
    
    private fun med3(a: Int, b: Int, c: Int): Int {
        val ab: Int = ids[a].compareTo(ids[b])
        val ac: Int = ids[a].compareTo(ids[c])
        val bc: Int = ids[b].compareTo(ids[c])
        return (if (ab < 0) (if (bc < 0) b else if (ac < 0) c else a) else (if (bc > 0) b else if (ac > 0) c else a))
    }
}
