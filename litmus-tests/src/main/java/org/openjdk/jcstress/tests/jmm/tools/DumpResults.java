/*
 * Adapted from Lincheck litmus tests.
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openjdk.jcstress.tests.jmm.tools;

import org.openjdk.jcstress.infra.collectors.DiskReadCollector;
import org.openjdk.jcstress.infra.collectors.InProcessCollector;
import org.openjdk.jcstress.infra.collectors.TestResult;

/** Exports each raw JCStress configuration without interpreting absent outcomes. */
public final class DumpResults {
    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    public static void main(String[] args) throws Exception {
        InProcessCollector collector = new InProcessCollector();
        for (String file : args) {
            DiskReadCollector reader = new DiskReadCollector(file, collector);
            reader.dump();
            reader.close();
        }
        for (TestResult result : collector.getTestResults()) {
            StringBuilder states = new StringBuilder();
            for (String state : result.getStateKeys()) {
                if (states.length() != 0) states.append(',');
                states.append(quote(state)).append(':').append(result.getCount(state));
            }
            System.out.println("{\"name\":" + quote(result.getName())
                    + ",\"status\":" + quote(result.status().toString())
                    + ",\"jvm_args\":" + quote(result.getConfig().jvmArgs.toString())
                    + ",\"compile_mode\":" + result.getConfig().compileMode
                    + ",\"fork\":" + result.getConfig().forkId
                    + ",\"messages\":" + quote(result.getMessages().toString())
                    + ",\"outcomes\":{" + states + "}}");
        }
    }
}
