/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.fixtures;

import java.util.Optional;

/**
 * JBRes-9242 reproducer for the same-basic-block dedup case. A ternary whose false-branch
 * is a chained call broken across multiple source lines. {@code javac} emits two
 * {@code LINENUMBER 33} directives for {@code chainedCall} (false-branch entry + chained
 * {@code .orElse}), separated only by an unrelated {@code LINENUMBER 32} anchor for the
 * intermediate {@code Optional.ofNullable(value)} call. The second
 * {@code LINENUMBER 33} directive is collapsed because no jump / switch / catch-handler
 * target sits between it and the first one.
 *
 * <p>This fixture deliberately omits the lambda body that {@link JavaChainedCallFixture}
 * exercises, so the same-BB dedup behavior can be asserted independently of the
 * lambda-shadow skip.
 */
public final class JavaChainedCallShapeFixture {

    public Object chainedCall(Integer id) {
        return id == null ? new Object()
                : Optional.<Object>ofNullable(id)
                    .orElse(new Object());
    }
}
