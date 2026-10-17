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
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.network.BotAccessGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
        DeathRetrievalPolicy.Verdict verdict = DeathRetrievalPolicy.judge(target.cause(), target.killer(), target.epochMs(), now);
        LOGGER.info("[Retrieve] bot={} sender={} target={} at {} in {} cause={} killer={} ageMs={} verdict={}",
                alias, sender == null ? "console" : sender.getName().getString(), target.whose(),
                target.pos().toShortString(), target.dimension(), target.cause(), target.killer(),
                DeathRetrievalPolicy.ageMs(target.epochMs(), now), verdict);
        if (verdict.refused()) {
            return fail(source, DeathRetrievalPolicy.refusal(verdict, target.whose(),
                    DeathRetrievalPolicy.ageMs(target.epochMs(), now)));
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
        DeathRetrievalPolicy.ReturnMode mode = sender != null
                ? DeathRetrievalPolicy.ReturnMode.SENDER : DeathRetrievalPolicy.ReturnMode.STAY;

        boolean started = NavigationArtifactService.beginRetrievalTravel(server, bot, alias, stand, dimKey, recipient);
        if (!started) {
            LOGGER.info("[Retrieve] {}: travel did not start (gate refused)", alias);
            return fail(source, DeathRetrievalPolicy.cannotSetOff(alias));
        }
        NavigationArtifactService.schedulePostArrival(alias, new NavigationArtifactService.PostArrivalAction(
                DeathRetrievalPolicy.actionType(mode), stand, recipient,
                sender != null ? sender.getEntityPos() : null));
        LOGGER.info("[Retrieve] {} departing to stand point {} in {} (death {}) return={}",
                alias, stand.toShortString(), dimKey.getValue(), target.pos().toShortString(), mode);

        String depart = DeathRetrievalPolicy.departing(alias, target.whose(),
                target.pos().getX(), target.pos().getY(), target.pos().getZ(), target.dimension());
        source.sendFeedback(() -> Text.literal("§a" + depart + "§r"), false);
        if (verdict == DeathRetrievalPolicy.Verdict.WARN_MOB) {
            String warn = DeathRetrievalPolicy.mobWarning(alias, target.killer());
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
            ServerCommandSource botSource = bot.getCommandSource().withSilent();
            Map<String, Object> params = new HashMap<>();
            params.put("radius", DeathRetrievalPolicy.SWEEP_RADIUS);
            params.put("passes", DeathRetrievalPolicy.SWEEP_PASSES);
            LOGGER.info("[Retrieve] {} sweeping drops around {} (r={} passes={})",
                    botAlias, arrivedDest.toShortString(), DeathRetrievalPolicy.SWEEP_RADIUS, DeathRetrievalPolicy.SWEEP_PASSES);
            EXECUTOR.submit(() -> {
                SkillExecutionResult result;
                try {
                    SkillContext ctx = new SkillContext(botSource,
                            SharedStateService.safeSharedState("death-retrieval"), params, null);
                    result = SkillManager.runSkill("drop_sweep", ctx);
                } catch (Throwable t) {
                    LOGGER.warn("[Retrieve] {} sweep crashed: {}", botAlias, t.toString());
                    result = SkillExecutionResult.failure(t.getClass().getSimpleName());
                }
                final SkillExecutionResult finalResult = result;
                server.execute(() -> finish(server, botAlias, before, finalResult, action));
            });
        });
    }

    /** After the sweep: report the count, then go back to the sender or stay. Server thread. */
    private static void finish(MinecraftServer server, String botAlias, int before,
                               SkillExecutionResult result, NavigationArtifactService.PostArrivalAction action) {
        UUID recipient = action.ownerUuid();
        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botAlias);
        if (bot == null || bot.isRemoved()) {
            LOGGER.info("[Retrieve] {} gone before the sweep report", botAlias);
            return;
        }
        int picked = Math.max(0, countItems(bot) - before);
        boolean ok = result != null && result.success();
        LOGGER.info("[Retrieve] {} sweep done: picked={} success={} msg='{}'",
                botAlias, picked, ok, result == null ? "null" : result.message());
        if (!ok && picked == 0) {
            NavigationArtifactService.notifyPlayer(server, recipient, "§e"
                    + DeathRetrievalPolicy.sweepFailed(botAlias, result == null ? null : result.message()) + "§r");
        } else {
            NavigationArtifactService.notifyPlayer(server, recipient,
                    "§a" + DeathRetrievalPolicy.result(botAlias, picked) + "§r");
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
        BlockPos home = sender.getBlockPos();
        RegistryKey<World> homeDim = sender.getEntityWorld().getRegistryKey();
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
                    safe[DeathRetrievalPolicy.gridIndex(dx, dy, dz, r, dyMin)] =
                            SafePositionService.isSpawnable(world, p)
                            && !BotHazardService.isDeadlyBlock(world.getBlockState(p.down()))
                            && !BotHazardService.isDeadlyBlock(world.getBlockState(p));
                }
            }
        }
        DeathRetrievalPolicy.Offset off = DeathRetrievalPolicy.chooseStandPoint(safe, r, dyMin, dyMax);
        return off == null ? null : death.add(off.dx(), off.dy(), off.dz());
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
