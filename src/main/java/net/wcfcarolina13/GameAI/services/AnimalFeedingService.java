package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.wcfcarolina13.GameAI.BotActions;

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
    private static final List<Item> WOLF_FOODS = List.of(
            Items.BEEF,
            Items.COOKED_BEEF,
            Items.CHICKEN,
            Items.COOKED_CHICKEN,
            Items.PORKCHOP,
            Items.COOKED_PORKCHOP,
            Items.MUTTON,
            Items.COOKED_MUTTON,
            Items.RABBIT,
            Items.COOKED_RABBIT,
            Items.ROTTEN_FLESH
    );

    private AnimalFeedingService() {}

    public static boolean feedIfNeeded(ServerPlayerEntity bot, LivingEntity animal) {
        return feedIfPossible(bot, animal, true);
    }

    public static boolean feedIfPossible(ServerPlayerEntity bot, LivingEntity animal, boolean requireLowHealth) {
        if (bot == null || animal == null || animal.isRemoved()) {
            return false;
        }
        if (requireLowHealth && !isLowHealth(animal)) {
            return false;
        }
        List<Item> foods = allowedFoods(animal.getType());
        if (foods.isEmpty()) {
            return false;
        }
        int attempts = 0;
        boolean fed = false;
        int maxAttempts = requireLowHealth ? 6 : 2;
        while (attempts < maxAttempts) {
            if (requireLowHealth && !isLowHealth(animal)) {
                break;
            }
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
            if (!requireLowHealth) {
                break;
            }
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

    /**
     * Ambient hobby target scan:
     * low-health animals are always valid, and tamed/fenced animals are allowed if food is available.
     */
    public static List<LivingEntity> findAmbientFeedTargets(ServerWorld world, ServerPlayerEntity bot, double radius) {
        if (world == null || bot == null) {
            return List.of();
        }
        BlockPos origin = bot.getBlockPos();
        double r = Math.max(6.0D, radius);
        Box box = new Box(origin).expand(r);
        List<LivingEntity> matches = new ArrayList<>();
        for (Entity entity : world.getOtherEntities(null, box)) {
            if (!(entity instanceof LivingEntity living) || living.isRemoved() || !living.isAlive()) {
                continue;
            }
            if (!isFeedableType(living.getType())) {
                continue;
            }
            if (!hasFoodFor(bot, living)) {
                continue;
            }
            boolean lowHealth = isLowHealth(living);
            boolean tamed = isTamedCompanion(living);
            boolean nearFence = isNearFence(world, living.getBlockPos(), 2);
            if (!lowHealth && !tamed && !nearFence) {
                continue;
            }
            matches.add(living);
        }
        matches.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(origin.getX() + 0.5D, origin.getY() + 0.5D, origin.getZ() + 0.5D)));
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
        if (type == EntityType.WOLF) {
            return WOLF_FOODS;
        }
        return List.of();
    }

    private static boolean isTamedCompanion(LivingEntity animal) {
        if (animal == null) {
            return false;
        }
        if (animal instanceof TameableEntity tameable) {
            return tameable.isTamed();
        }
        if (animal instanceof AbstractHorseEntity horse) {
            return horse.isTame();
        }
        return false;
    }

    private static boolean isNearFence(ServerWorld world, BlockPos origin, int radius) {
        if (world == null || origin == null) {
            return false;
        }
        int r = Math.max(1, radius);
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -1, -r), origin.add(r, 1, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (world.getBlockState(pos).isIn(BlockTags.FENCES)) {
                return true;
            }
        }
        return false;
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
