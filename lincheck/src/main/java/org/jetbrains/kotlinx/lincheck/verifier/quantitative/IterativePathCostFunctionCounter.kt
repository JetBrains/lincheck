package org.jetbrains.kotlinx.lincheck.verifier.quantitative

/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

/**
 * Implementation of this interface counts path cost incrementally.
 */
interface IterativePathCostFunctionCounter {
    /**
     * Returns next path cost counter with the required information for incremental counting
     * if the transition is possible, {@code null} if the transition is not satisfied.
     *
     * @param costWithNextCostCounter describes the transition
     */
    fun next(cost: Int, predicate: Boolean = (cost != 0)): IterativePathCostFunctionCounter?
}