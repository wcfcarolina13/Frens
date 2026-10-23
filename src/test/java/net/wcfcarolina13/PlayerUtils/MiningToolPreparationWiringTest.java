package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MiningToolPreparationWiringTest {
    private static final Path MAIN = Path.of("src/main/java/net/wcfcarolina13/PlayerUtils");
    private static final String EQUIP = "String refusal = equipHarmlessFallback(bot, ";

    @Test
    void bothToolHopsUseCancellableDispatchAndKeepInlineFailureFallback() throws IOException {
        for (String name : new String[]{"MiningTool.java", "ToolSelector.java"}) {
            String body = block(source(name), "private static <T> T callOnServer(");
            assertTrue(body.contains("return BotActions.callOnServerThread(bot, () ->"), name);
            assertTrue(body.contains("}, 2_000L, fallback)"), name);
            assertTrue(body.contains("catch (Throwable t)"), name);
            assertTrue(body.contains("return result == null ? fallback : result;"), name);
            assertFalse(body.contains("CompletableFuture"), name);
            assertFalse(body.contains("server.execute"), name);
        }
    }

    @Test
    void toolEquipVerifiesActualHeldStackAfterBothHotbarSelectionPaths() throws IOException {
        String body = block(source("MiningTool.java"),
                "private static boolean switchToTool(ServerPlayerEntity bot, ItemStack tool, boolean allowBundleReach)");
        assertTrue(body.contains("BotActions.selectHotbarSlot(bot, slot);\n"
                + "                    return ToolSelector.slotStillMatches(expected, bot.getMainHandStack());"));
        assertTrue(body.contains("BotActions.selectHotbarSlot(bot, target);\n"
                + "                return ToolSelector.slotStillMatches(expected, bot.getMainHandStack());"));
    }

    @Test
    void harmlessFallbackReportsTheActualHeldSafetyInsideTheServerAction() throws IOException {
        String source = source("MiningTool.java");
        String body = block(source, "private static HarmlessFallbackPolicy.Outcome switchToHarmlessFallback(");
        assertTrue(body.contains("return callOnServer(bot, () ->"));
        // Only a hop that never ran reports TIMED_OUT; a step that threw on the server did run.
        assertTrue(body.contains("}, HarmlessFallbackPolicy.Outcome.TIMED_OUT)"));
        String caught = body.substring(body.indexOf("catch (Throwable t)"));
        assertTrue(caught.contains("return HarmlessFallbackPolicy.Outcome.NO_SAFE_ITEM;"));
        assertTrue(body.indexOf("switchToHarmlessFallbackOnThread(bot, target)")
                < body.indexOf("ItemStack held = bot.getMainHandStack()"));
        assertTrue(body.contains("return held.isEmpty() || (isNonWeaponMiningFallback(held) && !wastesDrop(held, target))\n"
                + "                        ? HarmlessFallbackPolicy.Outcome.EQUIPPED\n"
                + "                        : HarmlessFallbackPolicy.Outcome.NO_SAFE_ITEM;"));
        String equip = block(source, "private static String equipHarmlessFallback(");
        assertTrue(equip.contains("HarmlessFallbackPolicy.decide(switchToHarmlessFallback(bot, target), attempt)"));
        assertTrue(equip.contains("return HarmlessFallbackPolicy.abortMessage(next);"));
        assertTrue(equip.contains("LOGGER.info("));
        String acceptable = block(source, "private static boolean isNonWeaponMiningFallback(");
        assertTrue(acceptable.contains("!isWeaponOnlyItem(stack)"));
        assertTrue(acceptable.contains("stack.get(DataComponentTypes.FOOD) == null"));
        assertTrue(block(source, "private static boolean wastesDrop(").contains("stack.isOf(Items.SHEARS)"));
    }

    @Test
    void initialEquipAndBothUnsafePreserveBranchesStopBeforeMiningStarts() throws IOException {
        String source = source("MiningTool.java");
        String initial = source.substring(source.indexOf("if (!preserveSelectedHotbarItem)"),
                source.indexOf("// Harvest-level protection"));
        String normalGuard = "if (!switchToTool(bot, bestTool))";
        assertRefuses(block(initial, normalGuard), "return miningResult;");
        String preserve = initial.substring(initial.indexOf("} else {"));
        String guard = EQUIP + "blockState, targetBlockPos);";
        int first = preserve.indexOf(guard);
        int second = preserve.indexOf(guard, first + guard.length());
        assertTrue(first >= 0 && second > first);
        assertRefuses(preserve.substring(first, second), "return miningResult;");
        assertRefuses(preserve.substring(second), "return miningResult;");
        assertTrue(source.indexOf(normalGuard) < source.indexOf("bot.swingHand(Hand.MAIN_HAND)"));
        assertTrue(source.indexOf(normalGuard) < source.indexOf("MINING_EXECUTOR.scheduleAtFixedRate"));
    }

    @Test
    void failedRepollFallbackStopsBeforeProgressAndBreakMutation() throws IOException {
        String source = source("MiningTool.java");
        String guard = "if (!swapped)";
        assertRefuses(block(source, guard), "return;");
        int refusalAt = source.indexOf(guard);
        assertTrue(refusalAt < source.indexOf("lastRepollSwap.set(bot.getMainHandStack().copy())"));
        assertTrue(refusalAt < source.indexOf("bot.interactionManager.tryBreakBlock(targetBlockPos)"));
    }

    @Test
    void fallbackStillPrefersEmptyHandsThenHarmlessItemsBeforeDamageableTools() throws IOException {
        String body = block(source("MiningTool.java"), "private static void switchToHarmlessFallbackOnThread(");
        int empty = body.indexOf("stack.isEmpty()");
        int harmless = body.indexOf("MiningTool::isHarmlessMiningFallback");
        int lastResort = body.indexOf("isNonWeaponMiningFallback(stack) && !wastesDrop(stack, target)");
        assertTrue(empty >= 0 && harmless > empty && lastResort > harmless);
    }

    private static void assertRefuses(String body, String returnStatement) {
        int equip = body.indexOf(EQUIP);
        int refuse = body.indexOf("if (refusal != null) {");
        int complete = body.indexOf("miningResult.complete(refusal);");
        int ret = body.indexOf(returnStatement);
        assertTrue(equip >= 0 && refuse > equip && complete > refuse && ret > complete, body);
    }

    private static String source(String name) throws IOException {
        return Files.readString(MAIN.resolve(name));
    }

    private static String block(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertTrue(signatureAt >= 0, "Missing signature: " + signature);
        int start = source.indexOf('{', signatureAt);
        int depth = 1;
        for (int end = start + 1; end < source.length(); end++) {
            char c = source.charAt(end);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(start + 1, end);
        }
        throw new AssertionError("Unclosed block: " + signature);
    }
}
