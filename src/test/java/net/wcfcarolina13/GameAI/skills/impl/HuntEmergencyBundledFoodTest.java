package net.wcfcarolina13.GameAI.skills.impl;

import net.wcfcarolina13.PlayerUtils.BundleReachPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A hunting bot's emergency eat reaches food tucked in a bundle (the candidate scan is direct-slot only). */
class HuntEmergencyBundledFoodTest {

    @Test
    void oneBundledPieceIsPulledOnlyWhenNoDirectFoodIsLeft() {
        assertEquals(1, BundleReachPolicy.extractionsNeeded(1, 0, 5));
        assertEquals(0, BundleReachPolicy.extractionsNeeded(1, 2, 5));
        assertEquals(0, BundleReachPolicy.extractionsNeeded(1, 0, 0));
    }

    @Test
    void emergencyEatReachesIntoABundleBeforeScanningSlots() throws IOException {
        String source = mainSource("src/main/java/net/wcfcarolina13/GameAI/skills/impl/HuntSkill.java");
        String eat = bodyOf(source, "private static boolean maybeEatEmergencyFood(ServerPlayerEntity bot)");
        int reach = eat.indexOf("reachBundledFood(bot);");
        int scan = eat.indexOf("findFoodCandidate(bot, true)");
        assertTrue(reach >= 0 && reach < scan, "the bundle reach comes before the slot scan");
        assertTrue(eat.contains("if (starving || emergency) {"), "only when the bot needs to eat");
        String helper = bodyOf(source, "private static void reachBundledFood(ServerPlayerEntity bot)");
        assertTrue(helper.contains("BundleReachPolicy.extractionsNeeded(1, direct, total - direct) > 0"));
        assertTrue(helper.contains("BundleService.reachFirst(bot, HealingService::isSafeEdible)"));
    }

    private static String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, signature + " not found");
        int open = source.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new AssertionError(signature + ": unbalanced body");
    }

    private static String mainSource(String relative) throws IOException {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path at = dir; at != null; at = at.getParent()) {
            Path candidate = at.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException(relative + " not found above " + dir);
    }
}
