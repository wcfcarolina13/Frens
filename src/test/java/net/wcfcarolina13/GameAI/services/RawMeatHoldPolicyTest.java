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
        assertTrue(RawMeatHoldPolicy.shouldHold(14, 1.0F, () -> true));
        assertTrue(RawMeatHoldPolicy.shouldHold(7, 0.5F, () -> true));
    }

    @Test
    void eatsRawAtSixFoodOrBelowHalfHealthOrWithNoCook() {
        assertFalse(RawMeatHoldPolicy.shouldHold(6, 1.0F, () -> true), "can't sprint at 6");
        assertFalse(RawMeatHoldPolicy.shouldHold(14, 0.45F, () -> true), "hurt bot eats what it has");
        assertFalse(RawMeatHoldPolicy.shouldHold(14, Float.NaN, () -> true));
        assertFalse(RawMeatHoldPolicy.shouldHold(14, 1.0F, () -> false), "no furnace, fuel or idle time");
        assertFalse(RawMeatHoldPolicy.shouldHold(14, 1.0F, null));
    }

    @Test
    void theFurnaceCheckRunsOnlyWhenTheCheapGatesPass() {
        AtomicInteger calls = new AtomicInteger();
        RawMeatHoldPolicy.shouldHold(5, 1.0F, () -> calls.incrementAndGet() > 0);
        RawMeatHoldPolicy.shouldHold(12, 0.2F, () -> calls.incrementAndGet() > 0);
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
