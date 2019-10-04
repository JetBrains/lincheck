package org.jetbrains.kotlinx.lincheck.util

import org.jetbrains.kotlinx.lincheck.util.PseudoRandomPolynomials.getPrimitivePolynomial
import kotlin.math.pow

/**
 * Pseudo random implementation based on Sobol sequences.
 * Use O(number of dimensions * log (number of points)) memory
 */
class PseudoRandom private constructor(
        private val lastValues: MutableList<Int>, // last value for i-th dimension
        private val lastIds: MutableList<Int>, // last used id for i-th dimension
        private var nextDimension: Int // next coordinate number
) {
    // direction numbers for i-th dimension
    private val directionNumbers = mutableListOf<MutableList<Int>>()

    /**
     * All values generated after call of this function will belong to next point
     */
    fun endLastPoint() {
        nextDimension = 0
    }

    fun nextDouble(): Double {
        if (lastValues.size == nextDimension) {
            lastValues.add(0)
            lastIds.add(0)
            nextDimension++
            return 0.0
        }

        val bit = lowestZeroBit(lastIds[nextDimension]++)
        val result = lastValues[nextDimension] xor getDirectionNumber(nextDimension, bit)
        lastValues[nextDimension++] = result

        var double = result / 2.0.pow(32)
        if (double < 0)
            double += 1.0 // handling sign bit

        return double
    }

    fun nextInt(upperBound: Int = Int.MAX_VALUE): Int {
        return (nextDouble() * upperBound).toInt()
    }

    constructor(): this(mutableListOf(), mutableListOf(), 0)

    fun copy(): PseudoRandom {
        return PseudoRandom(lastValues.toMutableList(), lastIds.toMutableList(), nextDimension)
    }

    /**
     * Direction numbers are calculated and memorized lazily on demand.
     */
    private fun getDirectionNumber(dimensionId: Int, id: Int): Int {
        while (dimensionId >= directionNumbers.size)
            directionNumbers.add(mutableListOf())

        while (id >= directionNumbers[dimensionId].size) {
            val polynomial = getPrimitivePolynomial(dimensionId)
            val degree = polynomial.coefs.size
            val currentSize = directionNumbers[dimensionId].size

            if (currentSize < degree) {
                directionNumbers[dimensionId].add(polynomial.coefs[currentSize] shl (31 - currentSize))
            } else {
                val numbers = directionNumbers[dimensionId]

                var value = numbers[currentSize - degree] xor (numbers[currentSize - degree] shr degree)

                // TODO: use UInt when won't be experimental
                // handle negative shr so that no to copy sign bit
                if (numbers[currentSize - degree] and (1 shl 31) != 0) {
                    for (j in 0 until degree)
                        value = value xor (1 shl (31 - j))
                }

                for (k in 0 until degree - 1) {
                    val shouldXor = (polynomial.mask shr (degree - 2 - k)) % 2 == 1
                    if (shouldXor)
                        value = value xor numbers[currentSize - k - 1]
                }

                numbers.add(value)
            }
        }

        return directionNumbers[dimensionId][id]
    }

    private fun lowestZeroBit(a: Int): Int {
        var result = 0
        var value = a
        while (value % 2 == 1) {
            value /= 2
            result++
        }
        return result
    }
}