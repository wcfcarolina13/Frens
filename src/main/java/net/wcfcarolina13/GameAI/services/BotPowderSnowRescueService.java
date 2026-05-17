package net.wcfcarolina13.GameAI.services;

import com.mojang.datafixers.util.Pair;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dedicated rescue for bots stuck inside powder snow. The generic
 * {@link BotHazardService#tryEscapeHazardBlockAtFeet} velocity-kick is too weak to
 * overcome powder snow's ~15% movement multiplier, so the bot just stands there
 * slowly freezing while the kick gets eaten by drag. This service runs an explicit
 * rescue ladder instead.
 *
 * <p><b>Wiki-grounded rescue ladder</b> (Minecraft 1.21 Powder Snow mechanics):</p>
 *
 * <ol>
 *   <li><b>Equip any leather armor piece</b> available in inventory. Wearing
 *       <i>any</i> leather armor stops freezing damage <i>and reverses</i> the
 *       accumulated freezing effect, even if applied while already inside the
 *       block. Leather boots specifically also let the entity ascend the column
 *       scaffolding-style by jumping.</li>
 *   <li><b>Hold jump</b> via {@code setJumping(true)} every tick. With boots this
 *       fast-climbs the column; without, the entity slowly swims up (still better
 *       than nothing).</li>
 *   <li><b>Sustained-failure escalation</b> (after {@value #ESCALATE_AFTER_TICKS}
 *       ticks still inside): try an active block-removal in priority order —
 *       empty bucket scoop, water bucket placement (Overworld/End only; water
 *       evaporates in the Nether), then mine-out (shovel if available, bare
 *       hands otherwise — wiki notes shovels don't actually speed it up, but the
 *       user wants the bot to use one if it has one).</li>
 *   <li><b>Emergency teleport</b> (after {@value #TELEPORT_AFTER_TICKS} ticks
 *       AND visible freezing damage taken): use
 *       {@link SafePositionService#findAlternativeSafeNear} to find a nearby
 *       safe block and teleport. Last resort.</li>
 * </ol>
 *
 * <p>{@link BotHazardService#tryEscapeHazardBlockAtFeet} is wired to skip powder
 * snow so this service has exclusive ownership.</p>
 */
public final class BotPowderSnowRescueService {

    private static final Logger LOGGER = LoggerFactory.getLogger("powder-snow-rescue");

    /** After this many ticks still inside, escalate to active block removal. */
    private static final long ESCALATE_AFTER_TICKS = 60L;
    /** Throttle escalation attempts so a failing bucket/mine doesn't fire every tick. */
    private static final long ESCALATION_THROTTLE_TICKS = 20L;
    /** After this many ticks AND visible freezing damage, emergency-teleport. */
    private static final long TELEPORT_AFTER_TICKS = 200L;
    /** Per-bot cooldown between emergency teleports. */
    private static final long TELEPORT_COOLDOWN_TICKS = 200L;

    private static final ConcurrentHashMap<UUID, Long> IN_POWDER_SNOW_SINCE_TICK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_ESCALATION_TICK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_TELEPORT_TICK = new ConcurrentHashMap<>();

    private BotPowderSnowRescueService() {}

    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        long tick = server.getTicks();
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) continue;
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) continue;

            BlockPos feet = bot.getBlockPos();
            boolean inPowderSnow = world.getBlockState(feet).isOf(Blocks.POWDER_SNOW);

            UUID id = bot.getUuid();
            if (!inPowderSnow) {
                IN_POWDER_SNOW_SINCE_TICK.remove(id);
                LAST_ESCALATION_TICK.remove(id);
                continue;
            }

            long enteredAt = IN_POWDER_SNOW_SINCE_TICK.computeIfAbsent(id, k -> tick);
            handleBot(bot, world, feet, tick, tick - enteredAt);
        }
    }

    /** Returns true if the position is safe to consider as the "freezing damage might have started" gate. */
    public static boolean isCurrentlyHandlingBot(UUID botId) {
        return IN_POWDER_SNOW_SINCE_TICK.containsKey(botId);
    }

    private static void handleBot(ServerPlayerEntity bot,
                                  ServerWorld world,
                                  BlockPos feet,
                                  long tick,
                                  long sustainedTicks) {
        // Step 1: equip any leather armor we have. Cheap; just sets the slot. Stops
        // freezing damage entirely once equipped (any piece, not just boots).
        boolean newlyEquipped = equipLeatherArmorIfAvailable(bot);
        if (newlyEquipped) {
            LOGGER.info("PowderSnow: bot={} equipped leather armor while inside powder snow", bot.getName().getString());
        }

        // Step 2: hold jump. With leather boots → scaffolding-style climb. Without → slow
        // swim up. Either way, the right vertical direction.
        bot.setJumping(true);

        // Step 3: sustained-failure escalation.
        if (sustainedTicks >= ESCALATE_AFTER_TICKS) {
            UUID id = bot.getUuid();
            long lastEsc = LAST_ESCALATION_TICK.getOrDefault(id, -1L);
            if (lastEsc < 0 || tick - lastEsc >= ESCALATION_THROTTLE_TICKS) {
                LAST_ESCALATION_TICK.put(id, tick);
                if (tryEmptyBucketScoop(bot, world, feet)
                        || tryWaterBucketPlace(bot, world, feet)
                        || tryMinePowderSnow(bot, feet)) {
                    return;
                }
            }
        }

        // Step 4: emergency teleport. Only fires after the bot is visibly freezing
        // (vanilla TicksFrozen reaches 140 → damage begins) and we've already tried
        // the cheaper steps for the full sustained window.
        if (sustainedTicks >= TELEPORT_AFTER_TICKS && bot.getFrozenTicks() >= 140) {
            UUID id = bot.getUuid();
            long lastTp = LAST_TELEPORT_TICK.getOrDefault(id, -1L);
            if (lastTp < 0 || tick - lastTp >= TELEPORT_COOLDOWN_TICKS) {
                LAST_TELEPORT_TICK.put(id, tick);
                tryEmergencyTeleport(bot, world, feet);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 1: equip leather armor
    // ─────────────────────────────────────────────────────────────────────────

    private static boolean equipLeatherArmorIfAvailable(ServerPlayerEntity bot) {
        boolean anyEquipped = false;
        // Boots first — they unlock scaffolding-style climbing, which is the cheapest
        // physical escape from the column.
        for (EquipmentSlot slot : List.of(EquipmentSlot.FEET, EquipmentSlot.HEAD,
                EquipmentSlot.CHEST, EquipmentSlot.LEGS)) {
            if (!bot.getEquippedStack(slot).isEmpty()) continue;
            int invSlot = findLeatherArmorForSlot(bot, slot);
            if (invSlot < 0) continue;

            ItemStack stack = bot.getInventory().getStack(invSlot);
            bot.getInventory().setStack(invSlot, ItemStack.EMPTY);
            bot.equipStack(slot, stack);
            bot.getInventory().markDirty();
            broadcastEquipment(bot, slot, stack);
            anyEquipped = true;
        }
        return anyEquipped;
    }

    private static int findLeatherArmorForSlot(ServerPlayerEntity bot, EquipmentSlot slot) {
        // 1.21.x removed the ArmorItem subclass — armor is component-based now. Match
        // vanilla leather pieces by identity. Mod leather variants aren't recognized,
        // but those wouldn't have the leather-armor freezing-immunity behavior anyway
        // unless they explicitly opt into the data — out of scope for this rescue.
        ItemStack target = switch (slot) {
            case HEAD  -> stackFor(Items.LEATHER_HELMET);
            case CHEST -> stackFor(Items.LEATHER_CHESTPLATE);
            case LEGS  -> stackFor(Items.LEATHER_LEGGINGS);
            case FEET  -> stackFor(Items.LEATHER_BOOTS);
            default -> ItemStack.EMPTY;
        };
        if (target.isEmpty()) return -1;
        PlayerInventory inv = bot.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.isOf(target.getItem())) return i;
        }
        return -1;
    }

    private static ItemStack stackFor(net.minecraft.item.Item item) {
        return new ItemStack(item);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3: active block removal
    // ─────────────────────────────────────────────────────────────────────────

    private static boolean tryEmptyBucketScoop(ServerPlayerEntity bot, ServerWorld world, BlockPos feet) {
        int slot = findInventorySlot(bot, Items.BUCKET);
        if (slot < 0) return false;
        if (!selectInventorySlot(bot, slot)) return false;
        return useHandOnBlock(bot, world, feet, "empty-bucket-scoop");
    }

    private static boolean tryWaterBucketPlace(ServerPlayerEntity bot, ServerWorld world, BlockPos feet) {
        // Water evaporates in the Nether — skip that dimension.
        if (world.getRegistryKey() == World.NETHER) return false;
        int slot = findInventorySlot(bot, Items.WATER_BUCKET);
        if (slot < 0) return false;
        if (!selectInventorySlot(bot, slot)) return false;
        return useHandOnBlock(bot, world, feet, "water-bucket-place");
    }

    private static boolean tryMinePowderSnow(ServerPlayerEntity bot, BlockPos feet) {
        // Per wiki, shovel doesn't speed up breaking — but the user wants the bot to
        // use one if it has one (intuitive player behaviour). Falls through to bare
        // hands if no shovel.
        BotActions.selectHarvestToolOrHands(bot, "shovel");
        LOGGER.info("PowderSnow: bot={} mining feet block at {} (tool={})",
                bot.getName().getString(), feet.toShortString(), bot.getMainHandStack().getItem());
        MiningTool.mineBlock(bot, feet);
        return true;
    }

    private static boolean useHandOnBlock(ServerPlayerEntity bot, ServerWorld world, BlockPos pos, String reason) {
        ItemStack handStack = bot.getMainHandStack();
        BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(pos),
                Direction.UP,
                pos,
                false);
        ActionResult result = handStack.useOnBlock(new ItemUsageContext(bot, Hand.MAIN_HAND, hit));
        LOGGER.info("PowderSnow: bot={} {} at {} result={} hand-now={}",
                bot.getName().getString(), reason, pos.toShortString(), result, bot.getMainHandStack().getItem());
        // ActionResult success codes vary across versions; treat anything non-FAIL as a try-again-next-tick.
        return result.isAccepted();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 4: emergency teleport
    // ─────────────────────────────────────────────────────────────────────────

    private static void tryEmergencyTeleport(ServerPlayerEntity bot, ServerWorld world, BlockPos feet) {
        BlockPos safe = SafePositionService.findAlternativeSafeNear(world, feet, 8);
        if (safe == null) {
            LOGGER.warn("PowderSnow: bot={} emergency teleport could not find safe block within 8 of {}",
                    bot.getName().getString(), feet.toShortString());
            return;
        }
        LOGGER.info("PowderSnow: bot={} emergency-teleport from {} to {} (frozenTicks={})",
                bot.getName().getString(), feet.toShortString(), safe.toShortString(), bot.getFrozenTicks());
        SafePositionService.snapTo(bot, safe);
        // Reset freezing visual so the bot doesn't keep shivering for several seconds after.
        bot.setFrozenTicks(0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static int findInventorySlot(ServerPlayerEntity bot, net.minecraft.item.Item item) {
        PlayerInventory inv = bot.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    /**
     * Bring a target inventory slot to the main-hand selected slot (0–8). If it's
     * already in the hotbar, just change selected. If in the main inventory, swap
     * with the currently-selected hotbar stack so the target ends up in hand.
     */
    private static boolean selectInventorySlot(ServerPlayerEntity bot, int slot) {
        PlayerInventory inv = bot.getInventory();
        if (slot < 0 || slot >= inv.size()) return false;
        if (slot < PlayerInventory.getHotbarSize()) {
            inv.setSelectedSlot(slot);
            return true;
        }
        // Swap into the currently-selected hotbar slot.
        int hotbar = inv.getSelectedSlot();
        ItemStack target = inv.getStack(slot);
        ItemStack current = inv.getStack(hotbar);
        inv.setStack(hotbar, target);
        inv.setStack(slot, current);
        inv.markDirty();
        return true;
    }

    private static void broadcastEquipment(ServerPlayerEntity bot, EquipmentSlot slot, ItemStack stack) {
        List<Pair<EquipmentSlot, ItemStack>> updates = List.of(new Pair<>(slot, stack));
        bot.getEntityWorld().getPlayers().forEach(player -> {
            if (player instanceof ServerPlayerEntity spe) {
                spe.networkHandler.sendPacket(new EntityEquipmentUpdateS2CPacket(bot.getId(), updates));
            }
        });
    }

    /** Hook for testing / debug commands. */
    public static void resetState(UUID botId) {
        if (botId == null) return;
        IN_POWDER_SNOW_SINCE_TICK.remove(botId);
        LAST_ESCALATION_TICK.remove(botId);
        LAST_TELEPORT_TICK.remove(botId);
    }
}
