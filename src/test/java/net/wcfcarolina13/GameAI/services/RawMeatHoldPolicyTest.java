package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawMeatHoldPolicyTest {

    @Test
    void holdsAboveSixFoodAtHalfHealthWhenACookIsPossible() {
        assertTrue(RawMeatHoldPolicy.shouldHold(14, 1.0F, 0L, () -> true));
        assertTrue(RawMeatHoldPolicy.shouldHold(7, 0.5F, 0L, () -> true));
    }

    @Test
    void eatsRawAtSixFoodOrBelowHalfHealthOrWithNoCook() {
        assertFalse(RawMeatHoldPolicy.shouldHold(6, 1.0F, 0L, () -> true), "can't sprint at 6");
        assertFalse(RawMeatHoldPolicy.shouldHold(14, 0.45F, 0L, () -> true), "hurt bot eats what it has");
        assertFalse(RawMeatHoldPolicy.shouldHold(14, Float.NaN, 0L, () -> true));
        assertFalse(RawMeatHoldPolicy.shouldHold(14, 1.0F, 0L, () -> false), "no furnace, fuel or idle time");
        assertFalse(RawMeatHoldPolicy.shouldHold(14, 1.0F, 0L, null));
    }

    @Test
    void aHurtBotAboveTheCookLevelEatsButHoldsAtIt() {
        assertFalse(RawMeatHoldPolicy.shouldHold(14, 0.8F, 0L, () -> true), "auto-cook can't start above 10");
        assertTrue(RawMeatHoldPolicy.shouldHold(10, 0.8F, 0L, () -> true), "the cook is due now");
    }

    @Test
    void theHoldAtTheCookLevelEndsAfterAboutADozenCookAttempts() {
        long cap = RawMeatHoldPolicy.MAX_HOLD_AT_COOK_LEVEL_MS;
        assertTrue(RawMeatHoldPolicy.shouldHold(10, 1.0F, cap, () -> true));
        assertFalse(RawMeatHoldPolicy.shouldHold(10, 1.0F, cap + 1, () -> true), "a cook that keeps loading nothing");
        assertTrue(RawMeatHoldPolicy.shouldHold(12, 1.0F, cap + 1, () -> true), "the cap counts only at the cook level");
    }

    @Test
    void theCookLevelMatchesAutoCook() throws IOException {
        String cook = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/BotAutoCookingService.java"));
        assertTrue(cook.contains("COOK_HUNGER_THRESHOLD = " + RawMeatHoldPolicy.COOK_FOOD_LEVEL + ";"));
    }

    @Test
    void theFurnaceCheckRunsOnlyWhenTheCheapGatesPass() {
        AtomicInteger calls = new AtomicInteger();
        RawMeatHoldPolicy.shouldHold(5, 1.0F, 0L, () -> calls.incrementAndGet() > 0);
        RawMeatHoldPolicy.shouldHold(12, 0.2F, 0L, () -> calls.incrementAndGet() > 0);
        RawMeatHoldPolicy.shouldHold(9, 1.0F, RawMeatHoldPolicy.MAX_HOLD_AT_COOK_LEVEL_MS + 1, () -> calls.incrementAndGet() > 0);
        assertEquals(0, calls.get());
    }

    @Test
    void autoEatHoldsRawMeatBeforeEatingAndFoodSeekersRespectIt() throws IOException {
        String healing = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/HealingService.java"));
        int autoEat = healing.indexOf("public static boolean autoEat(ServerPlayerEntity bot, List<Entity> hostileEntities)");
        assertTrue(autoEat > 0);
        String body = healing.substring(autoEat, healing.indexOf("public static boolean isHoldingRawMeatForCook(", autoEat));
        int hold = body.indexOf("RawMeatHoldPolicy.shouldHold(");
        int eat = body.lastIndexOf("return consumeFood(bot, foodSlot.getAsInt());");
        assertTrue(hold > 0 && eat > hold, "the hold is decided before the bot eats");
        assertTrue(body.contains("!emergency && isRawMeat(inv.getStack(foodSlot.getAsInt()))"),
                "only raw meat is held, never in an emergency");

        String aid = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/BotMutualAidService.java"));
        int urgent = aid.indexOf("public static boolean tryUrgentFoodRecovery(");
        String urgentBody = aid.substring(urgent, aid.indexOf("trySeekFoodFromNearbyBot(bot, world);", urgent));
        int held = urgentBody.indexOf("HealingService.isHoldingRawMeatForCook(bot)");
        int chest = urgentBody.indexOf("tryImmediateChestFoodRecovery(bot, world)");
        assertTrue(held > 0 && chest > held, "a bot holding raw meat doesn't go looking for other food");
    }
}
