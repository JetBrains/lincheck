/*
 *  Lincheck - Linearizability checker
 *  Copyright (C) 2015 Devexperts LLC
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.devexperts.dxlab.lincheck.asm;


import com.devexperts.dxlab.lincheck.asm.templ.*;
import jdk.internal.org.objectweb.asm.*;

import java.lang.reflect.Constructor;

public class ClassGenerator implements Opcodes {

    public static Generated generate(
            Object test,
            String pointedClassName,
            String generatedClassName, // "com/devexperts/dxlab/lincheck/asmtest/Generated2"
            String testFieldName, // queue
            String testClassName, // com/devexperts/dxlab/lincheck/tests/custom/QueueTestAnn
            String[] methodNames
    ) throws Exception {
        DynamicClassLoader loader = new DynamicClassLoader();

        int n = methodNames.length;
        Class<?> clz = null;
        if (n == 1) {
            clz = loader.define(pointedClassName,
                    Generated1Dump.dump(
                            generatedClassName,
                            testFieldName,
                            testClassName,
                            methodNames
                    ));
        } else if (n == 2) {
            clz = loader.define(pointedClassName,
                    Generated2Dump.dump(
                            generatedClassName,
                            testFieldName,
                            testClassName,
                            methodNames
                    ));
        } else if (n == 3) {
            clz = loader.define(pointedClassName,
                    Generated3Dump.dump(
                            generatedClassName,
                            testFieldName,
                            testClassName,
                            methodNames
                    ));
        } else if (n == 4) {
            clz = loader.define(pointedClassName,
                    Generated4Dump.dump(
                            generatedClassName,
                            testFieldName,
                            testClassName,
                            methodNames
                    ));
        } else if (n == 5) {
            clz = loader.define(pointedClassName,
                    Generated5Dump.dump(
                            generatedClassName,
                            testFieldName,
                            testClassName,
                            methodNames
                    ));
        } else {
            throw new IllegalArgumentException("Count actor should be from 1 to 5 inclusive");
        }


        Constructor<?>[] ctors = clz.getConstructors();
        Constructor<?> ctor = ctors[1];
        Generated o = (Generated) ctor.newInstance(test);
        return o;
    }

    private static class DynamicClassLoader extends ClassLoader {
        public Class<?> define(String className, byte[] bytecode) {
            return super.defineClass(className, bytecode, 0, bytecode.length);
        }
    };
}
