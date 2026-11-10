/*
 * Copyright (c) 2005, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.jcstress.infra.processors;

import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.Result;
import org.openjdk.jcstress.annotations.Signal;
import org.openjdk.jcstress.annotations.State;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class TestInfo {

    private List<ExecutableElement> actors;
    private TypeElement state;
    private TypeElement result;
    private ExecutableElement arbiter;
    private TypeElement test;
    private ExecutableElement signal;
    private String generatedName;
    private boolean requiresFork;
    private Collection<Outcome> outcomes;
    private Collection<String> refs;
    private String description;

    public TestInfo() {
        actors = new ArrayList<>();
        outcomes = new ArrayList<>();
        refs = new ArrayList<>();
        actors = new ArrayList<>();
    }

    public void addActor(ExecutableElement element) {
        actors.add(element);
    }

    public void setState(TypeElement element) {
        if (state == null || state.equals(element)) {
            state = element;
        } else {
            throw new GenerationException("We can only have a single @" + State.class.getSimpleName() + " per test.", element);
        }
    }

    public void setArbiter(ExecutableElement element) {
        if (arbiter == null || arbiter.equals(element)) {
            arbiter = element;
        } else {
            throw new GenerationException("We can only have a single @" + Arbiter.class.getSimpleName() + " per test.", element);
        }
    }

    public void setResult(TypeElement element) {
        if (result == null || result.equals(element)) {
            result = element;
        } else {
            throw new GenerationException("We can only have a single @" + Result.class.getSimpleName() + " per test.", element);
        }
    }

    public void setTest(TypeElement element) {
        if (test == null || test.equals(element)) {
            test = element;
        } else {
            throw new GenerationException("We can only have a single test.", element);
        }
    }

    public void setSignal(ExecutableElement element) {
        if (signal == null || signal.equals(element)) {
            signal = element;
        } else {
            throw new GenerationException("We can only have a single @" + Signal.class.getName() + " method.", element);
        }
    }

    public TypeElement getState() {
        return state;
    }

    public TypeElement getResult() {
        return result;
    }

    public TypeElement getTest() {
        return test;
    }

    public List<ExecutableElement> getActors() {
        return actors;
    }

    public ExecutableElement getArbiter() {
        return arbiter;
    }

    public ExecutableElement getSignal() {
        return signal;
    }

    public void setGeneratedName(String generatedName) {
        this.generatedName = generatedName;
    }

    public String getGeneratedName() {
        return generatedName;
    }

    public void setRequiresFork(boolean requiresFork) {
        this.requiresFork = requiresFork;
    }

    public boolean isRequiresFork() {
        return requiresFork;
    }

    public void addCase(Outcome c) {
        if (c.desc().contains("\n")) {
            throw new GenerationException("Outcome descriptions can not contain line breaks.", test);
        }
        outcomes.add(c);
    }

    public Collection<Outcome> cases() {
        return outcomes;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void addRef(String value) {
        refs.add(value);
    }

    public Collection<String> refs() {
        return refs;
    }
}
