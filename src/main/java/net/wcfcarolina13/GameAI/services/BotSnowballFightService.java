package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bots that have been idle (or just following) for a sustained window with snow available
 * may initiate a snowball fight with the nearest player. The fight only escalates if the
 * player reciprocates by throwing a snowball back. When the bot runs out of ammo it yields
 * and any further attack causes it to flee like a peaceful mob.
 *
 * <p>State machine: IDLE → PROBING (initiation throw) → ACTIVE (sustained fight) → YIELDED → IDLE.
 * The PROBING window is 30s; if not reciprocated, the bot drops back to IDLE with a 5min
 * cooldown so it doesn't pester the player.</p>
 */
public final class BotSnowballFightService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-snowball-fight");

    enum Phase { IDLE, PROBING, ACTIVE, YIELDED }

    /** Sustained eligibility before the bot will initiate a probe (60s). */
    private static final int IDLE_GATE_TICKS = 1200;
    /** How long the bot waits for the player to reciprocate the probe (30s). */
    private static final int RECIPROCATE_WINDOW_TICKS = 600;
    /** Min/max throw cadence during ACTIVE (1.5s–2.5s). */
    private static final int ACTIVE_THROW_INTERVAL_MIN = 30;
    private static final int ACTIVE_THROW_INTERVAL_MAX = 50;
    /** Cooldown after a fight ends before the bot can initiate another (5min). */
    private static final int FIGHT_COOLDOWN_TICKS = 6000;
    /** YIELDED grace period: bot stays in yield state long enough to be hit and flee (3s). */
    private static final int YIELDED_GRACE_TICKS = 60;
    /** Sustained-pelting yield threshold: this many incoming commander snowball hits
     *  within {@link #OVERWHELMED_WINDOW_TICKS} causes the bot to yield gracefully
     *  even with ammo remaining. Counters the "I threw 50 snowballs at it and it
     *  never gave up" user report. */
    private static final int OVERWHELMED_HIT_THRESHOLD = 8;
    private static final long OVERWHELMED_WINDOW_TICKS = 200L;

    private static final int RECIPROCATE_SCAN_RADIUS = 16;
    private static final int SNOW_RADIUS = 6;
    private static final int HOSTILE_DANGER_RADIUS = 16;
    private static final double COMMANDER_RANGE = 16.0;
    private static final double DISENGAGE_RANGE = 24.0;
    /** "Not dire" — bot/commander above 40% health to play. Below = leave them alone. */
    private static final float MIN_HEALTH_FRACTION = 0.40f;
    /** Hunger floor — vanilla loses sprint/regen below 6, that's "dire". */
    private static final int MIN_FOOD_LEVEL = 6;
    private static final int MOB_SPAWN_LIGHT_THRESHOLD = 7;

    private static final int DIALOGUE_DURATION_MS = 3500;
    private static final double DIALOGUE_RANGE = 32.0;

    /** Said with the very first probing throw — bot testing the waters. */
    private static final String[] PROBE_LINES = {
            "Catch this!",
            "Heads up!",
            "Hey — incoming!",
            "Snowball fight!",
            "Bet you can't dodge this one!",
            "Snowball fight?"
    };
    /** Said the moment the player throws a snowball back — "oh, it's on now". */
    private static final String[] ESCALATE_LINES = {
            "Oh, it's ON now!",
            "Hah! You're in for it!",
            "That's how it is, huh? Bring it!",
            "Now we're playing!",
            "Eat snow!"
    };
    /** Random taunts during ACTIVE — fired at low probability between throws. */
    private static final String[] TAUNT_LINES = {
            "Got you!",
            "Bullseye!",
            "Take that!",
            "Can't catch me!",
            "Is that all you got?",
            "Hold still!",
            "Almost had me!",
            "Gotcha!"
    };
    /** Said when a commander snowball flies near the bot during ACTIVE — "whoa, close one". */
    private static final String[] DODGE_LINES = {
            "Whoa, close one!",
            "Missed me!",
            "Nice try!",
            "Hah, dodged!"
    };
    /** Said when a probe goes unanswered — bot disappointed/jokey. */
    private static final String[] TIMEOUT_LINES = {
            "Tough crowd...",
            "Guess you don't wanna play.",
            "Suit yourself.",
            "No fun, huh?"
    };
    /** Said the instant the bot runs out of snowballs in ACTIVE. */
    private static final String[] YIELD_LINES = {
            "Out of ammo — I yield!",
            "I'm out, I'm out, truce!",
            "No more snowballs — you win!",
            "Mercy! I surrender!",
            "Okay okay, you got me!"
    };

    private static final ConcurrentHashMap<UUID, State> STATES = new ConcurrentHashMap<>();

    private static final class State {
        Phase phase = Phase.IDLE;
        long eligibleSinceTick = -1;
        long phaseEnteredTick = 0;
        long lastThrowTick = -1000;
        int nextThrowInterval = ACTIVE_THROW_INTERVAL_MIN;
        long fightCooldownUntilTick = 0;
        long yieldedClearAtTick = 0;
        long lastDodgeLineTick = -1000;
        UUID partnerUuid;
        /** Sliding window of recent incoming-commander-snowball hit timestamps,
         *  pruned to {@link #OVERWHELMED_WINDOW_TICKS}. Used for the
         *  overwhelmed-yield trigger. */
        final Deque<Long> snowballHitTicks = new ArrayDeque<>();
    }

    private BotSnowballFightService() {}

    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        long tick = server.getTicks();
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved()) continue;
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) continue;
            tickBot(bot, world, server, tick);
        }
    }

    private static void tickBot(ServerPlayerEntity bot, ServerWorld world,
                                MinecraftServer server, long tick) {
        State s = STATES.computeIfAbsent(bot.getUuid(), id -> new State());
        ServerPlayerEntity commander = nearestNonBotPlayer(bot, server);

        // /bot stop hook: if the user just issued /bot stop, drop out of any
        // active fight immediately. Without this gate, the bot ignored stop
        // commands during a snowball fight (user-reported).
        if (s.phase != Phase.IDLE && BotEventHandler.isInStopCommandGrace(bot.getUuid())) {
            LOGGER.info("Bot {} snowball-fight ended by /bot stop", bot.getName().getString());
            sayLine(bot, YIELD_LINES, "snowball-fight-stop-cmd");
            endFight(s, tick);
            return;
        }

        switch (s.phase) {
            case IDLE -> tickIdle(bot, world, server, s, commander, tick);
            case PROBING -> tickProbing(bot, world, s, commander, tick);
            case ACTIVE -> tickActive(bot, world, s, commander, tick);
            case YIELDED -> tickYielded(s, tick);
        }
    }

    /**
     * Damage hook entry. Called from the {@code ALLOW_DAMAGE} listener in {@code Frens.java}
     * whenever a registered bot takes damage. Branches on phase:
     * <ul>
     *   <li>PROBING + commander snowball hit → escalate to ACTIVE</li>
     *   <li>PROBING + real attack → abort with cooldown (no yield)</li>
     *   <li>ACTIVE + non-snowball hostile hit → flee while keeping the throw cadence</li>
     *   <li>YIELDED + any attacker → flee like a peaceful mob</li>
     * </ul>
     */
    public static void notifyBotDamaged(ServerPlayerEntity bot, DamageSource source, long tick) {
        if (bot == null || source == null) return;
        State s = STATES.get(bot.getUuid());
        if (s == null) return;

        Entity attacker = source.getAttacker();
        Entity src = source.getSource();
        boolean fromSnowball = src instanceof SnowballEntity;

        switch (s.phase) {
            case PROBING -> {
                if (fromSnowball && attacker instanceof ServerPlayerEntity sp
                        && !BotEventHandler.isRegisteredBot(sp)) {
                    s.partnerUuid = sp.getUuid();
                    enterActive(bot, s, tick);
                } else if (attacker instanceof HostileEntity
                        || (attacker instanceof ServerPlayerEntity p
                            && !BotEventHandler.isRegisteredBot(p)
                            && !fromSnowball)) {
                    endFight(s, tick);
                }
            }
            case ACTIVE -> {
                if (!fromSnowball && attacker instanceof HostileEntity) {
                    BotFleeService.fleeFromEntity(bot, attacker, tick);
                }
                // Overwhelmed-yield: the user pelting the bot with sustained snowballs
                // should be able to make it give up even when its own ammo is full.
                // Prior behavior only yielded on inventory-empty, so a player throwing
                // a stack of snowballs at the bot had no effect.
                if (fromSnowball && attacker instanceof ServerPlayerEntity sp
                        && s.partnerUuid != null && s.partnerUuid.equals(sp.getUuid())) {
                    s.snowballHitTicks.addLast(tick);
                    while (!s.snowballHitTicks.isEmpty()
                            && tick - s.snowballHitTicks.peekFirst() > OVERWHELMED_WINDOW_TICKS) {
                        s.snowballHitTicks.pollFirst();
                    }
                    if (s.snowballHitTicks.size() >= OVERWHELMED_HIT_THRESHOLD) {
                        LOGGER.info("Bot {} yields snowball fight (overwhelmed: {} hits in {} ticks)",
                                bot.getName().getString(), s.snowballHitTicks.size(), OVERWHELMED_WINDOW_TICKS);
                        sayLine(bot, YIELD_LINES, "snowball-fight-yield-overwhelmed");
                        s.phase = Phase.YIELDED;
                        s.yieldedClearAtTick = tick + YIELDED_GRACE_TICKS;
                        s.fightCooldownUntilTick = tick + FIGHT_COOLDOWN_TICKS;
                        s.snowballHitTicks.clear();
                    }
                }
            }
            case YIELDED -> {
                if (attacker != null) {
                    BotFleeService.fleeFromEntity(bot, attacker, tick);
                }
            }
            default -> { /* IDLE: ignore */ }
        }
    }

    // ── IDLE / eligibility ────────────────────────────────────────────────

    private static void tickIdle(ServerPlayerEntity bot, ServerWorld world,
                                 MinecraftServer server, State s,
                                 ServerPlayerEntity commander, long tick) {
        if (tick < s.fightCooldownUntilTick) {
            s.eligibleSinceTick = -1;
            return;
        }
        if (commander == null
                || !isEligibleToInitiate(bot, world, server, commander, tick)) {
            s.eligibleSinceTick = -1;
            return;
        }
        if (s.eligibleSinceTick < 0) {
            s.eligibleSinceTick = tick;
        }
        if (tick - s.eligibleSinceTick < IDLE_GATE_TICKS) return;

        // Throttle initiation attempts (avoid hammering the throw path on transient failures).
        if (tick - s.lastThrowTick < 20) return;
        if (!hasSnowball(bot)) return;

        if (throwSnowballAt(bot, world, commander)) {
            s.lastThrowTick = tick;
            s.phase = Phase.PROBING;
            s.phaseEnteredTick = tick;
            s.partnerUuid = commander.getUuid();
            sayLine(bot, PROBE_LINES, "snowball-fight-probe");
            LOGGER.info("Bot {} initiating snowball fight at {}",
                    bot.getName().getString(), commander.getName().getString());
        }
    }

    // ── PROBING ──────────────────────────────────────────────────────────

    private static void tickProbing(ServerPlayerEntity bot, ServerWorld world,
                                    State s, ServerPlayerEntity commander, long tick) {
        if (tick - s.phaseEnteredTick > RECIPROCATE_WINDOW_TICKS) {
            sayLine(bot, TIMEOUT_LINES, "snowball-fight-timeout");
            endFight(s, tick);
            return;
        }
        if (commander == null || s.partnerUuid == null
                || !commander.getUuid().equals(s.partnerUuid)
                || bot.squaredDistanceTo(commander) > DISENGAGE_RANGE * DISENGAGE_RANGE) {
            endFight(s, tick);
            return;
        }
        if (commanderSnowballNearBot(bot, commander, world)) {
            enterActive(bot, s, tick);
        }
    }

    // ── ACTIVE ───────────────────────────────────────────────────────────

    private static void tickActive(ServerPlayerEntity bot, ServerWorld world,
                                   State s, ServerPlayerEntity commander, long tick) {
        if (commander == null || s.partnerUuid == null
                || !commander.getUuid().equals(s.partnerUuid)
                || bot.squaredDistanceTo(commander) > DISENGAGE_RANGE * DISENGAGE_RANGE) {
            endFight(s, tick);
            return;
        }

        // Dodge banter when a commander snowball is flying near us — at most once per 2s.
        if (tick - s.lastDodgeLineTick > 40
                && commanderSnowballNearBot(bot, commander, world)
                && ThreadLocalRandom.current().nextDouble() < 0.25) {
            sayLine(bot, DODGE_LINES, "snowball-fight-dodge");
            s.lastDodgeLineTick = tick;
        }

        if (tick - s.lastThrowTick < s.nextThrowInterval) return;

        if (!hasSnowball(bot)) {
            sayLine(bot, YIELD_LINES, "snowball-fight-yield");
            LOGGER.info("Bot {} yields snowball fight (out of ammo)",
                    bot.getName().getString());
            s.phase = Phase.YIELDED;
            s.yieldedClearAtTick = tick + YIELDED_GRACE_TICKS;
            s.fightCooldownUntilTick = tick + FIGHT_COOLDOWN_TICKS;
            return;
        }
        if (throwSnowballAt(bot, world, commander)) {
            s.lastThrowTick = tick;
            s.nextThrowInterval = ACTIVE_THROW_INTERVAL_MIN
                    + ThreadLocalRandom.current().nextInt(
                            ACTIVE_THROW_INTERVAL_MAX - ACTIVE_THROW_INTERVAL_MIN + 1);
            if (ThreadLocalRandom.current().nextDouble() < 0.10) {
                sayLine(bot, TAUNT_LINES, "snowball-fight-taunt");
            }
        }
    }

    private static void tickYielded(State s, long tick) {
        if (tick >= s.yieldedClearAtTick) {
            s.phase = Phase.IDLE;
            s.partnerUuid = null;
            s.eligibleSinceTick = -1;
        }
    }

    // ── transitions ──────────────────────────────────────────────────────

    private static void enterActive(ServerPlayerEntity bot, State s, long tick) {
        s.phase = Phase.ACTIVE;
        s.phaseEnteredTick = tick;
        s.nextThrowInterval = ACTIVE_THROW_INTERVAL_MIN
                + ThreadLocalRandom.current().nextInt(
                        ACTIVE_THROW_INTERVAL_MAX - ACTIVE_THROW_INTERVAL_MIN + 1);
        s.snowballHitTicks.clear();
        sayLine(bot, ESCALATE_LINES, "snowball-fight-escalate");
        LOGGER.info("Bot {} — snowball fight ACTIVE", bot.getName().getString());
    }

    private static void endFight(State s, long tick) {
        s.phase = Phase.IDLE;
        s.partnerUuid = null;
        s.eligibleSinceTick = -1;
        s.fightCooldownUntilTick = tick + FIGHT_COOLDOWN_TICKS;
        s.snowballHitTicks.clear();
    }

    // ── eligibility ──────────────────────────────────────────────────────

    private static boolean isEligibleToInitiate(ServerPlayerEntity bot, ServerWorld world,
                                                MinecraftServer server,
                                                ServerPlayerEntity commander, long tick) {
        BotEventHandler.Mode mode = BotEventHandler.getCurrentMode(bot);
        if (mode != BotEventHandler.Mode.IDLE && mode != BotEventHandler.Mode.FOLLOW) return false;
        if (TaskService.hasActiveTask(bot.getUuid())) return false;
        if (BotFleeService.isInShelter(bot.getUuid())) return false;
        if (BotCombatCalloutService.wasRecentlyDamagedByHostile(bot, tick, 200)) return false;

        if (bot.squaredDistanceTo(commander) > COMMANDER_RANGE * COMMANDER_RANGE) return false;
        if (commander.getEntityWorld() != world) return false;

        // Health / hunger gates on both sides
        if (bot.getHealth() < bot.getMaxHealth() * MIN_HEALTH_FRACTION) return false;
        if (commander.getHealth() < commander.getMaxHealth() * MIN_HEALTH_FRACTION) return false;
        if (bot.getHungerManager().getFoodLevel() < MIN_FOOD_LEVEL) return false;
        if (commander.getHungerManager().getFoodLevel() < MIN_FOOD_LEVEL) return false;

        // Currently-flashing-red window — someone just took real damage
        if (bot.hurtTime > 0 || commander.hurtTime > 0) return false;

        // Powder-snow gate. inPowderSnow is only true when the entity is sunk into the
        // block; leather boots prevent sinking, so this naturally excludes boot-protected
        // players from the gate.
        if (bot.inPowderSnow || commander.inPowderSnow) return false;

        // Night-with-low-light + anyone unarmoured is too dangerous to start a fight
        if (world.isNight()) {
            int worstLight = Math.min(
                    world.getLightLevel(bot.getBlockPos()),
                    world.getLightLevel(commander.getBlockPos()));
            if (worstLight <= MOB_SPAWN_LIGHT_THRESHOLD
                    && (bot.getArmor() == 0 || commander.getArmor() == 0)) {
                return false;
            }
        }

        // Hostile mobs near either party — not safe to play
        if (hasHostileNear(bot, world, HOSTILE_DANGER_RADIUS)) return false;
        if (hasHostileNear(commander, world, HOSTILE_DANGER_RADIUS)) return false;

        // "Near snow OR has snow" — either inventory ammo or snow blocks within radius
        if (!hasSnowball(bot) && !hasSnowNear(bot, world, SNOW_RADIUS)) return false;

        return true;
    }

    private static boolean hasSnowball(ServerPlayerEntity bot) {
        return findSnowballSlot(bot) >= 0;
    }

    private static int findSnowballSlot(ServerPlayerEntity bot) {
        int size = bot.getInventory().size();
        for (int i = 0; i < size; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.SNOWBALL)) return i;
        }
        return -1;
    }

    private static boolean hasSnowNear(ServerPlayerEntity bot, ServerWorld world, int radius) {
        BlockPos origin = bot.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(
                origin.add(-radius, -2, -radius),
                origin.add(radius, 2, radius))) {
            if (!world.isChunkLoaded(pos)) continue;
            BlockState state = world.getBlockState(pos);
            if (state.isOf(Blocks.SNOW) || state.isOf(Blocks.SNOW_BLOCK)) return true;
        }
        return false;
    }

    private static boolean hasHostileNear(LivingEntity who, ServerWorld world, double radius) {
        Box box = who.getBoundingBox().expand(radius);
        List<Entity> hostiles = world.getOtherEntities(who, box,
                e -> e instanceof HostileEntity && e.isAlive());
        return !hostiles.isEmpty();
    }

    private static boolean commanderSnowballNearBot(ServerPlayerEntity bot,
                                                    ServerPlayerEntity commander,
                                                    ServerWorld world) {
        Box box = bot.getBoundingBox().expand(RECIPROCATE_SCAN_RADIUS);
        UUID commanderId = commander.getUuid();
        List<Entity> snowballs = world.getOtherEntities(bot, box, e -> {
            if (!(e instanceof SnowballEntity sb)) return false;
            Entity owner = sb.getOwner();
            return owner != null && commanderId.equals(owner.getUuid());
        });
        return !snowballs.isEmpty();
    }

    private static ServerPlayerEntity nearestNonBotPlayer(ServerPlayerEntity bot,
                                                          MinecraftServer server) {
        if (server == null) return null;
        ServerPlayerEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p == null || p.isRemoved() || p.isSpectator()) continue;
            if (p.getUuid().equals(bot.getUuid())) continue;
            if (BotEventHandler.isRegisteredBot(p)) continue;
            if (p.getEntityWorld() != bot.getEntityWorld()) continue;
            double sq = p.squaredDistanceTo(bot);
            if (sq < bestSq) {
                bestSq = sq;
                best = p;
            }
        }
        return best;
    }

    /**
     * Aims at the target with a small upward bias to compensate for gravity, then routes
     * through the same vanilla path a real player uses: {@code stack.use()} →
     * {@code SnowballItem.use()} which plays the throw sound, spawns a {@code SnowballEntity}
     * with velocity from the bot's pitch/yaw (POWER 1.5, divergence 1.0), increments the
     * USED stat, and decrements the stack. Returns true if the use was accepted.
     */
    private static boolean throwSnowballAt(ServerPlayerEntity bot, ServerWorld world,
                                           ServerPlayerEntity target) {
        if (!BotActions.ensureHotbarItem(bot, Items.SNOWBALL)) return false;
        ItemStack stack = bot.getMainHandStack();
        if (stack.isEmpty() || !stack.isOf(Items.SNOWBALL)) return false;

        aimAt(bot, target);

        ActionResult result = stack.use(world, bot, Hand.MAIN_HAND);
        if (result.isAccepted()) {
            bot.swingHand(Hand.MAIN_HAND, true);
            return true;
        }
        return false;
    }

    /**
     * Sets the bot's yaw/pitch to aim at the target's eye, with an upward bias scaled by
     * horizontal distance. The bias compensates for gravity drop on the snowball arc — same
     * intuition a real player applies when throwing past a few blocks.
     */
    private static void aimAt(ServerPlayerEntity bot, ServerPlayerEntity target) {
        Vec3d eye = bot.getEyePos();
        double dx = target.getX() - eye.x;
        double dy = (target.getY() + target.getStandingEyeHeight() * 0.85) - eye.y;
        double dz = target.getZ() - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 0.01) return;
        double aimY = dy + horiz * 0.12;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(aimY, horiz));
        pitch = Math.max(-90f, Math.min(90f, pitch));

        bot.setYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.setBodyYaw(yaw);
        bot.setPitch(pitch);
    }

    private static void sayLine(ServerPlayerEntity bot, String[] pool, String tag) {
        if (CompanionOverheadDialogueService.isRecentlyShown(bot.getUuid())) return;
        String line = pool[ThreadLocalRandom.current().nextInt(pool.length)];
        CompanionOverheadDialogueService.showOverheadLine(
                bot, line, DIALOGUE_DURATION_MS, DIALOGUE_RANGE, tag, null);
    }

    public static void clearAll() {
        STATES.clear();
    }
}
