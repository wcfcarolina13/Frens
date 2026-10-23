package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MiningToolPreparationWiringTest {
    private static final Path MAIN = Path.of("src/main/java/net/wcfcarolina13/PlayerUtils");
    private static final String REFUSAL = "Cannot mine: could not equip a safe tool or empty hand.";

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
        String body = block(source, "private static boolean switchToHarmlessFallback(");
        assertTrue(body.contains("return callOnServer(bot, () ->"));
        assertTrue(body.contains("}, Boolean.FALSE)"));
        assertTrue(body.indexOf("switchToHarmlessFallbackOnThread(bot, target)")
                < body.indexOf("ItemStack held = bot.getMainHandStack()"));
        assertTrue(body.contains("return held.isEmpty() || (isNonWeaponMiningFallback(held) && !wastesDrop(held, target));"));
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
        String normalGuard = "if (!switchToTool(bot, bestTool) && !switchToHarmlessFallback(bot, blockState))";
        assertRefuses(block(initial, normalGuard), "return miningResult;");
        String preserve = initial.substring(initial.indexOf("} else {"));
        String guard = "if (!switchToHarmlessFallback(bot, blockState))";
        int first = preserve.indexOf(guard);
        int second = preserve.indexOf(guard, first + guard.length());
        assertTrue(first >= 0 && second > first);
        assertRefuses(block(preserve.substring(first), guard), "return miningResult;");
        assertRefuses(block(preserve.substring(second), guard), "return miningResult;");
        assertTrue(source.indexOf(normalGuard) < source.indexOf("bot.swingHand(Hand.MAIN_HAND)"));
        assertTrue(source.indexOf(normalGuard) < source.indexOf("MINING_EXECUTOR.scheduleAtFixedRate"));
    }

    @Test
    void failedRepollFallbackStopsBeforeProgressAndBreakMutation() throws IOException {
        String source = source("MiningTool.java");
        String guard = "if (!swapped && !switchToHarmlessFallback(bot, currentState))";
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
        assertTrue(body.contains("miningResult.complete("));
        assertTrue(body.contains(REFUSAL));
        assertTrue(body.contains(returnStatement));
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
