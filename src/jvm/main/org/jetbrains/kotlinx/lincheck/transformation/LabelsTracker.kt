/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.objectweb.asm.*


private class LabelInfo(val id: Int, var backBranched: Boolean = false)

/**
 * [LabelsTracker] object is used to maintain the mapping between unique IDs and labels in code.
 */
internal object LabelsTracker {
    private val labelsByPosition = HashMap<String, LabelInfo>()
    private val labelsById = ArrayList<LabelInfo>()

    @Synchronized
    @JvmStatic
    fun newLabel(className: String, methodName: String, label: Label): Int {
        return labelsByPosition.computeIfAbsent(getLabelKey(className, methodName, label)) {
            val info = LabelInfo(labelsById.size)
            labelsById.add(info)
            info
        }.id
    }

    @Synchronized
    @JvmStatic
    fun newJumpTarget(className: String, methodName: String, label: Label): Int {
        val info = labelsByPosition.computeIfAbsent(getLabelKey(className, methodName, label)) {
            val info = LabelInfo(labelsById.size)
            labelsById.add(info)
            info
        }
        info.backBranched = true
        return info.id
    }

    @JvmStatic
    fun isLabelBackBranched(id: Int) = labelsById[id].backBranched

    private fun getLabelKey(className: String, methodName: String, label: Label): String {
        return "$className#$methodName:$label"
    }
}
