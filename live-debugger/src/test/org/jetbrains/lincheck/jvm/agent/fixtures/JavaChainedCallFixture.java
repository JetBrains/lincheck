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
 * Fixture for the JBRes-9242 reproducer: a ternary whose false-branch is a chained
 * call broken across multiple source lines. {@code javac} emits two {@code LINENUMBER 32}
 * directives for {@code findOrThrow} (false-branch entry + chained {@code .orElseThrow}),
 * and {@code lambda$findOrThrow$0} also carries {@code LINENUMBER 32}. A single
 * user-installed breakpoint at line 32 must therefore yield one — and only one —
 * snapshot hook in the transformed bytecode.
 *
 * <p>This is the same shape as
 * {@code org.springframework.samples.petclinic.owner.OwnerController.findOwner}
 * in PetClinic and as {@code IssuesResource.kt:107} in YouTrack.
 */
public final class JavaChainedCallFixture {

    public Object findOrThrow(Integer id) {
        return id == null ? new Object()
                : Optional.ofNullable(id)
                    .orElseThrow(() -> new IllegalArgumentException("not found: " + id));
    }
}
