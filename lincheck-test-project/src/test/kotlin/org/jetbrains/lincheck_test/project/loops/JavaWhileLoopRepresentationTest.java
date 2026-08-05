package org.jetbrains.lincheck_test.project.loops;

public class JavaWhileLoopRepresentationTest extends BaseLoopTest {
    public Object escape = null;

    @Override
    public void operation() {
        int i = 1;
        escape = "START";
        while (i < 3) {
            Object a = i;
            escape = a.toString();
            i++;
        }
        escape = "END";

    }
}
