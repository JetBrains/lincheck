package org.jetbrains.kotlinx.lincheck;

import org.objectweb.asm.ClassWriter;

import static org.jetbrains.kotlinx.lincheck.TransformationClassLoader.*;

/**
 * ClassWriter for classes transformed by LinCheck
 */
public class TransformationClassWriter extends ClassWriter {
    private ClassLoader loader;

    public TransformationClassWriter(int flags, ClassLoader loader) {
        super(flags);
        this.loader = loader;
    }

    protected ClassLoader getClassLoader() {
        return loader;
    }

    @Override
    protected String getCommonSuperClass(final String type1, final String type2) {
        String result = super.getCommonSuperClass(originalName(type1), originalName(type2));
        if (result.startsWith(JAVA_UTIL_PACKAGE)) {
            return TRANSFORMED_PACKAGE + result;
        }
        return result;
    }
}
