/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.wrapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Loads the tracing agent and its dependencies outside the application classloader.
 */
public final class AgentWrapper {
    private static final String REAL_AGENT_CLASS_ATTRIBUTE = "Tracing-Agent-Class";

    private static URLClassLoader agentClassLoader;

    private AgentWrapper() {}

    public static synchronized void premain(String agentArgs, Instrumentation instrumentation) throws Exception {
        launch("premain", agentArgs, instrumentation);
    }

    public static synchronized void agentmain(String agentArgs, Instrumentation instrumentation) throws Exception {
        launch("agentmain", agentArgs, instrumentation);
    }

    private static void launch(String entryPoint, String agentArgs, Instrumentation instrumentation) throws Exception {
        // The wrapper jar is appended to the system class path, so resolving the manifest or the nested jars
        // by resource name would search the whole application class path and could pick up
        // an unrelated `MANIFEST.MF` / `bootstrap.jar` / `agent-payload.jar`.
        // Everything we need is read from the wrapper's own code source instead.
        String agentClassName = "";
        JarFile bootstrapJar = null;
        try (JarFile wrapperJar = openWrapperJar()) {
            agentClassName = readAgentClassName(wrapperJar);
            if (agentClassLoader == null) {
                bootstrapJar = extractBootstrapJar(wrapperJar);
            }
            initializeAgentClassLoader(wrapperJar);
        }

        Thread thread = Thread.currentThread();
        ClassLoader previousContextClassLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(agentClassLoader);
        try {
            Class<?> agentClass = Class.forName(agentClassName, true, agentClassLoader);
            Class<?> instrumentationClass = Class.forName(
                "org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation",
                true,
                agentClassLoader
            );
            instrumentationClass
                .getMethod("setAgentClassLoader", ClassLoader.class)
                .invoke(null, agentClassLoader);
            if (bootstrapJar != null) {
                instrumentationClass
                    .getMethod("appendBootstrapJarToClassLoaderSearch", Instrumentation.class, JarFile.class)
                    .invoke(null, instrumentation, bootstrapJar);
            }
            Method method = agentClass.getMethod(entryPoint, String.class, Instrumentation.class);
            invoke(method, agentArgs, instrumentation);
        } finally {
            thread.setContextClassLoader(previousContextClassLoader);
        }
    }

    private static void initializeAgentClassLoader(JarFile wrapperJar) throws Exception {
        if (agentClassLoader != null) return;

        File agentPayloadJarFile = extractJar(wrapperJar, "agent-payload.jar", "lincheck-agent-payload");
        agentClassLoader = new URLClassLoader(
            new URL[] { agentPayloadJarFile.toURI().toURL() },
            platformClassLoader()
        );
        // Both jars stay open and are removed by the `deleteOnExit` hooks registered in `extractJar`.
        // Closing them from a shutdown hook of our own would race the agent's shutdown hooks --
        // those still load payload classes while dumping the trace -- and JVM shutdown hooks run concurrently.
    }

    private static JarFile extractBootstrapJar(JarFile wrapperJar) throws IOException {
        return new JarFile(extractJar(wrapperJar, "bootstrap.jar", "lincheck-bootstrap"));
    }

    private static ClassLoader platformClassLoader() throws Exception {
        try {
            Method method = ClassLoader.class.getMethod("getPlatformClassLoader");
            return (ClassLoader) method.invoke(null);
        } catch (NoSuchMethodException ignored) {
            return ClassLoader.getSystemClassLoader().getParent();
        }
    }

    private static File extractJar(JarFile wrapperJar, String entryName, String prefix) throws IOException {
        JarEntry entry = wrapperJar.getJarEntry(entryName);
        if (entry == null) {
            throw new IOException("Missing nested agent jar " + entryName + " in " + wrapperJar.getName());
        }
        File file = File.createTempFile(prefix, ".jar");
        file.deleteOnExit();
        try (InputStream input = wrapperJar.getInputStream(entry);
             FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
        return file;
    }

    private static String readAgentClassName(JarFile wrapperJar) throws IOException {
        Manifest manifest = wrapperJar.getManifest();
        if (manifest == null) throw new IOException("Missing agent manifest in " + wrapperJar.getName());
        String agentClassName = manifest.getMainAttributes().getValue(REAL_AGENT_CLASS_ATTRIBUTE);
        if (agentClassName == null) {
            throw new IOException("Missing manifest attribute " + REAL_AGENT_CLASS_ATTRIBUTE
                + " in " + wrapperJar.getName());
        }
        return agentClassName;
    }

    private static JarFile openWrapperJar() throws IOException {
        CodeSource codeSource = AgentWrapper.class.getProtectionDomain().getCodeSource();
        URL location = codeSource == null ? null : codeSource.getLocation();
        if (location == null) throw new IOException("Cannot locate the agent jar of " + AgentWrapper.class.getName());
        File agentJarFile;
        try {
            agentJarFile = new File(location.toURI());
        } catch (URISyntaxException exception) {
            throw new IOException("Cannot resolve the agent jar location " + location, exception);
        }
        return new JarFile(agentJarFile);
    }

    private static void invoke(Method method, String agentArgs, Instrumentation instrumentation) throws Exception {
        try {
            method.invoke(null, agentArgs, instrumentation);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw exception;
        }
    }
}
