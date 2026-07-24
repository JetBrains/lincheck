package org.jetbrains.lincheck_test.project.loops;

public class JavaDoLoopRepresentationTest extends BaseLoopTest {
    public Object escape = null;

    @Override
    public void operation() {
        int i = 1;
        escape = "START";
        do {
            Object a = i;
            escape = a.toString();
            i++;
        } while (i < 3);
        escape = "END";

    }
}
