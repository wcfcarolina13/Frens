package net.wcfcarolina13.GameAI;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweepReturnPolicyTest {

    @Test
    void returnsOnlyPastHalfTheRadius() {
        assertFalse(SweepReturnPolicy.shouldReturnToOrigin(0, 20), "bundle packed in place");
        assertFalse(SweepReturnPolicy.shouldReturnToOrigin(9.9 * 9.9, 20));
        assertTrue(SweepReturnPolicy.shouldReturnToOrigin(10.1 * 10.1, 20));
        assertTrue(SweepReturnPolicy.shouldReturnToOrigin(27 * 27, 20), "the headless case: table 27 blocks away");
        assertFalse(SweepReturnPolicy.shouldReturnToOrigin(100, 0));
    }

    @Test
    void sweepWalksBackAfterMakingRoom() throws Exception {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/DropSweeper.java"));
        int ensure = src.indexOf("if (ensureSpaceForDropSweep(source, player, commander)) {");
        int back = src.indexOf("returnToSweepOrigin(source, player, origin, horizontalRadius);", ensure);
        int cont = src.indexOf("continue;", back);
        assertTrue(ensure > 0 && back > ensure && cont > back);
    }
}
