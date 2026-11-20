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

/**
 * Mirrors the killbill {@code DefaultAccount.<init>} shape that triggered the
 * uninitialized-{@code this} {@code VerifyError}: a constructor whose first statement is a
 * {@code super(...)} call on its own line, followed by ordinary field-initialiser lines.
 *
 * <p>Unlike {@link JavaMultiLineDelegatingConstructorFixture} (which is about <em>missing</em>
 * {@code LINENUMBER}s on multi-line delegation argument lines), <strong>every line here carries
 * its own {@code LINENUMBER}</strong>. The {@code super(...)} line is therefore a perfectly
 * normal breakpoint target — the IDE's JDI debugger stops on it without issue.
 *
 * <p>The bug is purely in snapshot instrumentation. At the {@code super(...)} line, {@code this}
 * (local slot 0) is still {@code uninitializedThis} because the super constructor hasn't run yet.
 * The transformer used to capture it with {@code ALOAD 0} into the locals {@code Object[]}, which
 * the JVM verifier rejects ("uninitializedThis is not assignable to Object"), making the whole
 * class fail to (re)transform and silently revert to un-instrumented.
 */
public final class JavaSuperCallConstructorFixture extends JavaSuperCallConstructorBaseFixture {

    private final Object externalKey;
    private final Object email;

    public JavaSuperCallConstructorFixture(
            Object id,
            Object createdDate,
            Object updatedDate,
            Object externalKey,
            Object email) {
        super(id, createdDate, updatedDate);
        this.externalKey = externalKey;
        this.email = email;
    }
}
