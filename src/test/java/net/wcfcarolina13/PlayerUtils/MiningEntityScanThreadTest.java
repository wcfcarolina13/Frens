package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The painting scan in isNeverBreakAt is a world entity query; run on a skill worker it threw
 * ConcurrentModificationException in the entity lookup ("Failed to prepare mining task").
 */
class MiningEntityScanThreadTest {
    private static String source(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/net/wcfcarolina13", relative))
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    @Test
    void prepareSkipsEntityScanOffServerThread() throws IOException {
        String mining = source("PlayerUtils/MiningTool.java");
        int flag = mining.indexOf("boolean onServerThread = server != null && server.isOnThread();");
        int prepare = mining.indexOf("ProtectedStructureBlockHelper.isNeverBreakAt(bot.getEntityWorld(), targetBlockPos, onServerThread)");
        assertTrue(flag >= 0 && prepare > flag, "prepare must pass the server-thread flag");
    }

    @Test
    void serverThreadTickKeepsFullCheckBeforeBreaking() throws IOException {
        String mining = source("PlayerUtils/MiningTool.java");
        int tick = mining.indexOf("server.execute(() -> {", mining.indexOf("MINING_EXECUTOR.scheduleAtFixedRate("));
        int full = mining.indexOf("ProtectedStructureBlockHelper.isNeverBreakAt(bot.getEntityWorld(), targetBlockPos)\n", tick);
        int brk = mining.indexOf("bot.interactionManager.tryBreakBlock(targetBlockPos)", tick);
        assertTrue(tick >= 0 && full > tick && brk > full, "full check (with paintings) must run on the tick before tryBreakBlock");
    }

    @Test
    void helperGatesOnlyThePaintingScan() throws IOException {
        String helper = source("GameAI/services/ProtectedStructureBlockHelper.java");
        assertTrue(helper.contains("return isNeverBreakAt(world, pos, true);"), "default overload keeps the scan");
        assertTrue(helper.contains("if (scanEntities && hasPaintingAttachedTo(world, pos)) return true;"));
    }
}
