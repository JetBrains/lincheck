/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstruct

abstract class BinaryRelation<T> {
    abstract operator fun invoke(x: T, y: T): Boolean

    infix fun or(r: BinaryRelation<T>): BinaryRelation<T> {
        return UnionRelation(this, r)
    }

    infix fun and(r: BinaryRelation<T>): BinaryRelation<T> {
        return IntersectionRelation(this, r)
    }
}

fun<T> rel(r: (T, T) -> Boolean): BinaryRelation<T> {
    return LambdaRelation(r)
}

class UnionRelation<T>(val r1 : BinaryRelation<T>, val r2: BinaryRelation<T>): BinaryRelation<T>() {
    override fun invoke(x: T, y: T): Boolean {
        return r1(x, y) || r2(x, y)
    }
}

class IntersectionRelation<T>(val r1 : BinaryRelation<T>, val r2: BinaryRelation<T>): BinaryRelation<T>() {
    override fun invoke(x: T, y: T): Boolean {
        return r1(x, y) && r2(x, y)
    }
}

class LambdaRelation<T>(val rel: (T, T) -> Boolean): BinaryRelation<T>() {
    override fun invoke(x: T, y: T): Boolean {
        return rel(x, y)
    }
}