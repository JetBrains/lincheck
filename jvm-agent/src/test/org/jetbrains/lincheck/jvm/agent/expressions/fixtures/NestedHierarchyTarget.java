/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions.fixtures;

import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.external.ExternalConstructorBase;

/** Nested classes with their own hierarchy, the shape of a sources-only application model. */
public class NestedHierarchyTarget {
    public static class Address {
        public String city = "Sun Prairie";
    }

    /** No zero-argument constructor: a subclass stub has to call this one explicitly. */
    public static class Person {
        public String email;

        public Person(String email) {
            this.email = email;
        }
    }

    public static class Owner extends Person {
        public int id = 2;
        public Address address = new Address();
        public Address[] addresses = { new Address() };

        public Owner() {
            super("owner@example.com");
        }
    }

    public static class ExternalOwner extends ExternalConstructorBase {
        public ExternalOwner() {
            super("external-owner");
        }
    }
}
