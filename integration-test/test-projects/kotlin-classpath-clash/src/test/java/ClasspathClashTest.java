import kotlin.Unit;
import kotlin.collections.CollectionsKt;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class ClasspathClashTest {
    @Test
    public void capturesKotlinValues() {
        assertNotNull(Unit.INSTANCE);
        assertNotNull(CollectionsKt.listOf("kotlin", "stdlib"));
    }
}
