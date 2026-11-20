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
 * Minimal multi-arg base for {@link JavaSuperCallConstructorFixture}, so the subclass constructor
 * begins with a real {@code super(...)} call. Public (with a public constructor) so the verifier
 * regression test can define the transformed subclass in a throwaway class loader while resolving
 * this base from the parent loader without a package-private access error.
 */
public class JavaSuperCallConstructorBaseFixture {
    public JavaSuperCallConstructorBaseFixture(Object id, Object createdDate, Object updatedDate) {
    }
}
