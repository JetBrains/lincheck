package org.jetbrains.lincheck_test.project.loops;

public class JavaForLoopRepresentationTest extends BaseLoopTest {
    public Object escape = null;

    @Override
    public void operation() {
        escape = "START";
        for (int i = 1; i < 3; i++) {
            Object a = i;
            escape = a.toString();
        }
        escape = "END";

    }
}
