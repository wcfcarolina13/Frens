package net.wcfcarolina13.GameAI.services.supply;

import net.wcfcarolina13.GameAI.services.supply.CraftSupplyNeedPolicy.Line;
import net.wcfcarolina13.GameAI.services.supply.CraftSupplyNeedPolicy.Plan;
import net.wcfcarolina13.GameAI.services.supply.SupplyBundlePromptPolicy.Decision;
import net.wcfcarolina13.GameAI.services.supply.SupplyBundlePromptPolicy.Shown;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SupplyBundlePromptPolicyTest {

    private static final Shown PLANKS = new Shown(3, "Oak Planks");
    private static final Shown STICKS = new Shown(2, "Stick");
    private static final Shown IRON = new Shown(3, "Iron Ingot");

    @Test
    void decideCoversAllFourCases() {
        assertEquals(Decision.NOTHING_NEEDED, SupplyBundlePromptPolicy.decide(null));
        assertEquals(Decision.NOTHING_NEEDED, SupplyBundlePromptPolicy.decide(new Plan(List.of(), List.of(), false)));
        assertEquals(Decision.ASK_ALL, SupplyBundlePromptPolicy.decide(
                new Plan(List.of(new Line("a", 1)), List.of(), false)));
        assertEquals(Decision.ASK_PARTIAL, SupplyBundlePromptPolicy.decide(
                new Plan(List.of(new Line("a", 1)), List.of(new Line("b", 1)), false)));
        assertEquals(Decision.NOTHING_TO_ASK, SupplyBundlePromptPolicy.decide(
                new Plan(List.of(), List.of(new Line("b", 1)), false)));
    }

    @Test
    void joinUsesCommasAndAnd() {
        assertEquals("", SupplyBundlePromptPolicy.join(List.of()));
        assertEquals("", SupplyBundlePromptPolicy.join(null));
        assertEquals("3 Oak Planks", SupplyBundlePromptPolicy.join(List.of(PLANKS)));
        assertEquals("3 Oak Planks and 2 Stick", SupplyBundlePromptPolicy.join(List.of(PLANKS, STICKS)));
        assertEquals("3 Oak Planks, 2 Stick and 3 Iron Ingot",
                SupplyBundlePromptPolicy.join(List.of(PLANKS, STICKS, IRON)));
        assertEquals("1 items", SupplyBundlePromptPolicy.join(List.of(new Shown(1, " "), new Shown(0, "x"))));
    }

    @Test
    void combinedPromptNamesEveryLineAndThePurpose() {
        assertEquals("Jake asks to take 3 Oak Planks and 2 Stick from the chest at 10, 64, 10 to make a Wooden Pickaxe.",
                SupplyBundlePromptPolicy.promptText("Jake", List.of(PLANKS, STICKS), "a Wooden Pickaxe",
                        10, 64, 10, List.of()));
    }

    @Test
    void partialPromptSaysWhatIsStillMissing() {
        assertEquals("Jake asks to take 2 Stick from the chest at 1, 2, 3 to make an Iron Pickaxe."
                        + " Still missing: 3 Iron Ingot.",
                SupplyBundlePromptPolicy.promptText("Jake", List.of(STICKS), "an Iron Pickaxe", 1, 2, 3,
                        List.of(IRON)));
    }

    @Test
    void singleLineWithoutPurposeKeepsTheExistingWording() {
        assertEquals(SupplyChestRules.promptText("Jake", 3, "Oak Planks", 1, 2, 3),
                SupplyBundlePromptPolicy.promptText("Jake", List.of(PLANKS), null, 1, 2, 3, List.of()));
        assertEquals(SupplyChestRules.promptText("Jake", 3, "Oak Planks", 1, 2, 3),
                SupplyBundlePromptPolicy.promptText("Jake", List.of(PLANKS), " ", 1, 2, 3, null));
    }

    @Test
    void blankBotNameReadsYourCompanion() {
        assertEquals("Your companion asks to take 3 Oak Planks and 2 Stick from the chest at 0, 0, 0.",
                SupplyBundlePromptPolicy.promptText("", List.of(PLANKS, STICKS), null, 0, 0, 0, List.of()));
    }

    @Test
    void answersNameTheWholeSet() {
        assertEquals("Let Jake take exactly 3 Oak Planks and 2 Stick from this chest, once.",
                SupplyBundlePromptPolicy.allowOnceHover("Jake", List.of(PLANKS, STICKS)));
        assertEquals("Allowed: Jake may take 3 Oak Planks and 2 Stick from that chest, this once.",
                SupplyBundlePromptPolicy.grantedOnceReply("Jake", List.of(PLANKS, STICKS)));
        assertEquals("Refused: Jake won't ask for 3 Oak Planks and 2 Stick from that chest again for a while.",
                SupplyBundlePromptPolicy.rejectedReply("Jake", List.of(PLANKS, STICKS)));
    }

    @Test
    void nothingToAskLineNamesTheMissingItems() {
        assertEquals("I can't make an Iron Pickaxe: I still need 3 Iron Ingot, and the chest has none of it to spare.",
                SupplyBundlePromptPolicy.nothingToAskLine("an Iron Pickaxe", List.of(IRON)));
        assertEquals("I can't make that: I still need more materials, and the chest has none of it to spare.",
                SupplyBundlePromptPolicy.nothingToAskLine(null, List.of()));
    }
}
