package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
    void everyRefusalLineComesFromTheGate() throws IOException {
        String src = Files.readString(CRAFTING);
        assertFalse(src.contains("say(source, \"I don't know how to craft that yet.\");"),
                "no call site says the generic line directly any more");
        assertEquals(13, count(src, "say(source, recipeRefusalLine());"));
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
