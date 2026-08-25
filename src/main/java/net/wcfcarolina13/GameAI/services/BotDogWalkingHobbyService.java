package net.wcfcarolina13.GameAI.services;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.wcfcarolina13.ChatUtils.BotDialoguePlayer;
import net.wcfcarolina13.ChatUtils.VoiceLineCategory;
import net.wcfcarolina13.ChatUtils.BotDialogueSounds;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Walking-dogs hobby (added 2026-05-05).
 *
 * <p>When the bot is genuinely idle and walks past a sitting unnamed tamed wolf,
 * the bot toggles it to standing so the wolf will tag along while the bot does
 * other things. After a random 3–10 minute session, if the bot is back near a
 * registered base or its last-slept bed, there's a 50% chance the bot will
 * toggle the wolf back to sitting.
 *
 * <p>Composes alongside other hobbies — the bot does NOT take a TaskService
 * slot for this. The hobby only fires when the bot's idle wandering brings it
 * within direct interact reach of an eligible wolf, so the dog literally tags
 * along rather than the bot detouring to find one.
 *
 * <p>External cancellation: if anyone else (commander, another bot, another
 * player) re-sits the wolf during an active session, the session ends quietly.
 * The check is just {@code wolf.isSitting()} re-read each tick — no
 * data-tracker subscription needed.
 *
 * <p>Custom-named wolves are excluded entirely — name your wolf to opt out.
 */
public final class BotDogWalkingHobbyService {

    public static final String HOBBY_NAME = "walk_dogs";

    /** Search radius when looking for an eligible sitting wolf. */
    private static final double WOLF_PICKUP_RADIUS = 4.5D;

    /** Vanilla server-side entity-interact reach for players is ~6 blocks (squared 36).
     *  We use a tighter limit so the bot only interacts when genuinely adjacent. */
    private static final double INTERACT_REACH_SQ = 9.0D;

    /** Cooldown between pickup attempts per bot, so a bot that fails an interact
     *  doesn't hammer a wolf every tick. */
    private static final long PICKUP_RETRY_COOLDOWN_TICKS = 600L; // 30 s

    /** Session duration bounds (ticks at 20 TPS). */
    private static final long MIN_SESSION_TICKS = 60L * 20L * 3L;   // 3 min
    private static final long MAX_SESSION_TICKS = 60L * 20L * 10L;  // 10 min

    /** Distance beyond which the wolf is considered separated and the session ends. */
    private static final double SEPARATION_RADIUS_SQ = 24.0D * 24.0D;

    /** "At home" gate radius when deciding whether to sit the wolf at end of session. */
    private static final double AT_HOME_RADIUS = 16.0D;

    /** Probability of trying to sit the wolf when the session ends and the bot is at home. */
    private static final double SIT_AT_HOME_CHANCE = 0.5D;

    private static final Random RNG = new Random();

    private static final ConcurrentHashMap<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_PICKUP_ATTEMPT_TICK = new ConcurrentHashMap<>();

    private static final class Session {
        final UUID wolfId;
        final long endByTick;

        Session(UUID wolfId, long endByTick) {
            this.wolfId = wolfId;
            this.endByTick = endByTick;
        }
    }

    private BotDogWalkingHobbyService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        long tick = server.getTicks();
        if (tick % 20 != 0) return;

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) continue;
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) continue;

            UUID botId = bot.getUuid();

            // Disable mid-session if the user toggles the hobby off — drop the session
            // without trying to sit the wolf (the wolf reverts to vanilla follow / sit
            // AI; no harm done).
            if (!BotHomeService.isHobbyEnabled(bot, HOBBY_NAME)) {
                SESSIONS.remove(botId);
                continue;
            }

            Session session = SESSIONS.get(botId);
            if (session == null) {
                considerStartingSession(server, world, bot, tick);
            } else {
                advanceSession(server, world, bot, tick, session);
            }
        }

        // Garbage-collect state for bots that are no longer in the registry.
        java.util.Set<UUID> live = new java.util.HashSet<>();
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot != null) live.add(bot.getUuid());
        }
        SESSIONS.keySet().retainAll(live);
        LAST_PICKUP_ATTEMPT_TICK.keySet().retainAll(live);
    }

    private static void considerStartingSession(MinecraftServer server, ServerWorld world,
                                                ServerPlayerEntity bot, long tick) {
        UUID botId = bot.getUuid();

        // Only fire when the bot is genuinely idle — no active skill/task — and not in a vehicle.
        if (TaskService.hasActiveTask(botId)) return;
        if (bot.hasVehicle()) return;

        // Don't pick up wolves while inside a registered base — bot is parked at home.
        if (BotHomeService.findBaseNearPosition(server, world, bot.getBlockPos()).isPresent()) return;

        Long lastAttempt = LAST_PICKUP_ATTEMPT_TICK.get(botId);
        if (lastAttempt != null && tick - lastAttempt < PICKUP_RETRY_COOLDOWN_TICKS) return;

        WolfEntity wolf = findEligibleSittingWolf(world, bot);
        if (wolf == null) return;

        // Too far for a clean interact — the spec says no detour, so just wait until the
        // bot's idle wandering brings it closer.
        if (wolf.squaredDistanceTo(bot) > INTERACT_REACH_SQ) return;

        LAST_PICKUP_ATTEMPT_TICK.put(botId, tick);

        // Strip food from main hand before interacting — wolves with food in their
        // owner's hand get fed instead of toggled. Skip the wolf this tick if we
        // can't find a non-food slot anywhere in inventory.
        if (!ensureNonFoodInMainHand(bot)) return;

        // Physical interaction: face the wolf, then right-click. Same bot.interact()
        // path other entity interactions use — no direct setSitting() mutation.
        LookController.faceEntity(bot, wolf);
        boolean accepted = BotActions.interactEntity(bot, wolf, Hand.MAIN_HAND);
        if (!accepted) return;

        // Belt-and-suspenders: if we somehow still ended up feeding (e.g. an unknown
        // future food item that bypasses the FOOD-component check), don't open a
        // session — the wolf is still sitting.
        if (wolf.isSitting()) return;

        long duration = MIN_SESSION_TICKS
                + (long) (RNG.nextDouble() * (MAX_SESSION_TICKS - MIN_SESSION_TICKS));
        SESSIONS.put(botId, new Session(wolf.getUuid(), tick + duration));

        // Voiced session-start line — pick one of "Who's a good dog?" / "Going for walkies."
        playSessionStartLine(bot);
    }

    /** Pool of two session-start lines, each fired with 50/50 weight. Plays the audio
     *  via the standard BotDialoguePlayer path; the overhead-text companion line falls
     *  out of the SUBTITLE_MAP entry. */
    private static void playSessionStartLine(ServerPlayerEntity bot) {
        boolean useGoodDog = RNG.nextBoolean();
        BotDialoguePlayer.playSoundForBotDetailed(
                bot,
                useGoodDog ? BotDialogueSounds.LINE_WALK_DOGS_GOOD_DOG
                           : BotDialogueSounds.LINE_WALK_DOGS_WALKIES,
                VoiceLineCategory.SKILL_TASK);
    }

    private static void advanceSession(MinecraftServer server, ServerWorld world,
                                       ServerPlayerEntity bot, long tick, Session session) {
        UUID botId = bot.getUuid();
        Entity entity = world.getEntity(session.wolfId);

        // Wolf gone or dead — drop the session, nothing to clean up.
        if (!(entity instanceof WolfEntity wolf) || !wolf.isAlive()) {
            SESSIONS.remove(botId);
            return;
        }

        // External cancellation — anyone else (commander / another bot / another player)
        // sat the wolf back down. End quietly per spec.
        if (wolf.isSitting()) {
            SESSIONS.remove(botId);
            return;
        }

        // Wolf got separated (out of range for a long time, e.g. a chunk away).
        // End the session — wolf reverts to vanilla owner-follow AI.
        if (wolf.squaredDistanceTo(bot) > SEPARATION_RADIUS_SQ) {
            SESSIONS.remove(botId);
            return;
        }

        // Session timer expired — try to sit the wolf if at home, otherwise just end.
        if (tick >= session.endByTick) {
            if (isAtHome(server, world, bot) && wolf.squaredDistanceTo(bot) <= INTERACT_REACH_SQ) {
                if (RNG.nextDouble() < SIT_AT_HOME_CHANCE && ensureNonFoodInMainHand(bot)) {
                    LookController.faceEntity(bot, wolf);
                    BotActions.interactEntity(bot, wolf, Hand.MAIN_HAND);
                }
            }
            SESSIONS.remove(botId);
        }
    }

    /** Find a tamed, unnamed, sitting wolf within {@link #WOLF_PICKUP_RADIUS} blocks.
     *  Returns the closest match or null. */
    private static WolfEntity findEligibleSittingWolf(ServerWorld world, ServerPlayerEntity bot) {
        Box box = bot.getBoundingBox().expand(WOLF_PICKUP_RADIUS, 4.0D, WOLF_PICKUP_RADIUS);
        List<WolfEntity> wolves = world.getEntitiesByClass(
                WolfEntity.class,
                box,
                w -> w != null
                        && w.isAlive()
                        && w.isTamed()
                        && w.isSitting()
                        && !w.hasCustomName()
        );
        WolfEntity closest = null;
        double bestSq = Double.MAX_VALUE;
        for (WolfEntity w : wolves) {
            double sq = w.squaredDistanceTo(bot);
            if (sq < bestSq) {
                bestSq = sq;
                closest = w;
            }
        }
        return closest;
    }

    /** Switch the bot's main hand to a non-food item before interacting with a wolf,
     *  so the right-click toggles sit/stand instead of feeding. Wolves' feedable item
     *  list grows over Minecraft versions (rabbit stew, tropical fish, golden apples,
     *  golden carrots, and rumored upcoming items like a golden dandelion), so the
     *  defensive predicate strips anything with a FOOD component AND anything whose
     *  registry id starts with "golden_" — that catches both today's vanilla food list
     *  and the speculative "golden dandelion" / future golden-* items even before they
     *  ship with a FOOD component.
     *
     *  Strategy: if currently selected slot is risky, prefer selecting an existing
     *  non-risky hotbar slot; failing that, swap a non-risky main-inventory stack into
     *  the selected slot. Returns false only when EVERY inventory slot is risky — in
     *  that case, skip the interact entirely rather than risk a misfire. */
    private static boolean ensureNonFoodInMainHand(ServerPlayerEntity bot) {
        PlayerInventory inv = bot.getInventory();
        int currentSlot = MathHelper.clamp(inv.getSelectedSlot(), 0, 8);
        if (!isWolfFeedingRisk(inv.getStack(currentSlot))) return true;

        // Prefer an existing non-risky hotbar slot — empty stacks count as non-risky.
        for (int i = 0; i < 9; i++) {
            if (i == currentSlot) continue;
            if (!isWolfFeedingRisk(inv.getStack(i))) {
                BotActions.selectHotbarSlot(bot, i);
                return true;
            }
        }

        // No safe hotbar slot. Swap a non-risky main-inventory stack into the
        // currently selected slot so the bot ends up holding something safe.
        for (int i = 9; i < PlayerInventory.MAIN_SIZE; i++) {
            if (!isWolfFeedingRisk(inv.getStack(i))) {
                ItemStack hotbarStack = inv.getStack(currentSlot);
                ItemStack mainStack = inv.getStack(i);
                inv.setStack(currentSlot, mainStack);
                inv.setStack(i, hotbarStack);
                inv.markDirty();
                return true;
            }
        }
        return false;
    }

    /** Risky == has the FOOD data component (covers all current vanilla wolf-edible
     *  food: meat, rabbit stew, tropical fish, golden apples, etc.) OR has a registry
     *  id starting with "golden_" (defensive against upcoming items like a golden
     *  dandelion that may become wolf-feedable in a future update). */
    private static boolean isWolfFeedingRisk(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getComponents().contains(DataComponentTypes.FOOD)) return true;
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id != null && id.getPath().startsWith("golden_");
    }

    /** True if the bot is within {@link #AT_HOME_RADIUS} of either a registered base or
     *  its last-slept bed. */
    private static boolean isAtHome(MinecraftServer server, ServerWorld world, ServerPlayerEntity bot) {
        BlockPos botPos = bot.getBlockPos();
        if (BotHomeService.findBaseNearPosition(server, world, botPos).isPresent()) {
            return true;
        }
        Optional<BlockPos> lastBed = BotHomeService.getLastSleep(bot);
        if (lastBed.isPresent()) {
            return botPos.isWithinDistance(lastBed.get(), AT_HOME_RADIUS);
        }
        return false;
    }
}
