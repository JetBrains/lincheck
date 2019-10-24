package org.jetbrains.kotlinx.lincheck.util

import kotlin.math.pow

/**
 * Pseudo random implementation based on Sobol sequences.
 * Use O(number of dimensions * log (number of points)) memory.
 * Details in https://web.maths.unsw.edu.au/~fkuo/sobol/joe-kuo-notes.pdf
 */
class PseudoRandom private constructor(
        private val lastValues: MutableList<Int>, // last value for i-th dimension
        private val lastIds: MutableList<Int>, // last used id for i-th dimension
        private var nextDimension: Int // next coordinate number
) {
    // direction numbers for i-th dimension
    private val directionNumbers = mutableListOf<MutableList<Int>>()

    constructor(): this(mutableListOf(), mutableListOf(), 0)

    /**
     * All values generated after call of this function will belong to next point
     */
    fun endLastPoint() {
        nextDimension = 0
    }

    fun nextDouble(): Double {
        if (nextDimension == lastValues.size) {
            lastValues.add(0)
            lastIds.add(0)
            nextDimension++
            return 0.0
        }

        val bit = lowestZeroBit(lastIds[nextDimension]++)
        val randomNumber = lastValues[nextDimension] xor getDirectionNumber(nextDimension, bit)
        lastValues[nextDimension++] = randomNumber

        var result = randomNumber / 2.0.pow(32)
        if (result < 0)
            result += 1.0 // handling sign bit

        return result
    }

    fun nextInt(upperBound: Int = Int.MAX_VALUE): Int = (nextDouble() * upperBound).toInt()

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
                // handle negative shr so that not to copy sign bit
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