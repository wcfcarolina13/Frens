package net.wcfcarolina13.GameAI.skills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skills run on worker threads. These sites spawned entities, set blocks, edited the inventory or
 * teleported the bot straight from the worker; each now runs on the server thread. The checks pin
 * the mutation inside its hop so a later edit cannot quietly move it back out.
 */
class OffThreadMutationWiringTest {
    private static final String ROOT = "src/main/java/net/wcfcarolina13/GameAI/";

    @Test
    void flareSpawnsTheFireworkOnTheServerThread() throws IOException {
        String body = after(read("skills/impl/FlareSkill.java"), "public SkillExecutionResult execute(");
        int hop = body.indexOf("BotActions.callOnServerThread(bot, () ->");
        assertTrue(hop >= 0 && hop < body.indexOf(".split(1)") && hop < body.indexOf("world.spawnEntity(firework)"));
    }

    @Test
    void torchPlacerEntryPointsHop() throws IOException {
        String src = read("skills/support/TorchPlacer.java");
        assertTrue(src.contains("() -> replaceTorchAtOnServer(bot, pos), 1000L, false)"));
        assertTrue(src.contains("() -> placeTorchOnServer(bot, preferredDirection), 1000L, PlacementResult.FAILED)"));
    }

    @Test
    void plantingInteractionsUseTheHoppingPrimitive() throws IOException {
        assertFalse(read("skills/impl/HarvestCropSkill.java").contains("interactionManager.interactBlock("));
        assertFalse(read("skills/impl/PlantSeedsSkill.java").contains("interactionManager.interactBlock("));
        String farm = read("skills/impl/FarmSkill.java");
        assertEquals(2, count(farm, "BotActions.interactBlockAndSwing(bot, world, hit)"));
        String pickup = after(farm, "pickupWater(");
        pickup = pickup.substring(0, pickup.indexOf("private static boolean hasHoe("));
        assertTrue(pickup.indexOf("BotActions.callOnServerThread(bot, () ->")
                < pickup.indexOf("interactionManager.interactBlock("));
        String primitive = after(read("BotActions.java"), "interactBlockAndSwing(ServerPlayerEntity bot,");
        assertTrue(primitive.indexOf("callOnServerThread(bot, () ->") < primitive.indexOf("interactBlock("));
    }

    @Test
    void hovelSnapTeleportsOnlyInsideTheHop() throws IOException {
        String src = read("skills/impl/shelter/HovelPerimeterBuilder.java");
        assertEquals(1, count(src, "bot.teleport("));
        String helper = after(src, "private static boolean snapToOnServer(");
        assertTrue(helper.indexOf("BotActions.callOnServerThread(bot, () ->") < helper.indexOf("bot.teleport("));
        assertEquals(2, count(src, "snapWithRetry(bot, world, snap);"));
    }

    @Test
    void dropsAndPlankCraftRunOnTheServerThread() throws IOException {
        String hunt = after(read("skills/impl/HuntSkill.java"), "private static void jettisonDisposables(");
        assertTrue(hunt.indexOf("BotActions.callOnServerThread(bot, () ->") < hunt.indexOf("bot.dropItem("));
        String shelter = read("skills/impl/ShelterSkill.java");
        int pickup = shelter.indexOf("ensurePickupSlot(bot);");
        assertTrue(shelter.lastIndexOf("BotActions.callOnServerThread(bot, () ->", pickup) > pickup - 120,
                "ensurePickupSlot runs inside the cancelling hop");
        String planks = after(read("skills/impl/WoodcutSkill.java"), "private int craftPlanksForScaffold(");
        assertTrue(planks.indexOf("BotActions.callOnServerThread(bot, () ->") < planks.indexOf("stack.decrement(use)"));
        assertTrue(planks.indexOf("bot.dropItem(planks, false)") > 0, "overflow planks are dropped, not lost");
    }

    @Test
    void noSleepInsideTheNewHops() throws IOException {
        // A sleep inside a server-thread lambda would stall the whole server tick.
        String[][] lambdas = {
                {"skills/impl/FlareSkill.java", "BotActions.callOnServerThread(bot, () ->", "}, 1500L, false);"},
                {"skills/impl/WoodcutSkill.java", "return BotActions.callOnServerThread(bot, () ->", "}, 1000L, 0);"},
                {"skills/impl/shelter/HovelPerimeterBuilder.java", "return BotActions.callOnServerThread(bot, () ->", "}, 750L, false);"},
                {"BotActions.java", "net.minecraft.util.ActionResult result = bot.interactionManager", "}, 1000L, net.minecraft.util.ActionResult.FAIL);"},
        };
        for (String[] l : lambdas) {
            String src = read(l[0]);
            int start = src.indexOf(l[1]);
            int end = src.indexOf(l[2], start);
            assertTrue(start >= 0 && end > start, l[0]);
            String body = src.substring(start, end);
            assertFalse(body.contains("sleep(") || body.contains("sleepQuiet(") || body.contains("Thread.sleep"),
                    l[0] + " sleeps on the server thread");
        }
    }

    private static String read(String rel) throws IOException {
        return Files.readString(Path.of(ROOT + rel));
    }

    private static String after(String src, String marker) {
        int at = src.indexOf(marker);
        assertTrue(at >= 0, "missing " + marker);
        return src.substring(at);
    }

    private static int count(String src, String needle) {
        int n = 0;
        for (int i = src.indexOf(needle); i >= 0; i = src.indexOf(needle, i + 1)) n++;
        return n;
    }
}
