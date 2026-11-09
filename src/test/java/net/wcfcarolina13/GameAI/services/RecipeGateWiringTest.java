package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-reading checks for the limitedCrafting recipe gate (backlog 1224) in {@code CraftingHelper}:
 * the gate reads the vanilla rule and the commander's recipe book, leaves bots ungated, defers the
 * decision to {@link RecipeGatePolicy}, and every refusal line goes through {@code recipeRefusalLine()}.
 * Server types keep this out of a unit test.
 */
class RecipeGateWiringTest {

    private static final Path CRAFTING =
            Path.of("src/main/java/net/wcfcarolina13/GameAI/services/CraftingHelper.java");

    @Test
    void theGateReadsTheRuleAndTheCommandersBookAndDefersToThePolicy() throws IOException {
        String src = Files.readString(CRAFTING);
        String body = block(src, "private static boolean hasRecipePermission(");
        int clear = body.indexOf("RECIPE_REFUSAL_LINE.remove();");
        int nullCommander = body.indexOf("if (commander == null) {\n            return true;");
        assertTrue(clear >= 0 && nullCommander > clear, "a stale line is cleared before any early return");
        assertTrue(body.contains("!(commander instanceof net.wcfcarolina13.Entity.createFakePlayer)"),
                "a bot requester is never gated");
        assertTrue(body.contains("commander.getEntityWorld().getGameRules()\n                .getValue(net.minecraft.world.rule.GameRules.LIMITED_CRAFTING)"));
        assertTrue(body.contains("commander.getRecipeBook().isUnlocked(key)"));
        assertTrue(body.contains("recipe.get().value().isIgnoredInRecipeBook()"));
        assertTrue(body.contains("RecipeGatePolicy.decide("));
        assertTrue(body.contains("return decision == RecipeGatePolicy.Decision.ALLOW;"));
        assertFalse(body.contains("return true; // Fallback"), "the always-true fallback is gone");
    }

    @Test
    void everyMethodThatCommitsACraftIsGated() throws IOException {
        String src = Files.readString(CRAFTING);
        Matcher m = Pattern.compile("\n    (?:private|public) static [^\n(]*?\\b(\\w+)\\(").matcher(src);
        List<String> checked = new ArrayList<>();
        while (m.find()) {
            String name = m.group(1);
            if (name.equals("commitCraft") || name.equals("commitLogsToPlanks")) {
                continue; // the commit primitives themselves
            }
            int open = src.indexOf('{', m.end());
            int semi = src.indexOf(';', m.end());
            if (open < 0 || (semi >= 0 && semi < open)) {
                continue; // a field or an abstract declaration
            }
            String body = blockAt(src, open);
            if (body.contains("commitCraft(bot") || body.contains("commitLogsToPlanks(bot")) {
                assertTrue(body.contains("hasRecipePermission("), name + " commits a craft without the recipe gate");
                checked.add(name);
            }
        }
        // The full set as of backlog 1224: a new craft path must join it, gated.
        for (String expected : List.of("craftPlanks", "craftLadders", "craftBed", "craftWhiteWoolFromString",
                "craftWithPlanks", "craftSticks", "craftTorches", "craftDoor", "craftFence", "craftLead", "craftBundle",
                "craftLeather", "craftSaddle", "craftCarrotOnStick", "craftWarpedFungusOnStick", "craftToolWithMaterial",
                "craftShield", "craftSimple", "craftLeatherArmor", "craftMetalArmor", "craftCobbleBlock",
                "craftFishingRod", "ensurePlanksFromLogs")) {
            assertTrue(checked.contains(expected), expected + " was not found as a gated craft path: " + checked);
        }
    }

    @Test
    void aLockedToolMaterialTriesTheNextMaterial() throws IOException {
        String src = Files.readString(CRAFTING);
        String one = block(src, "private static int craftToolWithMaterial(");
        assertTrue(one.indexOf("if (!hasRecipePermission(commander, serverOf(bot), toolOutput(kind, mat))) {")
                < one.indexOf("askChestForToolMaterials("), "a locked material asks no chest");
        assertTrue(one.contains("return RECIPE_LOCKED;"));
        String loop = block(src, "private static int craftToolMaterialAware(");
        int locked = loop.indexOf("if (crafts == RECIPE_LOCKED) {");
        assertTrue(locked >= 0);
        String branch = loop.substring(locked, loop.indexOf("continue;", locked));
        assertTrue(branch.contains("lockedLine = recipeRefusalLine();"));
        assertTrue(branch.contains("shortfall.message = lockedLine;"), "a strict craft answers with the lock line");
        assertTrue(loop.contains("if (lockedLine != null && lockedMaterials == toTry.size()) {\n            say(source, lockedLine);"));
    }

    @Test
    void thePlankSubStepIsGatedOnTheCraftsCommander() throws IOException {
        String src = Files.readString(CRAFTING);
        String generic = block(src, "public static int craftGeneric(");
        assertTrue(generic.indexOf("CRAFT_COMMANDER.set(commander);") < generic.indexOf("craftGenericInternal("));
        assertTrue(generic.contains("} finally {"));
        String sub = block(src, "private static void ensurePlanksFromLogs(");
        assertTrue(sub.indexOf("hasRecipePermission(CRAFT_COMMANDER.get(), serverOf(bot), plankItem)")
                < sub.indexOf("commitLogsToPlanks(bot"));
    }

    @Test
    void everyRefusalLineComesFromTheGate() throws IOException {
        String src = Files.readString(CRAFTING);
        assertFalse(src.contains("say(source, \"I don't know how to craft that yet.\");"),
                "no call site says the generic line directly any more");
        assertEquals(19, count(src, "say(source, recipeRefusalLine());"));
        String line = block(src, "private static String recipeRefusalLine(");
        assertTrue(line.contains("return line != null ? line : \"I don't know how to craft that yet.\";"));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    /** The body of the block whose opening brace is at {@code start} (overloads share a signature prefix). */
    private static String blockAt(String source, int start) {
        int depth = 1;
        for (int end = start + 1; end < source.length(); end++) {
            char c = source.charAt(end);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(start + 1, end);
        }
        throw new AssertionError("Unbalanced block at " + start);
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
        throw new AssertionError("Unbalanced block for " + signature);
    }
}
