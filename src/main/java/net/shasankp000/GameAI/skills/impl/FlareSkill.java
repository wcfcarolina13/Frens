package net.shasankp000.GameAI.skills.impl;

import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.services.SafePositionService;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Emergency flare skill: fires a firework rocket straight up to signal location.
 * Used when bot is stuck too far from base and needs to alert the player.
 * 
 * Prerequisites:
 * - Bot must have firework rockets in inventory
 * - Bot must be able to see open sky (will attempt to navigate there if underground)
 * 
 * Behavior:
 * - Announces "Sending a flare!"
 * - Fires one firework rocket straight up
 */
public final class FlareSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-flare");
    private static final int MAX_OPEN_SKY_SEARCH_RADIUS = 24;

    @Override
    public String name() {
        return "flare";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = Objects.requireNonNull(source.getPlayer(), "player");
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return SkillExecutionResult.failure("No world available for flare.");
        }

        // Check if bot has fireworks
        int fireworkSlot = findFireworkSlot(bot);
        if (fireworkSlot < 0) {
            LOGGER.info("Flare skill: no fireworks in inventory");
            ChatUtils.sendChatMessages(source, "I don't have any fireworks to send a flare.");
            return SkillExecutionResult.failure("No fireworks available.");
        }

        BlockPos botPos = bot.getBlockPos();

        // Check if we can see open sky
        if (!world.isSkyVisible(botPos.up(2))) {
            LOGGER.info("Flare skill: no open sky at current position, searching nearby...");
            
            // Try to find a nearby position with open sky
            BlockPos openSkyPos = findNearbyOpenSky(world, botPos);
            if (openSkyPos == null) {
                LOGGER.warn("Flare skill: couldn't find open sky nearby");
                ChatUtils.sendChatMessages(source, "I can't find open sky to send a flare.");
                return SkillExecutionResult.failure("No open sky accessible.");
            }

            // Navigate to the open sky position using nudge
            LOGGER.info("Flare skill: navigating to open sky at {}", openSkyPos.toShortString());
            ChatUtils.sendSystemMessage(source, "Moving to open sky to send flare...");
            
            // Use nudge movement to get to the open sky position
            boolean reached = MovementService.nudgeTowardUntilClose(bot, openSkyPos, 4.0D, 10000L, 0.18, "flare-opensky");
            
            if (!reached) {
                LOGGER.warn("Flare skill: couldn't reach open sky position");
                ChatUtils.sendChatMessages(source, "I can't reach open sky to send a flare.");
                return SkillExecutionResult.failure("Couldn't reach open sky.");
            }
        }

        if (SkillManager.shouldAbortSkill(bot)) {
            return SkillExecutionResult.failure("Flare aborted.");
        }

        // Announce and fire the flare
        ChatUtils.sendChatMessages(source, "Sending a flare!");
        LOGGER.info("Flare skill: firing firework at {}", bot.getBlockPos().toShortString());

        // Consume one firework
        ItemStack fireworkStack = bot.getInventory().getStack(fireworkSlot);
        ItemStack toUse = fireworkStack.split(1);
        if (fireworkStack.isEmpty()) {
            bot.getInventory().setStack(fireworkSlot, ItemStack.EMPTY);
        }

        // Spawn and launch the firework straight up
        Vec3d launchPos = Vec3d.ofCenter(bot.getBlockPos()).add(0, 1.5, 0);
        FireworkRocketEntity firework = new FireworkRocketEntity(
                world,
                launchPos.x, launchPos.y, launchPos.z,
                toUse
        );
        // Set upward velocity
        firework.setVelocity(0, 0.5, 0);
        world.spawnEntity(firework);

        LOGGER.info("Flare skill: firework launched successfully");
        return SkillExecutionResult.success("Flare sent!");
    }

    /**
     * Finds a hotbar or inventory slot containing firework rockets.
     * Returns -1 if none found.
     */
    private int findFireworkSlot(ServerPlayerEntity bot) {
        // Check hotbar first (slots 0-8)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (isFirework(stack)) {
                return i;
            }
        }
        // Check main inventory (slots 9-35)
        for (int i = 9; i < 36; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (isFirework(stack)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isFirework(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        return item == Items.FIREWORK_ROCKET;
    }

    /**
     * Searches for a nearby position with open sky visible.
     * Uses expanding rings from the center position.
     */
    private BlockPos findNearbyOpenSky(ServerWorld world, BlockPos center) {
        // First check immediate surroundings
        for (Direction dir : Direction.Type.HORIZONTAL) {
            for (int dist = 1; dist <= MAX_OPEN_SKY_SEARCH_RADIUS; dist++) {
                BlockPos check = center.offset(dir, dist);
                BlockPos safe = SafePositionService.findSafeNear(world, check, 3);
                if (safe != null && world.isSkyVisible(safe.up(2))) {
                    return safe;
                }
            }
        }

        // Spiral outward search
        for (int radius = 2; radius <= MAX_OPEN_SKY_SEARCH_RADIUS; radius += 2) {
            for (int dx = -radius; dx <= radius; dx += 2) {
                for (int dz = -radius; dz <= radius; dz += 2) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue; // Only check perimeter
                    }
                    BlockPos check = center.add(dx, 0, dz);
                    BlockPos safe = SafePositionService.findSafeNear(world, check, 3);
                    if (safe != null && world.isSkyVisible(safe.up(2))) {
                        return safe;
                    }
                }
            }
        }

        return null;
    }
}
