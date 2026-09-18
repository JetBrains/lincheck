/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions.kotlin.compiler;

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
import org.jetbrains.kotlin.psi.KtPsiFactory;

import java.util.LinkedHashSet;
import java.util.Set;

/** Analyzes expression names inside the isolated Kotlin compiler class loader. */
public final class KotlinExpressionAnalyzer {
    private KotlinExpressionAnalyzer() {}

    public static String[][] analyze(String[] expressions) {
        Disposable disposable = Disposer.newDisposable();
        try {
            KotlinCoreEnvironment environment = KotlinCoreEnvironment.createForProduction(
                disposable,
                new CompilerConfiguration(),
                EnvironmentConfigFiles.JVM_CONFIG_FILES
            );
            KtPsiFactory psiFactory = new KtPsiFactory(environment.getProject(), false);
            Set<String> referenced = new LinkedHashSet<>();
            Set<String> called = new LinkedHashSet<>();
            for (String source : expressions) {
                collectExpressionNames(psiFactory.createExpression(source), referenced, called);
            }
            return new String[][] {
                referenced.toArray(new String[0]),
                called.toArray(new String[0]),
            };
        } finally {
            Disposer.dispose(disposable);
        }
    }

    private static void collectExpressionNames(
        PsiElement element,
        Set<String> referenced,
        Set<String> called
    ) {
        if (element instanceof KtNameReferenceExpression) {
            referenced.add(((KtNameReferenceExpression) element).getReferencedName());
        }
        if (element instanceof KtCallExpression) {
            KtExpression callee = ((KtCallExpression) element).getCalleeExpression();
            if (callee instanceof KtNameReferenceExpression) {
                called.add(((KtNameReferenceExpression) callee).getReferencedName());
            }
        }
        for (PsiElement child : element.getChildren()) {
            collectExpressionNames(child, referenced, called);
        }
    }
}
