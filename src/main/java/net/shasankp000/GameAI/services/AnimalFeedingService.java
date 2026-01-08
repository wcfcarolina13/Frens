package net.shasankp000.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.shasankp000.GameAI.BotActions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class AnimalFeedingService {
    private static final double FEED_RANGE_SQ = 4.5D * 4.5D;

    private static final List<Item> HORSE_FOODS = List.of(
            Items.WHEAT,
            Items.SUGAR,
            Items.APPLE,
            Items.HAY_BLOCK
    );
    private static final List<Item> PIG_FOODS = List.of(
            Items.CARROT,
            Items.POTATO,
            Items.BEETROOT
    );
    private static final List<Item> STRIDER_FOODS = List.of(
            Items.WARPED_FUNGUS
    );

    private AnimalFeedingService() {}

    public static boolean feedIfNeeded(ServerPlayerEntity bot, LivingEntity animal) {
        if (bot == null || animal == null || animal.isRemoved()) {
            return false;
        }
        if (!isLowHealth(animal)) {
            return false;
        }
        List<Item> foods = allowedFoods(animal.getType());
        if (foods.isEmpty()) {
            return false;
        }
        int attempts = 0;
        boolean fed = false;
        while (isLowHealth(animal) && attempts < 6) {
            Item food = findFoodItem(bot, foods);
            if (food == null) {
                break;
            }
            if (!BotActions.ensureHotbarItem(bot, food)) {
                break;
            }
            if (!BotActions.interactEntity(bot, animal, Hand.MAIN_HAND)) {
                break;
            }
            fed = true;
            attempts++;
            try {
                Thread.sleep(160L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return fed;
    }

    public static List<LivingEntity> findLowHealthAnimals(ServerWorld world, BlockPos origin, double radius) {
        if (world == null || origin == null) {
            return List.of();
        }
        double r = Math.max(4.0D, radius);
        Box box = new Box(origin).expand(r);
        List<LivingEntity> matches = new ArrayList<>();
        for (Entity entity : world.getOtherEntities(null, box)) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (!isFeedableType(living.getType())) {
                continue;
            }
            if (!isLowHealth(living)) {
                continue;
            }
            matches.add(living);
        }
        matches.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5)));
        return matches;
    }

    public static boolean isFeedableType(EntityType<?> type) {
        return !allowedFoods(type).isEmpty();
    }

    public static boolean isLowHealth(LivingEntity animal) {
        if (animal == null) {
            return false;
        }
        float max = animal.getMaxHealth();
        return animal.getHealth() + 0.25F < max;
    }

    public static boolean isWithinFeedRange(ServerPlayerEntity bot, LivingEntity animal) {
        if (bot == null || animal == null) {
            return false;
        }
        return bot.squaredDistanceTo(animal) <= FEED_RANGE_SQ;
    }

    public static boolean hasFoodFor(ServerPlayerEntity bot, LivingEntity animal) {
        if (bot == null || animal == null) {
            return false;
        }
        List<Item> foods = allowedFoods(animal.getType());
        return findFoodItem(bot, foods) != null;
    }

    private static List<Item> allowedFoods(EntityType<?> type) {
        if (type == null) {
            return List.of();
        }
        if (isHorseLike(type)) {
            return HORSE_FOODS;
        }
        if (type == EntityType.PIG) {
            return PIG_FOODS;
        }
        if (type == EntityType.STRIDER) {
            return STRIDER_FOODS;
        }
        return List.of();
    }

    private static boolean isHorseLike(EntityType<?> type) {
        return type == EntityType.HORSE
                || type == EntityType.DONKEY
                || type == EntityType.MULE
                || type == EntityType.SKELETON_HORSE
                || type == EntityType.ZOMBIE_HORSE
                || type == EntityType.LLAMA
                || type == EntityType.TRADER_LLAMA
                || type == EntityType.CAMEL;
    }

    private static Item findFoodItem(ServerPlayerEntity bot, List<Item> foods) {
        if (bot == null || foods == null || foods.isEmpty()) {
            return null;
        }
        for (Item item : foods) {
            if (item == null) {
                continue;
            }
            for (int i = 0; i < bot.getInventory().size(); i++) {
                ItemStack stack = bot.getInventory().getStack(i);
                if (!stack.isEmpty() && stack.isOf(item)) {
                    return item;
                }
            }
        }
        return null;
    }
}
