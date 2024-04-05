/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package fuzzing.CATreeMapAVL;

/**
 * Please see the file "CATreeMapAVL.java" for details.
 *
 * @author Kjell Winblad
 */
public interface Invalidatable {
    public boolean isValid();
    public void invalidate();
}