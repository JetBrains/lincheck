import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SourceWatchesTest {
    @Test
    public void capturesOwner() {
        capture(new Owner("Davis"));
    }

    private static void capture(Owner owner) {
        assertEquals("Davis", owner.getLastName());
    }
}
