package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-reading checks for metal armour crafting (backlog 1217) in {@code CraftingHelper}: the
 * dispatcher routes armour names through {@link ArmorCraftPolicy} before the name switch, and
 * {@code craftMetalArmor} asks the chest once, then needs a table, then pulls, then commits.
 * Server types keep this out of a unit test.
 */
class MetalArmorCraftWiringTest {

    private static final Path CRAFTING =
            Path.of("src/main/java/net/wcfcarolina13/GameAI/services/CraftingHelper.java");

    @Test
    void theDispatcherRoutesArmourBeforeTheNameSwitch() throws IOException {
        String body = block(Files.readString(CRAFTING), "private static int craftGenericInternal(");
        int netherite = body.indexOf("if (ArmorCraftPolicy.isNetheriteArmor(normalized)) {");
        int piece = body.indexOf("ArmorCraftPolicy.Piece armorPiece = ArmorCraftPolicy.piece(normalized);");
        int dispatch = body.indexOf("return craftMetalArmor(bot, source, commander, amount,");
        int sw = body.indexOf("return switch (normalized) {");
        assertTrue(netherite >= 0 && piece > netherite && dispatch > piece && sw > dispatch,
                "netherite refusal, then the armour lookup, then the switch");
        assertTrue(body.contains("case \"leather_helmet\" -> craftLeatherArmor("), "leather keeps its path");
    }

    @Test
    void theArmourCraftAsksThenNeedsATableThenPullsThenCommits() throws IOException {
        String body = block(Files.readString(CRAFTING), "private static int craftMetalArmor(");
        int ask = body.indexOf("askChestForKnownCraft(bot, source, output, amount, s -> s.isOf(material), true)");
        int station = body.indexOf("if (!ensureCraftingStation(bot, source)) {");
        int pull = body.indexOf("ensureItemAvailable(bot, source, material, per * amount);");
        int space = body.indexOf("ensureInventorySpaceForOutput(bot, source, output, crafts, 0, 0, reserveItems)");
        int commit = body.indexOf("commitCraft(bot, output, crafts,");
        int history = body.indexOf("recordCraftHistory(commander, output);");
        assertTrue(ask >= 0 && station > ask && pull > station && space > pull && commit > space && history > commit);
        assertTrue(body.contains("case STOP_SILENT, STOP_WITH_LINE -> {\n                return 0;"));
        assertTrue(body.contains("() -> consumeItemOnServer(bot, material, crafts * per)"));
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
