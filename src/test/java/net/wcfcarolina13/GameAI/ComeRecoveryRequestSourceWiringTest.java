package net.wcfcarolina13.GameAI;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ComeRecoveryRequestSourceWiringTest {

    @Test
    void comeRecoverySkillCarriesTheCommanderAsRequestSource() throws Exception {
        // Tool provisioning reads the commander's crafting history (seen headless 2026-11-07:
        // without it a sealed-in bot with 4 logs mined stone bare-handed).
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java"));
        int ctx = src.indexOf("SharedStateService.safeSharedState(\"come-recovery\")");
        int req = src.indexOf("commander != null ? commander.getCommandSource() : null", ctx);
        int confirmed = src.indexOf("[ComeRecovery] launch-confirmed", ctx);
        assertTrue(ctx > 0 && req > ctx && req < confirmed);
    }
}
