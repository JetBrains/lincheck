import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AsmClasspathClashTest {
    @Test
    public void generatesBytecodeWithOwnAsm() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Generated", null, "java/lang/Object", null);
        writer.visitEnd();
        assertTrue(writer.toByteArray().length > 0);
        assertEquals("java/lang/String", Type.getType(String.class).getInternalName());
    }
}
