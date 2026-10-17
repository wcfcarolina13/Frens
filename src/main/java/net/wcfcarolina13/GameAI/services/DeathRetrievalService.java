package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.World;
import net.minecraft.world.rule.GameRules;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GameAI.DropSweeper;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.network.BotAccessGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code /bot retrieve <bot> [me|<deadBot>]}: the bot fast-travels to a safe spot next to the
 * death position, sweeps the drops within {@link DeathRetrievalPolicy#SWEEP_RADIUS} blocks, reports
 * the count and fast-travels back to whoever sent it (or stays and reports when they are offline
 * or it cannot travel). Rules and text live in {@link DeathRetrievalPolicy}.
 *
 * <p>Threads: {@link #execute} runs in the Brigadier executor (server thread) and reads the world
 * there. {@link #onArrival} is called from the travel arrival on the server thread, hops to a
 * worker for the sweep (the drop sweep sleeps and walks), and hops back to the server thread for
 * the report and the return travel.
 */
public final class DeathRetrievalService {
    private static final Logger LOGGER = LoggerFactory.getLogger("death-retrieval");
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "death-retrieval-" + THREAD_ID.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private DeathRetrievalService() {}

    /** The resolved death to fetch. */
    private record Target(String dimension, BlockPos pos, String cause, String killer, Long epochMs, String whose) {}

    /**
     * Validates and starts a retrieval. Server thread (command executor).
     *
     * @param who null or "me" for the sender's own death, else the name of a bot that died
     */
    public static int execute(ServerCommandSource source, String botName, String who) {
        MinecraftServer server = source.getServer();
        ServerPlayerEntity sender = source.getPlayer();

        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);
        if (!(bot instanceof createFakePlayer) || !BotRegistry.isRegistered(bot.getUuid()) || bot.isRemoved()) {
            return fail(source, "Bot '" + botName + "' not found.");
        }
        String alias = bot.getName().getString();
        if (sender != null && !BotAccessGate.permits(sender, bot, "retrieve")) {
            return fail(source, DeathRetrievalPolicy.notOwner(alias));
        }
        if (NavigationArtifactService.isTraveling(bot.getUuid())) {
            return fail(source, DeathRetrievalPolicy.alreadyTraveling(alias));
        }

        Target target;
        if (DeathRetrievalPolicy.isMe(who)) {
            if (sender == null) {
                return fail(source, DeathRetrievalPolicy.consoleNeedsDeadBot());
            }
            target = resolveSenderDeath(server, sender);
            if (target == null) {
                return fail(source, DeathRetrievalPolicy.noRecordMe());
            }
        } else {
            String deadBot = who.trim();
            BotDeathRecordService.DeathRecord rec = BotDeathRecordService.getBotRecord(server, deadBot);
            if (rec == null) {
                return fail(source, DeathRetrievalPolicy.noRecordBot(deadBot));
            }
            if (sender != null && !mayFetchFor(sender, deadBot)) {
                return fail(source, DeathRetrievalPolicy.notOwnerOfDead(deadBot));
            }
            target = new Target(rec.dimension(), rec.pos(), rec.cause(), rec.killer(), rec.epochMs(), deadBot + "'s");
        }

        long now = System.currentTimeMillis();
        boolean keepInventory = server.getOverworld().getGameRules().getValue(GameRules.KEEP_INVENTORY);
        DeathRetrievalPolicy.Verdict verdict = DeathRetrievalPolicy.judge(target.cause(), keepInventory);
        List<DeathRetrievalPolicy.Warning> warnings =
                DeathRetrievalPolicy.warnings(target.cause(), target.killer(), target.epochMs(), now);
        long ageMs = DeathRetrievalPolicy.ageMs(target.epochMs(), now);
        LOGGER.info("[Retrieve] bot={} sender={} target={} at {} in {} cause={} killer={} ageMs={} verdict={} warnings={}",
                alias, sender == null ? "console" : sender.getName().getString(), target.whose(),
                target.pos().toShortString(), target.dimension(), target.cause(), target.killer(),
                ageMs, verdict, warnings);
        if (verdict.refused()) {
            return fail(source, DeathRetrievalPolicy.refusal(verdict, target.whose()));
        }

        Identifier dimId = Identifier.tryParse(target.dimension() == null ? "" : target.dimension());
        RegistryKey<World> dimKey = dimId == null ? null : RegistryKey.of(RegistryKeys.WORLD, dimId);
        ServerWorld world = dimKey == null ? null : server.getWorld(dimKey);
        if (world == null) {
            return fail(source, DeathRetrievalPolicy.worldUnavailable(target.dimension()));
        }

        BlockPos stand = findStandPoint(world, target.pos());
        if (stand == null) {
            LOGGER.info("[Retrieve] {}: no safe stand point within {} blocks of {}",
                    alias, DeathRetrievalPolicy.STAND_RADIUS, target.pos().toShortString());
            return fail(source, DeathRetrievalPolicy.noStandSpot(alias));
        }

        // Reports go to the sender, or for a console run to the bot's owner.
        UUID recipient = sender != null ? sender.getUuid() : CompanionCommunicationPolicy.resolveOwnerUuid(bot);
        if (recipient == null) {
            LOGGER.info("[Retrieve] {}: console run on a bot with no owner; reports go to the log only", alias);
        }
        DeathRetrievalPolicy.ReturnMode mode = sender != null
                ? DeathRetrievalPolicy.ReturnMode.SENDER : DeathRetrievalPolicy.ReturnMode.STAY;

        boolean started = NavigationArtifactService.beginRetrievalTravel(server, bot, alias, stand, dimKey, recipient);
        if (!started) {
            LOGGER.info("[Retrieve] {}: travel did not start (gate refused)", alias);
            return fail(source, DeathRetrievalPolicy.cannotSetOff(alias));
        }
        // Whose death rides in the action, so the arrival (also after a reload) finds its tagged drops.
        // "me" only when the mod's record is this same vanilla death.
        String whoForAction = DeathRetrievalPolicy.isMe(who)
                ? (target.epochMs() != null ? DeathRetrievalPolicy.ME : null)
                : who.trim();
        NavigationArtifactService.schedulePostArrival(alias, new NavigationArtifactService.PostArrivalAction(
                DeathRetrievalPolicy.actionType(mode, whoForAction), stand, recipient, null));
        LOGGER.info("[Retrieve] {} departing to stand point {} in {} (death {}) return={}",
                alias, stand.toShortString(), dimKey.getValue(), target.pos().toShortString(), mode);

        String depart = DeathRetrievalPolicy.departing(alias, target.whose(),
                target.pos().getX(), target.pos().getY(), target.pos().getZ(), target.dimension());
        source.sendFeedback(() -> Text.literal("§a" + depart + "§r"), false);
        for (DeathRetrievalPolicy.Warning w : warnings) {
            String warn = DeathRetrievalPolicy.warningLine(w, alias, target.killer(), ageMs);
            source.sendFeedback(() -> Text.literal("§e" + warn + "§r"), false);
        }
        return 1;
    }

    /**
     * Travel arrival hook for a {@code retrieve:} action. Server thread. Drops the action when the
     * bot landed at Overworld spawn (target world unavailable) or on a different trip.
     */
    public static void onArrival(MinecraftServer server, String botAlias, BlockPos arrivedDest,
                                 boolean dimensionFallback, NavigationArtifactService.PostArrivalAction action) {
        server.execute(() -> {
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botAlias);
            if (bot != null) {
                // Never inherit a filter a stuck earlier sweep left behind.
                DropSweeper.setAllowedDrops(bot.getUuid(), null);
            }
            if (bot == null || bot.isRemoved()) {
                LOGGER.info("[Retrieve] {} gone after arrival; sweep skipped", botAlias);
                return;
            }
            if (dimensionFallback) {
                LOGGER.info("[Retrieve] {} landed at Overworld spawn (target world unavailable); canceled", botAlias);
                NavigationArtifactService.notifyPlayer(server, action.ownerUuid(),
                        "§e" + DeathRetrievalPolicy.arrivedAtFallback(botAlias) + "§r");
                return;
            }
            if (action.target() == null || !action.target().equals(arrivedDest)) {
                LOGGER.info("[Retrieve] {} retrieve action dropped: trip to {} is not the retrieval trip to {}",
                        botAlias, arrivedDest == null ? "null" : arrivedDest.toShortString(),
                        action.target() == null ? "null" : action.target().toShortString());
                return;
            }
            int before = countItems(bot);
            long sweepStartMs = System.currentTimeMillis();
            ServerCommandSource botSource = bot.getCommandSource().withSilent();
            Map<String, Object> params = new HashMap<>();
            params.put("radius", DeathRetrievalPolicy.SWEEP_RADIUS);
            params.put("vertical", DeathRetrievalPolicy.SWEEP_RADIUS);
            params.put("maxRadius", DeathRetrievalPolicy.SWEEP_RADIUS);
            params.put("maxVertical", DeathRetrievalPolicy.SWEEP_RADIUS);
            params.put("passes", DeathRetrievalPolicy.SWEEP_PASSES);
            // Never store into, or place, chests at the death spot: a full bot stops and says so.
            params.put("noOffload", true);
            Set<UUID> filter = deathDropFilter(server, bot, arrivedDest, action);
            LOGGER.info("[Retrieve] {} sweeping drops around {} (r={} passes={} tagged={})",
                    botAlias, arrivedDest.toShortString(), DeathRetrievalPolicy.SWEEP_RADIUS, DeathRetrievalPolicy.SWEEP_PASSES,
                    filter == null ? "none, sweeping everything" : filter.size());
            UUID botUuid = bot.getUuid();
            EXECUTOR.submit(() -> {
                SkillExecutionResult result;
                DropSweeper.setAllowedDrops(botUuid, filter);
                try {
                    result = runSweep(botAlias, botSource, params);
                    if ((result == null || !result.success()) && !bot.isDead() && !bot.isRemoved()) {
                        LOGGER.info("[Retrieve] {} sweep failed ('{}'), retrying once in {} ms", botAlias,
                                result == null ? "null" : result.message(), DeathRetrievalPolicy.SWEEP_RETRY_DELAY_MS);
                        try {
                            Thread.sleep(DeathRetrievalPolicy.SWEEP_RETRY_DELAY_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        if (!bot.isDead() && !bot.isRemoved()) {
                            result = runSweep(botAlias, botSource, params);
                        }
                    }
                } finally {
                    DropSweeper.setAllowedDrops(botUuid, null);
                }
                final SkillExecutionResult finalResult = result;
                server.execute(() -> finish(server, botAlias, bot, before, sweepStartMs, finalResult, action, filter));
            });
        });
    }

    /** One drop_sweep run on the calling worker thread. */
    private static SkillExecutionResult runSweep(String botAlias, ServerCommandSource botSource, Map<String, Object> params) {
        try {
            SkillContext ctx = new SkillContext(botSource,
                    SharedStateService.safeSharedState("death-retrieval"), params, null);
            return SkillManager.runSkill("drop_sweep", ctx);
        } catch (Throwable t) {
            LOGGER.warn("[Retrieve] {} sweep crashed: {}", botAlias, t.toString());
            return SkillExecutionResult.failure(t.getClass().getSimpleName());
        }
    }

    /**
     * After the sweep: died, failed twice (stay), or report the count and go back to the sender.
     * Server thread. {@code sweptBot} is the entity that arrived, so a respawn elsewhere is not
     * mistaken for it.
     */
    private static void finish(MinecraftServer server, String botAlias, ServerPlayerEntity sweptBot, int before,
                               long sweepStartMs, SkillExecutionResult result,
                               NavigationArtifactService.PostArrivalAction action, Set<UUID> filter) {
        UUID recipient = action.ownerUuid();
        BotDeathRecordService.DeathRecord lastDeath = BotDeathRecordService.getBotRecord(server, botAlias);
        boolean died = sweptBot.isDead() || (lastDeath != null && lastDeath.epochMs() >= sweepStartMs);
        if (died) {
            LOGGER.info("[Retrieve] {} died during the sweep", botAlias);
            NavigationArtifactService.notifyPlayer(server, recipient,
                    "§c" + DeathRetrievalPolicy.diedAtDeathSpot(botAlias) + "§r");
            return;
        }
        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botAlias);
        if (bot == null || bot.isRemoved() || bot != sweptBot) {
            LOGGER.info("[Retrieve] {} gone before the sweep report", botAlias);
            return;
        }
        // Offload is off for this sweep, so the inventory gain is the real count.
        int picked = Math.max(0, countItems(bot) - before);
        boolean ok = result != null && result.success();
        LOGGER.info("[Retrieve] {} sweep done: picked={} tagged={} taggedGone={} success={} msg='{}'",
                botAlias, picked, filter == null ? 0 : filter.size(), taggedGone(bot, filter), ok,
                result == null ? "null" : result.message());
        if (!ok) {
            LOGGER.info("[Retrieve] {} stays: sweep failed twice", botAlias);
            NavigationArtifactService.notifyPlayer(server, recipient, "§e"
                    + DeathRetrievalPolicy.sweepFailedStaying(botAlias, result == null ? null : result.message()) + "§r");
            return;
        }
        boolean leftBehind = bot.getInventory().getEmptySlot() == -1 && dropsRemain(bot, filter);
        if (leftBehind) {
            NavigationArtifactService.notifyPlayer(server, recipient,
                    "§a" + DeathRetrievalPolicy.result(botAlias, picked) + "§r");
            NavigationArtifactService.notifyPlayer(server, recipient,
                    "§e" + DeathRetrievalPolicy.leftItemsBehind(botAlias) + "§r");
        } else {
            NavigationArtifactService.notifyPlayer(server, recipient,
                    "§a" + DeathRetrievalPolicy.sweepReport(botAlias, result.message(), picked) + "§r");
        }

        Optional<DeathRetrievalPolicy.ReturnMode> mode = DeathRetrievalPolicy.parseAction(action.type());
        if (mode.isEmpty() || mode.get() == DeathRetrievalPolicy.ReturnMode.STAY) {
            NavigationArtifactService.notifyPlayer(server, recipient,
                    "§e" + DeathRetrievalPolicy.stayingSentFromConsole(botAlias) + "§r");
            return;
        }
        ServerPlayerEntity sender = recipient == null ? null : server.getPlayerManager().getPlayer(recipient);
        if (sender == null || sender.isRemoved()) {
            LOGGER.info("[Retrieve] {} stays: sender offline", botAlias);
            NavigationArtifactService.notifyPlayer(server, recipient,
                    "§e" + DeathRetrievalPolicy.stayingOffline(botAlias) + "§r");
            return;
        }
        // Land on a safe standing spot near the sender, never at their live position (a flying or
        // spectating sender would drop the bot from the air).
        ServerWorld homeWorld = (ServerWorld) sender.getEntityWorld();
        BlockPos home = findReturnPoint(homeWorld, sender.getBlockPos());
        RegistryKey<World> homeDim = homeWorld.getRegistryKey();
        if (home == null) {
            LOGGER.info("[Retrieve] {} stays: no safe landing spot near {} at {}",
                    botAlias, sender.getName().getString(), sender.getBlockPos().toShortString());
            NavigationArtifactService.notifyPlayer(server, recipient,
                    "§e" + DeathRetrievalPolicy.stayingNoLandingSpot(botAlias) + "§r");
            return;
        }
        int delay = NavigationArtifactService.beginErrandReturnTravel(server, bot, botAlias, home, homeDim, recipient);
        if (delay < 0) {
            LOGGER.info("[Retrieve] {} stays: return travel did not start", botAlias);
            NavigationArtifactService.notifyPlayer(server, recipient,
                    "§e" + DeathRetrievalPolicy.stayingCannotTravel(botAlias) + "§r");
            return;
        }
        LOGGER.info("[Retrieve] {} returning to {} at {} in {} (ETA {}s)",
                botAlias, sender.getName().getString(), home.toShortString(), homeDim.getValue(), delay / 20);
        NavigationArtifactService.notifyPlayer(server, recipient,
                "§e" + DeathRetrievalPolicy.returning(botAlias, delay / 20) + "§r");
    }

    /** The sender's vanilla last death, with cause and time when the mod recorded the same death. */
    private static Target resolveSenderDeath(MinecraftServer server, ServerPlayerEntity sender) {
        Optional<GlobalPos> last = sender.getLastDeathPos();
        if (last.isEmpty()) {
            return null;
        }
        String dim = last.get().dimension().getValue().toString();
        BlockPos pos = last.get().pos();
        BotDeathRecordService.DeathRecord rec = BotDeathRecordService.getPlayerRecord(server, sender.getUuid());
        boolean same = rec != null && dim.equals(rec.dimension()) && pos.equals(rec.pos());
        return new Target(dim, pos,
                same ? rec.cause() : null,
                same ? rec.killer() : null,
                same ? rec.epochMs() : null,
                "your");
    }

    /** Owner of the dead bot (from config, so it works while the dead bot is offline), op or host. */
    private static boolean mayFetchFor(ServerPlayerEntity sender, String deadBot) {
        UUID owner = null;
        try {
            ManualConfig.BotOwnership o = Frens.CONFIG == null ? null : Frens.CONFIG.getOwner(deadBot);
            if (o != null && o.ownerUuid() != null && !o.ownerUuid().isBlank()) {
                owner = UUID.fromString(o.ownerUuid());
            }
        } catch (RuntimeException ignored) {
        }
        BotAccessPolicy.Decision d = BotAccessPolicy.authorize(sender.getUuid(), owner, true,
                Frens.isOperator(sender), BotAccessGate.isHost(sender));
        return BotAccessPolicy.allowed(d);
    }

    /** Server thread: loads the death chunk if needed (one-off, like a teleport). */
    /**
     * A safe standing spot near the sender: the stand-point grid around them, else the first
     * standable cell straight down from them (a sender flying or spectating high up). Null when
     * neither has one. Server thread.
     */
    private static BlockPos findReturnPoint(ServerWorld world, BlockPos senderPos) {
        BlockPos near = findStandPoint(world, senderPos);
        if (near != null) {
            return near;
        }
        boolean[] column = new boolean[DeathRetrievalPolicy.RETURN_SCAN_DEPTH];
        for (int i = 0; i < column.length; i++) {
            BlockPos p = senderPos.down(i);
            if (world.isOutOfHeightLimit(p) || world.isOutOfHeightLimit(p.up())) break;
            column[i] = isSafeStand(world, p);
        }
        int i = DeathRetrievalPolicy.firstStandableBelow(column);
        return i < 0 ? null : senderPos.down(i);
    }

    private static boolean isSafeStand(ServerWorld world, BlockPos p) {
        return SafePositionService.isSpawnable(world, p)
                && !BotHazardService.isDeadlyBlock(world.getBlockState(p.down()))
                && !BotHazardService.isDeadlyBlock(world.getBlockState(p));
    }

    private static BlockPos findStandPoint(ServerWorld world, BlockPos death) {
        int r = DeathRetrievalPolicy.STAND_RADIUS;
        int dyMin = DeathRetrievalPolicy.STAND_DY_MIN;
        int dyMax = DeathRetrievalPolicy.STAND_DY_MAX;
        boolean[] safe = new boolean[DeathRetrievalPolicy.gridSize(r, dyMin, dyMax)];
        for (int dy = dyMin; dy <= dyMax; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = death.add(dx, dy, dz);
                    if (world.isOutOfHeightLimit(p) || world.isOutOfHeightLimit(p.up())) continue;
                    safe[DeathRetrievalPolicy.gridIndex(dx, dy, dz, r, dyMin)] = isSafeStand(world, p);
                }
            }
        }
        DeathRetrievalPolicy.Offset off = DeathRetrievalPolicy.chooseStandPoint(safe, r, dyMin, dyMax);
        return off == null ? null : death.add(off.dx(), off.dy(), off.dz());
    }

    /** This trip's drops (tagged ones, or any when untagged) still within the sweep radius. Server thread. */
    private static boolean dropsRemain(ServerPlayerEntity bot, Set<UUID> filter) {
        double r = DeathRetrievalPolicy.SWEEP_RADIUS;
        return !bot.getEntityWorld().getEntitiesByClass(net.minecraft.entity.ItemEntity.class,
                bot.getBoundingBox().expand(r, r, r),
                e -> !e.isRemoved() && DeathDropFilterPolicy.allowed(filter, e.getUuid())).isEmpty();
    }

    /** Tagged drops no longer in the world (picked up, merged or despawned). Server thread. */
    private static int taggedGone(ServerPlayerEntity bot, Set<UUID> filter) {
        if (filter == null || !(bot.getEntityWorld() instanceof ServerWorld world)) return 0;
        int gone = 0;
        for (UUID id : filter) {
            net.minecraft.entity.Entity e = world.getEntity(id);
            if (e == null || e.isRemoved()) gone++;
        }
        return gone;
    }

    /**
     * The item entities that dropped at the death this trip is for, or null (sweep everything) when
     * the action names no death, the record is gone, has no tags, or is a later death elsewhere.
     * Server thread.
     */
    private static Set<UUID> deathDropFilter(MinecraftServer server, ServerPlayerEntity bot, BlockPos stand,
                                             NavigationArtifactService.PostArrivalAction action) {
        Optional<String> who = DeathRetrievalPolicy.parseWho(action.type());
        if (who.isEmpty()) return null;
        BotDeathRecordService.DeathRecord rec = DeathRetrievalPolicy.ME.equals(who.get())
                ? BotDeathRecordService.getPlayerRecord(server, action.ownerUuid())
                : BotDeathRecordService.getBotRecord(server, who.get());
        if (rec == null) return null;
        String dim = bot.getEntityWorld().getRegistryKey().getValue().toString();
        if (!dim.equals(rec.dimension())
                || !DeathDropFilterPolicy.deathNearStand(rec.x() - stand.getX(), rec.y() - stand.getY(), rec.z() - stand.getZ())) {
            LOGGER.info("[Retrieve] {}: {}'s last death at {} is not this trip's, sweeping everything",
                    bot.getName().getString(), who.get(), rec.pos().toShortString());
            return null;
        }
        return DeathDropFilterPolicy.toFilter(rec.dropUuids());
    }

    /** Total item count in the bot's inventory (all slots). Server thread. */
    private static int countItems(ServerPlayerEntity bot) {
        PlayerInventory inv = bot.getInventory();
        int total = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s != null && !s.isEmpty()) total += s.getCount();
        }
        return total;
    }

    private static int fail(ServerCommandSource source, String message) {
        source.sendFeedback(() -> Text.literal("§c" + message + "§r"), false);
        return 0;
    }
}
