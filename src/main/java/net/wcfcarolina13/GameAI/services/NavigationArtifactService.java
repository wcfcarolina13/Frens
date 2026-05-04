package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityPosition;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.wcfcarolina13.Entity.AutoFaceEntity;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.registry.Registries;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NavigationArtifactService {

    private static final Logger LOGGER = LoggerFactory.getLogger("nav-artifact");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static final String TRAVEL_FILE = "pending_travels.json";

    // ── Fast-travel cost constants ──────────────────────────────────────
    /** Hunger cost formula: 1 food point per HUNGER_DISTANCE_DIVISOR blocks traveled. */
    private static final double HUNGER_DISTANCE_DIVISOR = 20.0;
    /** Minimum food level the bot must retain after travel (6 = 3 drumsticks). */
    private static final int MIN_POST_TRAVEL_FOOD = 6;
    /** Cooldown between fast-travels per bot, in ticks (3 minutes = 3600 ticks). */
    private static final long TRAVEL_COOLDOWN_TICKS = 3600L;
    /** Per-bot cooldown tracker: bot UUID -> server tick of last departure. */
    private static final Map<UUID, Long> TRAVEL_COOLDOWNS = new ConcurrentHashMap<>();

    /** Clear travel cooldown for a bot (used by sunrise resume to allow immediate return travel). */
    public static void clearTravelCooldown(UUID botUuid) {
        if (botUuid != null) TRAVEL_COOLDOWNS.remove(botUuid);
    }

    /** Get remaining cooldown ticks for a bot, or 0 if no cooldown active. */
    public static long getRemainingCooldownTicks(UUID botUuid, long currentTick) {
        if (botUuid == null) return 0;
        Long lastDeparture = TRAVEL_COOLDOWNS.get(botUuid);
        if (lastDeparture == null) return 0;
        long elapsed = currentTick - lastDeparture;
        return elapsed >= TRAVEL_COOLDOWN_TICKS ? 0 : TRAVEL_COOLDOWN_TICKS - elapsed;
    }

    // ── Smoke signal navigation beacon ───────────────────────────────────
    private static final int SMOKE_SIGNAL_SCAN_RADIUS_H = 8;
    private static final int SMOKE_SIGNAL_SCAN_RADIUS_V = 8;
    private static final long SMOKE_SIGNAL_CACHE_TICKS = 1200L; // 60 seconds
    private static final Map<BlockPos, Long> SMOKE_SIGNAL_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Boolean> SMOKE_SIGNAL_RESULT_CACHE = new ConcurrentHashMap<>();

    public static boolean hasSmokeSignal(ServerWorld world, BlockPos basePos) {
        if (world == null || basePos == null) return false;
        long now = world.getTime();
        Long cachedAt = SMOKE_SIGNAL_CACHE.get(basePos);
        if (cachedAt != null && now - cachedAt < SMOKE_SIGNAL_CACHE_TICKS) {
            return Boolean.TRUE.equals(SMOKE_SIGNAL_RESULT_CACHE.get(basePos));
        }
        boolean found = scanForSmokeSignal(world, basePos);
        SMOKE_SIGNAL_CACHE.put(basePos, now);
        SMOKE_SIGNAL_RESULT_CACHE.put(basePos, found);
        return found;
    }

    private static boolean scanForSmokeSignal(ServerWorld world, BlockPos center) {
        for (int dx = -SMOKE_SIGNAL_SCAN_RADIUS_H; dx <= SMOKE_SIGNAL_SCAN_RADIUS_H; dx++) {
            for (int dy = -SMOKE_SIGNAL_SCAN_RADIUS_V; dy <= SMOKE_SIGNAL_SCAN_RADIUS_V; dy++) {
                for (int dz = -SMOKE_SIGNAL_SCAN_RADIUS_H; dz <= SMOKE_SIGNAL_SCAN_RADIUS_H; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    net.minecraft.block.BlockState state = world.getBlockState(pos);
                    if ((state.isOf(net.minecraft.block.Blocks.CAMPFIRE)
                            || state.isOf(net.minecraft.block.Blocks.SOUL_CAMPFIRE))
                            && state.get(net.minecraft.block.CampfireBlock.LIT)
                            && world.getBlockState(pos.down()).isOf(net.minecraft.block.Blocks.HAY_BLOCK)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static void clearSmokeSignalCache() {
        SMOKE_SIGNAL_CACHE.clear();
        SMOKE_SIGNAL_RESULT_CACHE.clear();
    }

    private NavigationArtifactService() {}

    /** Gson-serializable DTO mirroring {@link PendingTravel} with primitive/String fields. */
    public static class SavedTravel {
        public String botUuid;
        public String botAlias;
        public int destX, destY, destZ;
        public String dimension;
        public long departureTick;
        public long arrivalTick;
        public String ownerUuid;
        public String mountEntityTypeId;
        public double travelDistance;
        public boolean magicTravel;
    }

    private static Path travelFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("frens").resolve(TRAVEL_FILE);
    }

    // ── Navigation tiers ──────────────────────────────────────────────────

    public enum NavTier { NONE, BASIC, ENHANCED }

    /**
     * Determine navigation tier from bot + player inventories.
     * ENHANCED: either holds Eye of Ender.
     * BASIC: bot holds Compass, Recovery Compass, Map, or Filled Map.
     */
    public static NavTier getBotNavigationTier(ServerPlayerEntity bot, ServerPlayerEntity player) {
        if (hasItemInInventory(bot, Items.ENDER_EYE) || hasItemInInventory(player, Items.ENDER_EYE)) {
            return NavTier.ENHANCED;
        }
        // Lodestone compass with binding → ENHANCED tier (component check only, no chunk validation —
        // the travel-commit path validates the block separately; tier classification should be cheap)
        if (LodestoneCompassService.hasLodestoneCompass(bot)) {
            return NavTier.ENHANCED;
        }
        if (hasItemInInventory(bot, Items.COMPASS)
                || hasItemInInventory(bot, Items.RECOVERY_COMPASS)
                || hasItemInInventory(bot, Items.FILLED_MAP)
                || hasItemInInventory(bot, Items.MAP)) {
            return NavTier.BASIC;
        }
        return NavTier.NONE;
    }

    /** Check if both player and bot each hold at least one ender pearl. */
    public static boolean bothHaveEnderPearl(ServerPlayerEntity player, ServerPlayerEntity bot) {
        return hasItemInInventory(player, Items.ENDER_PEARL)
                && hasItemInInventory(bot, Items.ENDER_PEARL);
    }

    /** Check if both hold an ender pearl AND a chorus fruit. */
    public static boolean bothHaveChorusRecallItems(ServerPlayerEntity player, ServerPlayerEntity bot) {
        return hasItemInInventory(player, Items.ENDER_PEARL)
                && hasItemInInventory(player, Items.CHORUS_FRUIT)
                && hasItemInInventory(bot, Items.ENDER_PEARL)
                && hasItemInInventory(bot, Items.CHORUS_FRUIT);
    }

    /** Check bot has Eye of Ender (kept) + Chorus Fruit (to consume) for Soul of Ender spell. */
    public static boolean botHasSoulOfEnderItems(ServerPlayerEntity bot) {
        return hasItemInInventory(bot, Items.ENDER_EYE)
                && hasItemInInventory(bot, Items.CHORUS_FRUIT);
    }

    /** Consume one item of a given type from the player's inventory. Returns true if consumed. */
    public static boolean consumeItem(ServerPlayerEntity player, net.minecraft.item.Item item) {
        if (player == null) return false;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack != null && !stack.isEmpty() && stack.isOf(item)) {
                stack.decrement(1);
                if (stack.isEmpty()) inv.setStack(i, net.minecraft.item.ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }

    /**
     * Estimate how many ticks a fast travel sequence should take based on distance.
     * <ul>
     *   <li>1 real second per chunk (distance / 16)</li>
     *   <li>Cross-dimension adds 30 seconds</li>
     *   <li>Min 5 seconds, max 5 minutes</li>
     * </ul>
     *
     * @param distance      Euclidean distance in blocks between origin and destination.
     * @param crossDimension true if the travel crosses dimensions (e.g. Overworld to Nether).
     * @return delay in game ticks (20 ticks = 1 second).
     */
    public static int calculateDelayTicks(double distance, boolean crossDimension) {
        return calculateDelayTicks(distance, crossDimension, 1.0);
    }

    public static int calculateDelayTicks(double distance, boolean crossDimension, double multiplier) {
        int chunks = Math.max(1, (int) Math.ceil(distance / 16.0));
        // ~1.5 seconds per chunk base travel time (up from 1s)
        int seconds = chunks + chunks / 2;
        if (crossDimension) seconds += 30;
        seconds = Math.max(7, Math.min(300, (int) (seconds * multiplier)));
        return seconds * 20;
    }

    /**
     * Render the tier-reason tag into a player-readable chat suffix.
     *
     * <p>The reason strings produced by {@link #resolveSurfaceTier} and
     * {@link #resolveUndergroundTier} are compact telemetry tags
     * (e.g. {@code lodestone-compass}, {@code map+compass-rendered+smoke-signal},
     * {@code underground-no-artifact}) — fine for the server log, opaque in chat.
     * This helper turns them into prose for the departure notification so
     * players can see why fast-travel activated (lodestone compass? smoke signal?)
     * and at which speed (1.0× / 2.0× / 3.0×).
     *
     * <p>Returns an empty string when no tier was resolved — the travel path
     * bypassed gating (internal summon, debug skip, etc.) and there's nothing
     * meaningful to say.
     */
    private static String formatTierSuffix(String reason, double multiplier) {
        if (reason == null || reason.isBlank()) {
            return "";
        }
        String human = switch (reason) {
            case "lodestone-compass" -> "lodestone compass";
            case "lodestone-compass-lenient" -> "lodestone compass (no target bound)";
            case "ender-eye" -> "Eye of Ender";
            case "wizard-tome" -> "Wizard's Tome";
            case "enchanting-table" -> "nearby Enchanting Table";
            case "mutual-ender-pearls" -> "mutual Ender Pearls";
            case "map+compass+light" -> "map + compass + light source";
            default -> reason
                    .replace("map+compass-rendered", "map + compass")
                    .replace("smoke-signal-underground", "underground smoke signal")
                    .replace("smoke-signal", "smoke signal")
                    .replace("spyglass", "spyglass")
                    .replace("-", " ")
                    .replace("+", " + ");
        };
        String speed;
        if (multiplier <= 1.0) {
            speed = "instant-class";
        } else if (multiplier <= 2.0) {
            speed = "tier 1";
        } else {
            speed = "slow";
        }
        return " \u00A77(fast-travel: " + human + ", " + speed + ")\u00A7e";
    }

    /** Determine delay multiplier based on bot/player artifact tier. 2x for Tier 1, 1x for Tier 2+. */
    public static double artifactDelayMultiplier(ServerPlayerEntity bot, ServerPlayerEntity owner) {
        // Lodestone compass with valid target — Tier 2 (instant-class navigation)
        if (LodestoneCompassService.hasLodestoneCompass(bot)) {
            return 1.0;
        }
        // Tier 2+: Eye of Ender, Wizard's Tome, Enchanting Table, or both hold Ender Pearls.
        if (hasArtifact(bot, net.minecraft.item.Items.ENDER_EYE)
                || hasArtifact(owner, net.minecraft.item.Items.ENDER_EYE)
                || CompanionCommunicationPolicy.hasWizardTome(bot)
                || CompanionCommunicationPolicy.hasWizardTome(owner)
                || isNearBlock(bot, net.minecraft.block.Blocks.ENCHANTING_TABLE, 6)
                || isNearBlock(owner, net.minecraft.block.Blocks.ENCHANTING_TABLE, 6)
                || (hasArtifact(bot, net.minecraft.item.Items.ENDER_PEARL)
                    && hasArtifact(owner, net.minecraft.item.Items.ENDER_PEARL))) {
            return 1.0;
        }
        // Tier 1: Map, Compass, or lodestone compass (lenient — even with lost target,
        // the compass component proves it was a navigation tool).
        if (hasArtifact(bot, net.minecraft.item.Items.FILLED_MAP)
                || hasArtifact(bot, net.minecraft.item.Items.COMPASS)
                || LodestoneCompassService.hasAnyLodestoneCompass(bot)) {
            return 2.0;
        }
        // No artifacts: still allow but at 3x delay.
        return 3.0;
    }

    private static boolean hasArtifact(ServerPlayerEntity player, net.minecraft.item.Item item) {
        if (player == null) return false;
        for (int i = 0; i < player.getInventory().size(); i++) {
            net.minecraft.item.ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(item)) return true;
            // Check inside bundles
            var bundle = stack.get(net.minecraft.component.DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle != null) {
                for (net.minecraft.item.ItemStack bundled : bundle.iterate()) {
                    if (bundled != null && bundled.isOf(item)) return true;
                }
            }
        }
        return false;
    }

    private static boolean isNearBlock(ServerPlayerEntity player, net.minecraft.block.Block block, int radius) {
        if (player == null) return false;
        BlockPos center = player.getBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (player.getEntityWorld().getBlockState(center.add(dx, dy, dz)).isOf(block)) return true;
                }
            }
        }
        return false;
    }

    // ── Travel-tier resolver (surface / underground) ──────────────────────

    /**
     * Outcome of a tier resolution. {@code allowed = false} means the bot cannot
     * initiate fast-travel at all in this context; {@code delayMultiplier} is a
     * multiplier on the base delay (1.0 = tier 2, 2.0 = tier 1, 3.0 = fallback).
     * {@code reason} is a short tag for logging/telemetry.
     */
    public record TravelTierResult(boolean allowed, double delayMultiplier, String reason) {
        public static TravelTierResult refused(String reason) {
            return new TravelTierResult(false, Double.POSITIVE_INFINITY, reason);
        }
        public static TravelTierResult allowed(double multiplier, String reason) {
            return new TravelTierResult(true, multiplier, reason);
        }
    }

    /**
     * Resolve the travel tier for an above-ground trip.
     * <p>Tier 2+ artifacts (lodestone compass with bound target, eye of ender, wizard tome,
     * nearby enchanting table, or mutual ender pearls) short-circuit to 1.0×.
     * <p>Otherwise two gates are considered:
     * <ul>
     *   <li><b>Map + Compass gate</b> — bot has at least one filled map, at least one
     *       compass, and the destination pixel is rendered on at least one of the bot's
     *       maps (strict: {@code colors[pixel] != 0}).</li>
     *   <li><b>Smoke-signal gate</b> — the destination sits inside a labeled saved base
     *       whose centre has a lit campfire-on-hay, and the bot is within 5× the base's
     *       protection radius (Manhattan) of that centre.</li>
     * </ul>
     * Gate scoring: 1 gate → 3.0×, 2 gates → 2.0×. A spyglass anywhere in the scanner
     * surface subtracts one more step (3→2 or 2→1). Zero gates → refused.
     */
    public static TravelTierResult resolveSurfaceTier(MinecraftServer server,
                                                       ServerPlayerEntity bot,
                                                       ServerPlayerEntity owner,
                                                       BlockPos destination,
                                                       RegistryKey<World> destDimension) {
        if (bot == null || destination == null || destDimension == null) {
            return TravelTierResult.refused("missing-context");
        }

        // ── Tier 2+ short-circuits ─────────────────────────────────────
        if (LodestoneCompassService.hasLodestoneCompass(bot)) {
            return TravelTierResult.allowed(1.0, "lodestone-compass");
        }
        if (ArtifactScanner.has(bot, Items.ENDER_EYE) || ArtifactScanner.has(owner, Items.ENDER_EYE)) {
            return TravelTierResult.allowed(1.0, "ender-eye");
        }
        if (CompanionCommunicationPolicy.hasWizardTome(bot) || CompanionCommunicationPolicy.hasWizardTome(owner)) {
            return TravelTierResult.allowed(1.0, "wizard-tome");
        }
        if (isNearBlock(bot, net.minecraft.block.Blocks.ENCHANTING_TABLE, 6)
                || isNearBlock(owner, net.minecraft.block.Blocks.ENCHANTING_TABLE, 6)) {
            return TravelTierResult.allowed(1.0, "enchanting-table");
        }
        if (ArtifactScanner.has(bot, Items.ENDER_PEARL) && ArtifactScanner.has(owner, Items.ENDER_PEARL)) {
            return TravelTierResult.allowed(1.0, "mutual-ender-pearls");
        }

        // ── Gate 1: Map + Compass with destination rendered ────────────
        boolean mapCompassGate =
                ArtifactScanner.hasCompass(bot)
                && ArtifactScanner.hasMap(bot)
                && ArtifactScanner.hasRenderedMapAt(bot, destination.getX(), destination.getZ(), destDimension);

        // ── Gate 2: Smoke signal at labeled destination base ────────────
        boolean smokeSignalGate = false;
        if (server != null) {
            ServerWorld destWorld = server.getWorld(destDimension);
            if (destWorld != null) {
                java.util.Optional<BotHomeService.BaseEntry> destBase =
                        BotHomeService.findBaseNearPosition(server, destWorld, destination);
                if (destBase.isPresent() && hasSmokeSignal(destWorld, destBase.get().pos())) {
                    int baseRadius = destBase.get().radius() > 0
                            ? destBase.get().radius() : BotHomeService.DEFAULT_BASE_PROTECTION_RADIUS;
                    double maxRange = baseRadius * 5.0;
                    double distToBase = bot.getBlockPos().getManhattanDistance(destBase.get().pos());
                    if (distToBase <= maxRange) {
                        smokeSignalGate = true;
                    }
                }
            }
        }

        int gates = (mapCompassGate ? 1 : 0) + (smokeSignalGate ? 1 : 0);
        if (gates == 0) {
            return TravelTierResult.refused("no-surface-gate");
        }

        boolean spyglass = ArtifactScanner.hasSpyglass(bot);
        int stepsSaved = (gates == 2 ? 1 : 0) + (spyglass ? 1 : 0);
        double multiplier = Math.max(1.0, 3.0 - stepsSaved);

        StringBuilder reason = new StringBuilder();
        if (mapCompassGate) reason.append("map+compass-rendered");
        if (smokeSignalGate) { if (reason.length() > 0) reason.append("+"); reason.append("smoke-signal"); }
        if (spyglass) reason.append("+spyglass");
        return TravelTierResult.allowed(multiplier, reason.toString());
    }

    /**
     * Resolve the travel tier for an underground trip. Stricter rules than the surface path:
     * <ul>
     *   <li>Tier 2+ artifacts (lodestone compass with bound target, eye of ender, wizard tome,
     *       nearby enchanting table, mutual ender pearls) → 1.0×.</li>
     *   <li>Map + Compass + at least one torch or lantern → 2.0× (light to read the map).</li>
     *   <li>Lodestone compass without bound target → 2.0× (compass is still a nav tool).</li>
     *   <li>Smoke signal at labeled destination base, within 2× base radius → 3.0×.</li>
     *   <li>Otherwise refused.</li>
     * </ul>
     * Spyglass is not useful underground and is ignored.
     */
    public static TravelTierResult resolveUndergroundTier(MinecraftServer server,
                                                           ServerPlayerEntity bot,
                                                           ServerPlayerEntity owner,
                                                           BlockPos destination,
                                                           RegistryKey<World> destDimension) {
        if (bot == null || destination == null || destDimension == null) {
            return TravelTierResult.refused("missing-context");
        }

        // Tier 2+ short-circuits (same as surface).
        if (LodestoneCompassService.hasLodestoneCompass(bot)) {
            return TravelTierResult.allowed(1.0, "lodestone-compass");
        }
        if (ArtifactScanner.has(bot, Items.ENDER_EYE) || ArtifactScanner.has(owner, Items.ENDER_EYE)) {
            return TravelTierResult.allowed(1.0, "ender-eye");
        }
        if (CompanionCommunicationPolicy.hasWizardTome(bot) || CompanionCommunicationPolicy.hasWizardTome(owner)) {
            return TravelTierResult.allowed(1.0, "wizard-tome");
        }
        if (isNearBlock(bot, net.minecraft.block.Blocks.ENCHANTING_TABLE, 6)
                || isNearBlock(owner, net.minecraft.block.Blocks.ENCHANTING_TABLE, 6)) {
            return TravelTierResult.allowed(1.0, "enchanting-table");
        }
        if (ArtifactScanner.has(bot, Items.ENDER_PEARL) && ArtifactScanner.has(owner, Items.ENDER_PEARL)) {
            return TravelTierResult.allowed(1.0, "mutual-ender-pearls");
        }

        // Map + Compass + light source → 2.0×.
        boolean hasMapAndCompass = ArtifactScanner.hasMap(bot) && ArtifactScanner.hasCompass(bot);
        boolean hasLight = ArtifactScanner.hasTorchOrLantern(bot);
        if (hasMapAndCompass && hasLight) {
            return TravelTierResult.allowed(2.0, "map+compass+light");
        }

        // Lodestone compass without target (lenient) → 2.0×.
        if (LodestoneCompassService.hasAnyLodestoneCompass(bot)) {
            return TravelTierResult.allowed(2.0, "lodestone-compass-lenient");
        }

        // Smoke signal fallback (tight underground range: 2× base radius).
        if (server != null) {
            ServerWorld destWorld = server.getWorld(destDimension);
            if (destWorld != null) {
                java.util.Optional<BotHomeService.BaseEntry> destBase =
                        BotHomeService.findBaseNearPosition(server, destWorld, destination);
                if (destBase.isPresent() && hasSmokeSignal(destWorld, destBase.get().pos())) {
                    int baseRadius = destBase.get().radius() > 0
                            ? destBase.get().radius() : BotHomeService.DEFAULT_BASE_PROTECTION_RADIUS;
                    double maxRange = baseRadius * 2.0;
                    double distToBase = bot.getBlockPos().getManhattanDistance(destBase.get().pos());
                    if (distToBase <= maxRange) {
                        return TravelTierResult.allowed(3.0, "smoke-signal-underground");
                    }
                }
            }
        }

        if (hasMapAndCompass && !hasLight) {
            return TravelTierResult.refused("underground-no-light");
        }
        return TravelTierResult.refused("underground-no-artifact");
    }

    // ── Bot-to-bot artifact teleport (summon home) ─────────────

    /** Distance threshold for bot-to-bot artifact teleport (same as sunset fast travel). */
    private static final double BOT_SUMMON_RANGE_SQ = 96.0D * 96.0D;
    /** Receiver must be within this range of the destination to qualify as "at base". */
    private static final double RECEIVER_AT_DEST_RANGE_SQ = 32.0D * 32.0D;

    /**
     * Attempt to teleport a traveler bot to a destination by having a "receiver" bot
     * already near that destination summon it via tier-2 artifact access.
     *
     * <p>Requirements:
     * <ul>
     *   <li>Traveler is &gt;96 blocks from destination</li>
     *   <li>A same-owner bot ("receiver") exists within 32 blocks of destination</li>
     *   <li>Receiver has tier-2 artifact access (Eye of Ender, Wizard's Tome,
     *       Enchanting Table nearby, or both bots hold Ender Pearls)</li>
     *   <li>Traveler is not in combat</li>
     * </ul>
     *
     * @param server      the Minecraft server
     * @param traveler    the bot that wants to get home
     * @param destination the target position (home/base)
     * @return true if the teleport was initiated
     */
    public static boolean tryBotToBotArtifactTeleport(MinecraftServer server,
                                                       ServerPlayerEntity traveler,
                                                       BlockPos destination) {
        if (server == null || traveler == null || destination == null) {
            return false;
        }
        if (isTraveling(traveler.getUuid())) {
            return false;
        }
        if (BotCombatCalloutService.isInCombat(traveler.getUuid())) {
            return false;
        }

        // Distance gate: traveler must be far enough from destination.
        double distSq = traveler.getBlockPos().getSquaredDistance(destination);
        if (distSq <= BOT_SUMMON_RANGE_SQ) {
            return false;
        }

        UUID travelerOwner = BotTerritoryAuthorizationService.resolveBotOwnerUuid(traveler);
        if (travelerOwner == null) {
            return false;
        }

        // Find a receiver bot near the destination with tier-2 artifact access.
        ServerPlayerEntity receiver = null;
        for (ServerPlayerEntity candidate : BotEventHandler.getRegisteredBots(server)) {
            if (candidate == null || candidate == traveler || candidate.isRemoved()) {
                continue;
            }
            if (isTraveling(candidate.getUuid())) {
                continue;
            }
            // Same owner check.
            UUID candidateOwner = BotTerritoryAuthorizationService.resolveBotOwnerUuid(candidate);
            if (!travelerOwner.equals(candidateOwner)) {
                continue;
            }
            // Receiver must be near the destination.
            if (candidate.getBlockPos().getSquaredDistance(destination) > RECEIVER_AT_DEST_RANGE_SQ) {
                continue;
            }
            // Receiver must have tier-2 artifact access.
            if (!hasReceiverTier2Access(candidate, traveler)) {
                continue;
            }
            receiver = candidate;
            break;
        }

        if (receiver == null) {
            return false;
        }

        // Calculate delay using receiver's artifact multiplier (1.0x for tier-2).
        double distance = Math.sqrt(distSq);
        ServerWorld travelerWorld = (ServerWorld) traveler.getEntityWorld();
        boolean crossDim = !travelerWorld.getRegistryKey().equals(
                ((ServerWorld) receiver.getEntityWorld()).getRegistryKey());
        int delayTicks = calculateDelayTicks(distance, crossDim, 1.0);

        UUID ownerUuid = travelerOwner;
        String travelerAlias = traveler.getName().getString();
        String receiverAlias = receiver.getName().getString();

        boolean started = beginDelayedTravel(server, traveler, travelerAlias, destination,
                ((ServerWorld) receiver.getEntityWorld()).getRegistryKey(), delayTicks, ownerUuid);

        if (started) {
            LOGGER.info("Bot-to-bot summon: {} is summoning {} home via artifact (dist={}, delay={}t)",
                    receiverAlias, travelerAlias, (int) distance, delayTicks);
            notifyOwner(server, ownerUuid,
                    "\u00A7d" + receiverAlias + " is summoning " + travelerAlias
                    + " home via artifact (ETA ~" + Math.max(1, delayTicks / 20) + "s).\u00A7r");
        }
        return started;
    }

    /**
     * Check whether a receiver bot has tier-2 artifact access for summoning.
     * Matches the tier-2 checks in {@link #artifactDelayMultiplier}.
     */
    private static boolean hasReceiverTier2Access(ServerPlayerEntity receiver,
                                                   ServerPlayerEntity traveler) {
        if (hasArtifact(receiver, net.minecraft.item.Items.ENDER_EYE)) return true;
        if (CompanionCommunicationPolicy.hasWizardTome(receiver)) return true;
        if (isNearBlock(receiver, net.minecraft.block.Blocks.ENCHANTING_TABLE, 6)) return true;
        // Dual ender pearls: both receiver and traveler must hold one.
        if (hasArtifact(receiver, net.minecraft.item.Items.ENDER_PEARL)
                && hasArtifact(traveler, net.minecraft.item.Items.ENDER_PEARL)) {
            return true;
        }
        return false;
    }

    // ── Fast travel system ────────────────────────────────────

    /** Tracks a bot that is currently in transit (removed from world, awaiting respawn). */
    public record PendingTravel(UUID botUuid, String botAlias, BlockPos destination,
                                RegistryKey<World> dimension, long departureTick, long arrivalTick,
                                UUID ownerUuid, String mountEntityTypeId, double travelDistance,
                                boolean magicTravel) {}

    /** Bots currently in transit, keyed by bot UUID. */
    private static final Map<UUID, PendingTravel> PENDING_TRAVELS = new ConcurrentHashMap<>();

    /** Retry counts for failed respawn attempts, keyed by bot UUID. Max 3 retries. */
    private static final Map<UUID, Integer> RESPAWN_RETRY_COUNTS = new ConcurrentHashMap<>();
    private static final int MAX_RESPAWN_RETRIES = 3;

    /** Messages queued for owners who were offline when the notification was sent. */
    private static final Map<UUID, List<String>> QUEUED_NOTIFICATIONS = new ConcurrentHashMap<>();

    /** Actions to perform after a bot arrives at its fast travel destination (e.g., withdraw from chest). */
    public record PostArrivalAction(String type, BlockPos target, UUID ownerUuid, Vec3d returnPos) {}
    private static final Map<String, PostArrivalAction> PENDING_POST_ARRIVAL = new ConcurrentHashMap<>();

    /** Schedule a post-arrival action for a bot (keyed by lowercase alias). */
    public static void schedulePostArrival(String botAlias, PostArrivalAction action) {
        if (botAlias != null && action != null) {
            PENDING_POST_ARRIVAL.put(botAlias.toLowerCase(java.util.Locale.ROOT), action);
        }
    }

    /** Post-spawn setup waiting for the bot entity to appear in the player manager. */
    private record PostSpawnSetup(String botAlias, ServerWorld world, Vec3d spawnPos, BlockPos dest,
                                  RegistryKey<World> dim, PendingTravel travel,
                                  boolean dimensionFallback, int ticksWaited) {}
    private static final Map<String, PostSpawnSetup> PENDING_POST_SPAWN = new ConcurrentHashMap<>();

    /** Check if a bot is currently in transit. */
    public static boolean isTraveling(UUID botUuid) {
        return botUuid != null && PENDING_TRAVELS.containsKey(botUuid);
    }

    /** Get the pending travel record for a bot, or null if not traveling. */
    public static PendingTravel getPendingTravel(UUID botUuid) {
        return botUuid != null ? PENDING_TRAVELS.get(botUuid) : null;
    }

    /** Find a pending travel by bot name (case-insensitive). Returns null if not traveling. */
    public static PendingTravel getPendingTravelByName(String botName) {
        if (botName == null) return null;
        String lower = botName.toLowerCase(java.util.Locale.ROOT);
        for (PendingTravel t : PENDING_TRAVELS.values()) {
            if (t.botAlias() != null && t.botAlias().toLowerCase(java.util.Locale.ROOT).equals(lower)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Cancel a pending fast-travel, removing the bot from the travel queue.
     * Call this before force-spawning a bot that is mid-travel.
     *
     * @return the canceled travel record, or null if no travel was pending.
     */
    public static PendingTravel cancelTravel(UUID botUuid) {
        if (botUuid == null) return null;
        PendingTravel removed = PENDING_TRAVELS.remove(botUuid);
        if (removed != null) {
            RESPAWN_RETRY_COUNTS.remove(botUuid);
            PENDING_POST_ARRIVAL.remove(removed.botAlias().toLowerCase(java.util.Locale.ROOT));
            PENDING_POST_SPAWN.remove(removed.botAlias().toLowerCase(java.util.Locale.ROOT));
            flushPendingTravels();
            LOGGER.info("Canceled pending travel for '{}' to {}", removed.botAlias(), removed.destination().toShortString());
        }
        return removed;
    }

    /**
     * Begin fast travel for a bot. The bot is removed from the world and will be
     * respawned at the destination after {@code delayTicks} have elapsed.
     *
     * @param server      the Minecraft server
     * @param bot         the fake player bot to send traveling
     * @param botAlias    the bot's display name / alias
     * @param destination the target block position
     * @param dimension   the target dimension
     * @param delayTicks  how many ticks until arrival
     * @param ownerUuid   UUID of the player who owns this bot (for notifications)
     */
    public static boolean beginDelayedTravel(MinecraftServer server, ServerPlayerEntity bot,
                                             String botAlias, BlockPos destination,
                                             RegistryKey<World> dimension, int delayTicks,
                                             UUID ownerUuid) {
        return beginDelayedTravel(server, bot, botAlias, destination, dimension, delayTicks, ownerUuid, false, false, false, false);
    }

    /**
     * Emergency travel path used by autonomous rescue logic.
     * Reuses the same delayed-travel pipeline but skips the normal underground gating.
     */
    public static boolean beginEmergencyTravel(MinecraftServer server, ServerPlayerEntity bot,
                                               String botAlias, BlockPos destination,
                                               RegistryKey<World> dimension, int delayTicks,
                                               UUID ownerUuid) {
        return beginDelayedTravel(server, bot, botAlias, destination, dimension, delayTicks, ownerUuid, true, true, false, false);
    }

    /**
     * Base-bypass travel used by sunrise resume. Skips the underground artifact gate
     * (the bot is returning to a known base) but keeps other gates active.
     */
    public static boolean beginBaseBypassTravel(MinecraftServer server, ServerPlayerEntity bot,
                                                 String botAlias, BlockPos destination,
                                                 RegistryKey<World> dimension, UUID ownerUuid) {
        if (server == null || bot == null || destination == null || dimension == null) return false;
        double distance = bot.getBlockPos().getManhattanDistance(destination);
        boolean crossDim = !((ServerWorld) bot.getEntityWorld()).getRegistryKey().equals(dimension);
        int delayTicks = calculateDelayTicks(distance, crossDim, 3.0);
        return beginDelayedTravel(server, bot, botAlias, destination, dimension, delayTicks, ownerUuid, false, false, true, false);
    }

    /**
     * Magic travel path: spells that consume reagents (ender pearls, chorus fruit)
     * bypass food requirements entirely. The reagent cost IS the price.
     * Hunger drain on arrival is also skipped.
     */
    public static boolean beginMagicTravel(MinecraftServer server, ServerPlayerEntity bot,
                                           String botAlias, BlockPos destination,
                                           RegistryKey<World> dimension, int delayTicks,
                                           UUID ownerUuid) {
        return beginDelayedTravel(server, bot, botAlias, destination, dimension, delayTicks, ownerUuid, false, false, false, true);
    }

    /**
     * Coordinated emergency travel for two bots. Used by autonomous escort rescue so both
     * bots enter traveling on the same tick and the rescue service can own HUD messaging.
     */
    public static boolean beginCoordinatedEmergencyTravel(MinecraftServer server,
                                                          ServerPlayerEntity primaryBot,
                                                          String primaryAlias,
                                                          BlockPos primaryDestination,
                                                          RegistryKey<World> primaryDimension,
                                                          ServerPlayerEntity secondaryBot,
                                                          String secondaryAlias,
                                                          BlockPos secondaryDestination,
                                                          RegistryKey<World> secondaryDimension,
                                                          int delayTicks,
                                                          UUID ownerUuid) {
        if (server == null || primaryBot == null || secondaryBot == null
                || primaryDestination == null || secondaryDestination == null
                || primaryDimension == null || secondaryDimension == null) {
            return false;
        }
        if (primaryBot.hasVehicle() || secondaryBot.hasVehicle()) {
            return false;
        }
        if (!canBeginDelayedTravel(server, primaryBot, primaryDestination, primaryDimension, ownerUuid, true)
                || !canBeginDelayedTravel(server, secondaryBot, secondaryDestination, secondaryDimension, ownerUuid, true)) {
            return false;
        }
        boolean primaryStarted = beginDelayedTravel(server, primaryBot, primaryAlias, primaryDestination, primaryDimension,
                delayTicks, ownerUuid, true, true, false, false);
        if (!primaryStarted) {
            return false;
        }
        boolean secondaryStarted = beginDelayedTravel(server, secondaryBot, secondaryAlias, secondaryDestination, secondaryDimension,
                delayTicks, ownerUuid, true, true, false, false);
        return secondaryStarted;
    }

    /**
     * Extract usable food from containers (bundles, shulker boxes) into the bot's
     * main inventory so the fast-travel food budget can account for it.
     * Only extracts what is needed for the journey. Must be called on the server thread.
     */
    private static void extractFoodFromContainers(ServerPlayerEntity bot, int neededNutrition) {
        if (neededNutrition <= 0) return;

        record FoodCandidate(int invSlot, int containerIndex, double score, int nutrition,
                             boolean isBundle) {}

        List<FoodCandidate> candidates = new java.util.ArrayList<>();

        for (int slot = 0; slot < bot.getInventory().size(); slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;

            // Scan bundles
            var bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle != null) {
                int idx = 0;
                for (ItemStack bundled : bundle.iterate()) {
                    if (HealingService.isTravelUsableFood(bundled)) {
                        FoodComponent food = bundled.getComponents().get(DataComponentTypes.FOOD);
                        if (food != null) {
                            double score = food.nutrition() + (food.saturation() * 2.0);
                            candidates.add(new FoodCandidate(slot, idx, score,
                                    food.nutrition() * bundled.getCount(), true));
                        }
                    }
                    idx++;
                }
            }

            // Scan shulker boxes (items with CONTAINER component on a ShulkerBoxBlock item)
            var container = stack.get(DataComponentTypes.CONTAINER);
            if (container != null && stack.getItem() instanceof net.minecraft.item.BlockItem blockItem
                    && blockItem.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) {
                int idx = 0;
                for (ItemStack contained : container.iterateNonEmpty()) {
                    if (HealingService.isTravelUsableFood(contained)) {
                        FoodComponent food = contained.getComponents().get(DataComponentTypes.FOOD);
                        if (food != null) {
                            double score = food.nutrition() + (food.saturation() * 2.0);
                            candidates.add(new FoodCandidate(slot, idx, score,
                                    food.nutrition() * contained.getCount(), false));
                        }
                    }
                    idx++;
                }
            }
        }

        if (candidates.isEmpty()) return;

        // Sort cheapest first
        candidates.sort(java.util.Comparator.comparingDouble(FoodCandidate::score));

        int extracted = 0;
        for (FoodCandidate c : candidates) {
            if (extracted >= neededNutrition) break;

            ItemStack containerStack = bot.getInventory().getStack(c.invSlot);
            if (containerStack.isEmpty()) continue;

            ItemStack foodToMove;
            if (c.isBundle) {
                var bundle = containerStack.get(DataComponentTypes.BUNDLE_CONTENTS);
                if (bundle == null) continue;

                // Collect bundle contents, remove the target item
                java.util.List<ItemStack> remaining = new java.util.ArrayList<>();
                int idx = 0;
                ItemStack target = ItemStack.EMPTY;
                for (ItemStack bundled : bundle.iterate()) {
                    if (idx == c.containerIndex && target.isEmpty()) {
                        target = bundled.copy();
                    } else {
                        remaining.add(bundled.copy());
                    }
                    idx++;
                }
                if (target.isEmpty()) continue;

                // Rebuild bundle without the extracted item
                var builder = new net.minecraft.component.type.BundleContentsComponent.Builder(
                        net.minecraft.component.type.BundleContentsComponent.DEFAULT);
                for (ItemStack item : remaining) {
                    builder.add(item);
                }
                containerStack.set(DataComponentTypes.BUNDLE_CONTENTS, builder.build());
                foodToMove = target;
            } else {
                // Shulker box extraction
                var container = containerStack.get(DataComponentTypes.CONTAINER);
                if (container == null) continue;

                java.util.List<ItemStack> slots = new java.util.ArrayList<>();
                container.streamNonEmpty().forEach(s -> slots.add(s.copy()));
                if (c.containerIndex >= slots.size()) continue;

                foodToMove = slots.remove(c.containerIndex);

                // Rebuild container component
                containerStack.set(DataComponentTypes.CONTAINER,
                        net.minecraft.component.type.ContainerComponent.fromStacks(slots));
            }

            // Place in main inventory
            if (!bot.getInventory().insertStack(foodToMove)) {
                LOGGER.debug("Cannot extract food from container: inventory full");
                break;
            }

            extracted += c.nutrition;
            LOGGER.info("Extracted food from container in slot {} for fast-travel provisioning", c.invSlot);
        }
    }

    private static boolean beginDelayedTravel(MinecraftServer server, ServerPlayerEntity bot,
                                              String botAlias, BlockPos destination,
                                              RegistryKey<World> dimension, int delayTicks,
                                              UUID ownerUuid, boolean skipGates, boolean suppressOwnerNotify,
                                              boolean skipArtifactGate, boolean magicTravel) {
        if (server == null || bot == null || botAlias == null || destination == null || dimension == null) {
            LOGGER.warn("beginDelayedTravel called with null arguments; ignoring.");
            return false;
        }

        double travelDistance = bot.getBlockPos().getManhattanDistance(destination);

        if (!skipGates) {
            // ── Combat gate ──────────────────────────────────────────────
            if (BotCombatCalloutService.isInCombat(bot.getUuid())) {
                notifyOwner(server, ownerUuid,
                        "\u00A7c" + botAlias + " cannot fast-travel while in combat.\u00A7r");
                return false;
            }

            // ── Cooldown gate ────────────────────────────────────────────
            long now = server.getOverworld().getTime();
            Long lastDeparture = TRAVEL_COOLDOWNS.get(bot.getUuid());
            if (lastDeparture != null) {
                long elapsed = now - lastDeparture;
                if (elapsed < TRAVEL_COOLDOWN_TICKS) {
                    long remainingTicks = TRAVEL_COOLDOWN_TICKS - elapsed;
                    int remainingSec = Math.max(1, (int) (remainingTicks / 20));
                    String timeStr = remainingSec >= 60
                            ? (remainingSec / 60) + "m " + (remainingSec % 60) + "s"
                            : remainingSec + "s";
                    notifyOwner(server, ownerUuid,
                            "\u00A7c" + botAlias + " needs to rest before traveling again (" + timeStr + " remaining).\u00A7r");
                    return false;
                }
            }

            // ── Food safety gate ─────────────────────────────────────────
            // Magic travel (spells with reagent cost) bypasses food requirements entirely.
            if (!magicTravel) {
                // Extract food from containers (bundles, shulker boxes) if main inventory
                // doesn't have enough for the journey.
                double estHungerCost = travelDistance / HUNGER_DISTANCE_DIVISOR;
                int estNeeded = (int) Math.ceil(estHungerCost) + MIN_POST_TRAVEL_FOOD;
                int mainFood = bot.getHungerManager().getFoodLevel();
                for (int i = 0; i < bot.getInventory().size(); i++) {
                    ItemStack s = bot.getInventory().getStack(i);
                    if (HealingService.isTravelUsableFood(s)) {
                        FoodComponent f = s.getComponents().get(DataComponentTypes.FOOD);
                        if (f != null) mainFood += f.nutrition() * s.getCount();
                    }
                }
                if (mainFood < estNeeded) {
                    extractFoodFromContainers(bot, estNeeded - mainFood);
                }

                // Budget = current food level + nutrition from all usable food items in inventory.
                // The bot could eat before/during travel, so inventory food counts toward the budget.
                double hungerCost = travelDistance / HUNGER_DISTANCE_DIVISOR;
                int currentFood = bot.getHungerManager().getFoodLevel();
                int inventoryNutrition = 0;
                for (int i = 0; i < bot.getInventory().size(); i++) {
                    ItemStack stack = bot.getInventory().getStack(i);
                    if (HealingService.isTravelUsableFood(stack)) {
                        FoodComponent food = stack.getComponents().get(DataComponentTypes.FOOD);
                        inventoryNutrition += food.nutrition() * stack.getCount();
                    }
                }
                int totalBudget = currentFood + inventoryNutrition;
                int projectedFood = totalBudget - (int) Math.ceil(hungerCost);
                if (projectedFood < MIN_POST_TRAVEL_FOOD) {
                    int shortfall = (int) Math.ceil(hungerCost) + MIN_POST_TRAVEL_FOOD - totalBudget;
                    int steakEstimate = (int) Math.ceil(shortfall / 8.0);
                    notifyOwner(server, ownerUuid,
                            "\u00A7e" + botAlias + " needs provisions for this journey \u2014 roughly "
                            + steakEstimate + " cooked steak worth of food (~"
                            + shortfall + " hunger points). Pack extra before sending them off.\u00A7r");
                    return false;
                }
            }

        }

        // ── Travel-tier resolver (surface or underground) ───────────────
        // Replaces the old underground gate + above-ground smoke extension.
        // Refuses when no gate is open; otherwise multiplies the delay based on
        // which artifacts the bot has access to (via ArtifactScanner — scans
        // main inventory, bundles, shulker boxes, and the bot's ender chest).
        String tierReasonForMessage = null;
        double tierMultForMessage = 1.0;
        if (!skipGates && !skipArtifactGate) {
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerUuid);
            ServerWorld currentWorld = (ServerWorld) bot.getEntityWorld();

            boolean isUnderground = false;
            if (!currentWorld.isSkyVisible(bot.getBlockPos().up())) {
                int surfaceY = SafePositionService.getWalkableGroundY(currentWorld, bot.getBlockX(), bot.getBlockZ());
                if (bot.getBlockPos().getY() < surfaceY - 4) {
                    isUnderground = true;
                }
            }

            TravelTierResult tier = isUnderground
                    ? resolveUndergroundTier(server, bot, owner, destination, dimension)
                    : resolveSurfaceTier(server, bot, owner, destination, dimension);

            if (!tier.allowed()) {
                String msg = switch (tier.reason()) {
                    case "no-surface-gate" ->
                            botAlias + " needs a Map + Compass (with the destination explored) or a smoke signal at the destination base to fast-travel here.";
                    case "underground-no-light" ->
                            botAlias + " can't read the map underground without a torch or lantern.";
                    case "underground-no-artifact" ->
                            botAlias + " can't fast-travel underground without a Map + Compass (and a light), a Lodestone Compass, or a smoke signal at the destination base.";
                    default ->
                            botAlias + " can't fast-travel right now (" + tier.reason() + ").";
                };
                notifyOwner(server, ownerUuid, "\u00A7c" + msg + "\u00A7r");
                return false;
            }

            if (tier.delayMultiplier() > 1.0) {
                boolean crossDim = !currentWorld.getRegistryKey().equals(dimension);
                delayTicks = calculateDelayTicks(travelDistance, crossDim, tier.delayMultiplier());
            }

            LOGGER.info("Fast-travel tier: bot={} underground={} reason={} mult={}",
                    botAlias, isUnderground, tier.reason(), tier.delayMultiplier());

            tierReasonForMessage = tier.reason();
            tierMultForMessage = tier.delayMultiplier();
        }

        // ── Mount evaluation ──────────────────────────────────────────────
        ServerWorld destWorld = server.getWorld(dimension);
        TravelMountHandler.MountTravelResult mountResult =
                TravelMountHandler.evaluateTravel(bot, destination, dimension, destWorld);

        String mountEntityTypeId = null;
        switch (mountResult.decision()) {
            case REFUSE_FULL_INVENTORY, REFUSE_NO_ROOM_AT_DEST, REFUSE_CROSS_DIM_ANIMAL -> {
                notifyOwner(server, ownerUuid, "\u00A7c" + mountResult.message() + "\u00A7r");
                return false; // Abort travel
            }
            case TETHERED_CROSS_DIM -> {
                notifyOwner(server, ownerUuid, "\u00A7e" + mountResult.message() + "\u00A7r");
                // Fall through — travel proceeds without animal
            }
            case PROCEED_WITH_ANIMAL -> {
                // Record the entity type so we can recreate it at the destination
                Entity mount = mountResult.mountEntity();
                if (mount != null) {
                    bot.stopRiding();
                    Identifier typeId = EntityType.getId(mount.getType());
                    mountEntityTypeId = typeId != null ? typeId.toString() : null;
                    TravelMountHandler.ensureMountPersistence(mount);
                    mount.discard();
                    LOGGER.info("Mount '{}' ({}) will be recreated at destination for bot '{}'",
                            mount.getName().getString(), mountEntityTypeId, botAlias);
                }
            }
            default -> {} // PROCEED_NO_MOUNT, PROCEED_VEHICLE_COLLECTED — nothing extra
        }

        UUID botUuid = bot.getUuid();
        long now = server.getOverworld().getTime();
        long arrival = now + delayTicks;

        PendingTravel travel = new PendingTravel(botUuid, botAlias, destination, dimension,
                now, arrival, ownerUuid, mountEntityTypeId, travelDistance, magicTravel);
        PENDING_TRAVELS.put(botUuid, travel);

        // Record cooldown timestamp for this bot.
        if (!skipGates) {
            TRAVEL_COOLDOWNS.put(botUuid, now);
        }

        // Set mode to TRAVELING so other systems ignore this bot.
        BotCommandStateService.State state = BotCommandStateService.stateFor(botUuid);
        if (state != null) {
            state.mode = BotEventHandler.Mode.TRAVELING;
        }

        // Pre-write destination as the bot's saved world-state position. This is a safety
        // net: if onBotJoin's isTraveling guard doesn't fire, the bot restores to the
        // destination rather than the departure point.
        BotWorldStateService.saveStateManual(server, botAlias,
                destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5, 0, 0);

        // Close any open inventory screens for this bot to prevent item duplication.
        // If a player has the bot's inventory open when it departs, they could continue
        // manipulating the stale copy while the bot respawns with the persisted original.
        try {
            for (ServerPlayerEntity viewer : server.getPlayerManager().getPlayerList()) {
                if (viewer != null && !viewer.isRemoved()
                        && viewer.currentScreenHandler instanceof net.wcfcarolina13.ui.BotPlayerInventoryScreenHandler handler
                        && handler.getBotRef() == bot) {
                    viewer.closeHandledScreen();
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to close inventory screens for departing bot '{}': {}", botAlias, t.getMessage());
        }

        // Persist bot state (inventory, position) before removal so it survives the trip.
        // Save explicitly, then disconnect the bot cleanly.
        try {
            BotPersistenceService.onBotDisconnect(bot);
        } catch (Throwable t) {
            LOGGER.warn("Failed to persist bot '{}' before travel: {}", botAlias, t.getMessage());
        }

        // Remove from player manager FIRST (sends PlayerRemoveS2CPacket — clears tab list),
        // THEN discard the entity (sets removal reason to DISCARDED, which triggers ServerWorld
        // to remove the entity from chunk tracking and broadcast EntitiesDestroyS2CPacket to all
        // clients, making the bot visually disappear).
        // Note: kill() on createFakePlayer is a no-op (FakeClientConnection.handleDisconnection
        // does nothing), so we must use discard() to actually remove the entity from the world.
        try {
            BotPersistenceService.removeFromPlayerManager(server, bot);
        } catch (Throwable t) {
            LOGGER.warn("Failed to remove bot '{}' from player manager: {}", botAlias, t.getMessage());
        }
        try {
            bot.discard();
        } catch (Throwable t) {
            LOGGER.warn("Failed to discard bot '{}' for travel: {}", botAlias, t.getMessage());
        }

        int delaySeconds = delayTicks / 20;
        LOGGER.info("Bot '{}' departed for {} in {} (ETA: {}s, {} ticks)",
                botAlias, destination.toShortString(),
                dimension.getValue(), delaySeconds, delayTicks);

        // Notify the owner that the bot has departed (queues if offline). Include
        // the fast-travel tier reason so the player can see why fast-travel was
        // possible — otherwise the same chat line fires regardless of which gate
        // opened, and players (including the dev) can't tell whether it was the
        // lodestone compass, a map+compass, a smoke signal, or something else.
        if (!suppressOwnerNotify) {
            String tierSuffix = formatTierSuffix(tierReasonForMessage, tierMultForMessage);
            notifyOwner(server, ownerUuid,
                    "\u00A7e" + botAlias + " has departed" + tierSuffix + " and will arrive in ~"
                    + delaySeconds + " seconds.\u00A7r");
        }

        flushPendingTravels();
        return true;
    }

    private static boolean canBeginDelayedTravel(MinecraftServer server,
                                                 ServerPlayerEntity bot,
                                                 BlockPos destination,
                                                 RegistryKey<World> dimension,
                                                 UUID ownerUuid,
                                                 boolean skipGates) {
        if (server == null || bot == null || destination == null || dimension == null) {
            return false;
        }
        if (!skipGates) {
            if (BotCombatCalloutService.isInCombat(bot.getUuid())) {
                return false;
            }
            ServerWorld currentWorld = (ServerWorld) bot.getEntityWorld();
            if (!currentWorld.isSkyVisible(bot.getBlockPos().up())) {
                int surfaceY = SafePositionService.getWalkableGroundY(currentWorld, bot.getBlockX(), bot.getBlockZ());
                boolean nearSurface = bot.getBlockPos().getY() >= surfaceY - 4;
                if (!nearSurface) {
                    ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerUuid);
                    double mult = artifactDelayMultiplier(bot, owner);
                    if (mult > 1.0
                            && !(hasArtifact(bot, net.minecraft.item.Items.FILLED_MAP)
                            && hasArtifact(bot, net.minecraft.item.Items.COMPASS))
                            && !LodestoneCompassService.hasAnyLodestoneCompass(bot)) {
                        return false;
                    }
                }
            }
        }
        ServerWorld destWorld = server.getWorld(dimension);
        TravelMountHandler.MountTravelResult mountResult =
                TravelMountHandler.evaluateTravel(bot, destination, dimension, destWorld);
        return switch (mountResult.decision()) {
            case REFUSE_FULL_INVENTORY, REFUSE_NO_ROOM_AT_DEST, REFUSE_CROSS_DIM_ANIMAL -> false;
            default -> true;
        };
    }

    /**
     * Called every server tick. Checks pending travels and respawns bots whose arrival time
     * has been reached.
     */
    public static void tickPendingTravels(MinecraftServer server) {
        // Process post-spawn setups (waiting for bot entity to appear after createFake).
        if (!PENDING_POST_SPAWN.isEmpty()) {
            Iterator<Map.Entry<String, PostSpawnSetup>> psIt = PENDING_POST_SPAWN.entrySet().iterator();
            while (psIt.hasNext()) {
                Map.Entry<String, PostSpawnSetup> psEntry = psIt.next();
                PostSpawnSetup ps = psEntry.getValue();
                // Find the bot by name — must be alive (not the old killed entity).
                ServerPlayerEntity bot = null;
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p != null && !p.isRemoved() && ps.botAlias().equalsIgnoreCase(p.getName().getString())) {
                        bot = p;
                        break;
                    }
                }
                if (bot != null) {
                    LOGGER.info("Bot '{}' appeared after {} real ticks — completing travel setup.",
                            ps.botAlias(), ps.ticksWaited());
                    completePostSpawnSetup(server, bot, ps);
                    psIt.remove();
                } else if (ps.ticksWaited() >= 60) {
                    // Log all entities with matching name for diagnostics.
                    ServerPlayerEntity stale = server.getPlayerManager().getPlayer(ps.botAlias());
                    LOGGER.error("Bot '{}' failed to appear after 60 real ticks. " +
                            "getPlayer(name) returned: {} (removed={})",
                            ps.botAlias(), stale, stale != null ? stale.isRemoved() : "n/a");
                    psIt.remove();
                } else {
                    int nextTick = ps.ticksWaited() + 1;
                    if (nextTick % 10 == 0) {
                        // Count matching players in full list for diagnostics.
                        long matching = server.getPlayerManager().getPlayerList().stream()
                                .filter(p -> ps.botAlias().equalsIgnoreCase(p.getName().getString()))
                                .count();
                        long alive = server.getPlayerManager().getPlayerList().stream()
                                .filter(p -> ps.botAlias().equalsIgnoreCase(p.getName().getString()) && !p.isRemoved())
                                .count();
                        LOGGER.info("Post-spawn poll tick {}: bot='{}' matching={} alive={}",
                                nextTick, ps.botAlias(), matching, alive);
                    }
                    psEntry.setValue(new PostSpawnSetup(ps.botAlias(), ps.world(), ps.spawnPos(),
                            ps.dest(), ps.dim(), ps.travel(), ps.dimensionFallback(),
                            nextTick));
                }
            }
        }

        if (PENDING_TRAVELS.isEmpty()) {
            return;
        }

        long now = server.getOverworld().getTime();
        Iterator<Map.Entry<UUID, PendingTravel>> it = PENDING_TRAVELS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingTravel> entry = it.next();
            PendingTravel travel = entry.getValue();
            if (now >= travel.arrivalTick()) {
                try {
                    respawnBotAtDestination(server, travel);
                    // Success — remove from map and clean up retry state.
                    it.remove();
                    RESPAWN_RETRY_COUNTS.remove(travel.botUuid());
                    flushPendingTravels();
                } catch (Throwable t) {
                    int retries = RESPAWN_RETRY_COUNTS.merge(travel.botUuid(), 1, Integer::sum);
                    if (retries > MAX_RESPAWN_RETRIES) {
                        LOGGER.error("Permanently failed to respawn bot '{}' after {} retries: {}",
                                travel.botAlias(), retries, t.getMessage(), t);
                        it.remove();
                        RESPAWN_RETRY_COUNTS.remove(travel.botUuid());
                        flushPendingTravels();
                        // Notify owner of permanent failure.
                        notifyOwner(server, travel.ownerUuid(),
                                "\u00A7cTravel failed: your companion " + travel.botAlias()
                                + " could not be respawned at the destination.\u00A7r");
                    } else {
                        LOGGER.warn("Respawn attempt {}/{} failed for bot '{}': {} (will retry next tick)",
                                retries, MAX_RESPAWN_RETRIES, travel.botAlias(), t.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Respawn a bot at its travel destination. Creates a new fake player entity at the
     * destination coordinates, restores persisted state (inventory, etc.), registers
     * the bot with mod systems, and notifies the owner.
     */
    private static void respawnBotAtDestination(MinecraftServer server, PendingTravel travel) {
        final boolean dimensionFallback;
        final ServerWorld finalWorld;
        final BlockPos finalDest;
        final RegistryKey<World> finalDim;

        ServerWorld targetWorld = server.getWorld(travel.dimension());
        if (targetWorld == null) {
            LOGGER.warn("Target dimension {} unloaded for bot '{}'; falling back to Overworld spawn.",
                    travel.dimension().getValue(), travel.botAlias());
            targetWorld = server.getOverworld();
            finalWorld = targetWorld;
            net.minecraft.world.WorldProperties.SpawnPoint sp = targetWorld.getSpawnPoint();
            finalDest = (sp != null && sp.getPos() != null) ? sp.getPos() : BlockPos.ORIGIN;
            finalDim = World.OVERWORLD;
            dimensionFallback = true;
        } else {
            finalWorld = targetWorld;
            finalDest = travel.destination();
            finalDim = travel.dimension();
            dimensionFallback = false;
        }
        // Ensure spawn position is not inside a solid block (e.g. lodestone, enchanting table).
        // If the destination block is solid, find the nearest safe adjacent position.
        BlockPos safeDest = finalDest;
        if (!finalWorld.getBlockState(finalDest).getCollisionShape(finalWorld, finalDest).isEmpty()
                || !finalWorld.getBlockState(finalDest.up()).getCollisionShape(finalWorld, finalDest.up()).isEmpty()) {
            BlockPos adjusted = SafePositionService.findSafeNear(finalWorld, finalDest, 3);
            if (adjusted != null) {
                safeDest = adjusted;
                LOGGER.info("Fast-travel destination {} is inside a solid block; adjusted to {}",
                        finalDest.toShortString(), safeDest.toShortString());
            }
        }
        final Vec3d spawnPos = Vec3d.ofBottomCenter(safeDest);

        LOGGER.info("Respawning bot '{}' at {} in {}",
                travel.botAlias(), safeDest.toShortString(), finalDim.getValue());

        // Explicitly remove any existing entity with the same name before creating the
        // new one.  FakeClientConnection.handleDisconnection() is a no-op, so the vanilla
        // duplicate-login disconnect inside PlayerManager.onPlayerConnect() never fires
        // the onDisconnected() callback → the old entity stays in the player list.
        // Without this cleanup, two entities coexist: commands that iterate getPlayerList()
        // (BotTargetingService) pick the stale first entry while UUID-keyed lookups
        // (getRegisteredBots) return the correct newer entity — causing position desync.
        for (ServerPlayerEntity existing : server.getPlayerManager().getPlayerList()) {
            if (existing instanceof createFakePlayer
                    && travel.botAlias().equalsIgnoreCase(existing.getName().getString())) {
                LOGGER.info("Removing stale entity for '{}' (pos={}) before travel respawn",
                        travel.botAlias(), existing.getBlockPos().toShortString());
                BotPersistenceService.removeFromPlayerManager(server, existing);
                existing.discard();
                break;
            }
        }

        // Create the fake player at the destination.
        // createFake handles GameProfile resolution, skin, connection, and teleport.
        createFakePlayer.createFake(
                travel.botAlias(),
                server,
                spawnPos,
                0.0,   // yaw — will face north; owner can adjust
                0.0,   // pitch
                finalDim,
                GameMode.SURVIVAL,
                false   // not flying
        );

        // Enqueue post-spawn setup — processed in tickPendingTravels() which runs
        // once per real server tick, giving createFake time to fully register the entity.
        PENDING_POST_SPAWN.put(travel.botAlias().toLowerCase(java.util.Locale.ROOT),
                new PostSpawnSetup(travel.botAlias(), finalWorld, spawnPos, finalDest,
                        finalDim, travel, dimensionFallback, 0));
    }

    private static void completePostSpawnSetup(MinecraftServer server, ServerPlayerEntity bot, PostSpawnSetup ps) {
        // Teleport to exact destination (onBotJoin skips position restore for traveling bots).
        double dx = ps.spawnPos().x, dy = ps.spawnPos().y, dz = ps.spawnPos().z;
        bot.teleport(ps.world(), dx, dy, dz,
                java.util.Set.of(), 0.0F, 0.0F, true);
        // Force position + send position packet to ALL clients in the dimension.
        bot.setPosition(dx, dy, dz);
        bot.setVelocity(Vec3d.ZERO);

        // ── Hunger drain proportional to travel distance ─────────────
        // Direct food/saturation set — drain food level FIRST (visible on HUD), then saturation.
        // This ensures the player sees the hunger bar drop on arrival.
        // Magic travel (spell-based) skips hunger drain — reagent cost is the price.
        double dist = ps.travel().travelDistance();
        if (dist > 0 && !ps.travel().magicTravel()) {
            double hungerCost = dist / HUNGER_DISTANCE_DIVISOR;
            int foodBefore = bot.getHungerManager().getFoodLevel();
            float satBefore = bot.getHungerManager().getSaturationLevel();

            // Drain food level first (visible on hunger bar)
            int foodDrain = (int) Math.min(foodBefore, Math.ceil(hungerCost));
            double remaining = hungerCost - foodDrain;
            bot.getHungerManager().setFoodLevel(foodBefore - foodDrain);

            // Drain saturation for any remaining fractional cost
            if (remaining > 0) {
                float satDrain = (float) Math.min(satBefore, remaining);
                bot.getHungerManager().setSaturationLevel(satBefore - satDrain);
            }

            LOGGER.info("Applied travel hunger drain to '{}': distance={}, hungerCost={}, food={}→{}, sat={}→{}",
                    ps.botAlias(), (int) dist, String.format("%.1f", hungerCost),
                    foodBefore, bot.getHungerManager().getFoodLevel(),
                    String.format("%.1f", satBefore), String.format("%.1f", bot.getHungerManager().getSaturationLevel()));
        }
        // Broadcast entity position to all players in the dimension (same approach as createFake).
        EntityPosition ep = EntityPosition.fromEntity(bot).withRotation(0.0F, 0.0F);
        server.getPlayerManager().sendToDimension(
                new net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket(
                        bot.getId(), ep, java.util.Set.of(), bot.isOnGround()),
                ps.dim());

        try { BotEventHandler.registerBot(bot); }
        catch (Throwable t) { LOGGER.warn("Failed to register bot '{}' after travel: {}", ps.botAlias(), t.getMessage()); }

        // Seed teleport detector so the spawn-to-destination jump isn't flagged as an external teleport.
        BotEventHandler.notifyTravelArrival(bot.getUuid(), new Vec3d(dx, dy, dz), (long) server.getTicks());

        try { AutoFaceEntity.startAutoFace(bot); }
        catch (Throwable t) { LOGGER.debug("AutoFaceEntity start failed for '{}': {}", ps.botAlias(), t.getMessage()); }

        BotCommandStateService.State cmdState = BotCommandStateService.stateFor(bot);
        if (cmdState != null) { cmdState.mode = BotEventHandler.Mode.IDLE; }

        // Recreate co-traveling mount if one was stored.
        PendingTravel travel = ps.travel();
        if (travel.mountEntityTypeId() != null) {
            try {
                Identifier typeId = Identifier.of(travel.mountEntityTypeId());
                if (Registries.ENTITY_TYPE.containsId(typeId)) {
                    EntityType<?> mountType = Registries.ENTITY_TYPE.get(typeId);
                    Entity mount = mountType.create(ps.world(), SpawnReason.COMMAND);
                    if (mount != null) {
                        BlockPos safeSpot = TravelMountHandler.findSafeAnimalSpot(ps.world(), ps.dest(), mount);
                        BlockPos mountPos = safeSpot != null ? safeSpot : ps.dest();
                        mount.refreshPositionAndAngles(mountPos.getX() + 0.5, mountPos.getY(), mountPos.getZ() + 0.5, 0, 0);
                        ps.world().spawnEntity(mount);
                        TravelMountHandler.ensureMountPersistence(mount);
                        MountPersistenceService.recordMount(bot, mount, true);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to recreate mount for '{}': {}", ps.botAlias(), e.getMessage());
            }
        }

        ps.world().playSound(null, ps.dest(), SoundEvents.ENTITY_ENDER_PEARL_THROW, SoundCategory.PLAYERS, 1.0F, 0.8F);

        String msg = ps.dimensionFallback()
                ? "\u00A7eYour companion " + ps.botAlias() + " arrived at Overworld spawn (target dimension was unavailable).\u00A7r"
                : "\u00A7aYour companion " + ps.botAlias() + " has arrived at the destination.\u00A7r";
        notifyOwner(server, travel.ownerUuid(), msg);

        LOGGER.info("Bot '{}' arrived at {} in {}.", ps.botAlias(), ps.dest().toShortString(), ps.dim().getValue());

        // Process post-arrival action (e.g., withdraw from chest, then return).
        String key = ps.botAlias().toLowerCase(java.util.Locale.ROOT);
        PostArrivalAction action = PENDING_POST_ARRIVAL.remove(key);
        if (action != null && action.type() != null && action.type().startsWith("skill_resume:")) {
            String command = action.type().substring("skill_resume:".length());
            server.execute(() -> {
                ServerPlayerEntity arrBot = server.getPlayerManager().getPlayer(ps.botAlias());
                if (arrBot != null && !arrBot.isRemoved()) {
                    LOGGER.info("Post-arrival skill resume for '{}': {}", ps.botAlias(), command);
                    try {
                        server.getCommandManager().getDispatcher().execute(command,
                                server.getCommandSource().withSilent());
                    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                        LOGGER.warn("Post-arrival skill resume dispatch failed for '{}': {}", ps.botAlias(), e.getMessage());
                    }
                }
            });
        } else if (action != null && action.type() != null && action.type().startsWith("withdraw")) {
            // Parse return mode: "withdraw:stay", "withdraw:player", "withdraw:home"
            String returnMode = "stay";
            if (action.type().contains(":")) {
                returnMode = action.type().substring(action.type().indexOf(':') + 1);
            }
            final String finalReturnMode = returnMode;

            // Schedule withdrawal on next tick (bot needs to be fully in world first).
            server.execute(() -> {
                ServerPlayerEntity arrBot = server.getPlayerManager().getPlayer(ps.botAlias());
                if (arrBot == null || arrBot.isRemoved()) return;
                BlockPos chestPos = action.target();
                ServerWorld w = (ServerWorld) arrBot.getEntityWorld();
                var be = w.getBlockEntity(chestPos);
                if (be instanceof net.minecraft.inventory.Inventory storage) {
                    int moved = 0;
                    for (int i = 0; i < storage.size() && moved < 256; i++) {
                        net.minecraft.item.ItemStack stack = storage.getStack(i);
                        if (stack == null || stack.isEmpty()) continue;
                        net.minecraft.item.ItemStack copy = stack.copy();
                        if (arrBot.getInventory().insertStack(copy)) {
                            int taken = stack.getCount() - copy.getCount();
                            stack.decrement(taken);
                            moved += taken;
                        }
                    }
                    storage.markDirty();
                    BotChestRegistryService.updateContentsSnapshot(arrBot, chestPos, w, storage);
                    LOGGER.info("Post-arrival: {} withdrew {} items from chest at {} (return={})",
                            ps.botAlias(), moved, chestPos.toShortString(), finalReturnMode);

                    notifyOwner(server, action.ownerUuid(),
                            "\u00A7a" + ps.botAlias() + " collected " + moved + " items from the chest.\u00A7r");

                    // Handle return trip.
                    handlePostCollectReturn(server, arrBot, ps.botAlias(), finalReturnMode, action);
                } else {
                    notifyOwner(server, action.ownerUuid(),
                            "\u00A7e" + ps.botAlias() + " arrived but no chest found at " + chestPos.toShortString() + ".\u00A7r");
                }
            });
        }
    }

    /** After collecting, fast-travel back to the requested destination. */
    private static void handlePostCollectReturn(MinecraftServer server, ServerPlayerEntity bot,
                                                 String botAlias, String returnMode, PostArrivalAction action) {
        if ("stay".equals(returnMode) || returnMode == null) return;

        BlockPos returnDest = null;
        String returnLabel = null;

        if ("player".equals(returnMode)) {
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(action.ownerUuid());
            if (owner != null && !owner.isRemoved()) {
                returnDest = owner.getBlockPos();
                returnLabel = "player";
            }
        } else if ("home".equals(returnMode)) {
            // Use the "home" base label.
            ServerWorld w = (ServerWorld) bot.getEntityWorld();
            var homeOpt = BotHomeService.getBaseByLabel(server, w, "home");
            if (homeOpt.isPresent()) {
                returnDest = homeOpt.get();
                returnLabel = "home base";
            }
        }

        if (returnDest == null) {
            // Fall back to origin position stored in the action.
            if (action.returnPos() != null) {
                returnDest = BlockPos.ofFloored(action.returnPos());
                returnLabel = "origin";
            }
        }

        if (returnDest != null) {
            double dist = bot.getBlockPos().getManhattanDistance(returnDest);
            int delayTicks = calculateDelayTicks(dist, false);
            LOGGER.info("Post-collect return: {} -> {} ({}, dist={}, ETA={}s)",
                    botAlias, returnDest.toShortString(), returnLabel, (int) dist, delayTicks / 20);
            beginDelayedTravel(server, bot, botAlias, returnDest,
                    ((ServerWorld) bot.getEntityWorld()).getRegistryKey(), delayTicks, action.ownerUuid(),
                    true /* skipGates: return trip after collection */, false, false, false);
            notifyOwner(server, action.ownerUuid(),
                    "\u00A7e" + botAlias + " is returning to " + returnLabel + " (ETA ~" + Math.max(1, delayTicks / 20) + "s).\u00A7r");
        }
    }

    // ── Session lifecycle ──────────────────────────────────────────────────

    /**
     * Flush pending travels to disk, then clear all in-memory state.
     * Called from {@code Frens.java} SERVER_STOPPED to prevent stale state
     * leaking across integrated-server world reloads.
     */
    public static void resetSession() {
        flushPendingTravels();
        PENDING_TRAVELS.clear();
        RESPAWN_RETRY_COUNTS.clear();
        QUEUED_NOTIFICATIONS.clear();
        PENDING_POST_SPAWN.clear();
        PENDING_POST_ARRIVAL.clear();
        TRAVEL_COOLDOWNS.clear();
    }

    // ── Persistence ────────────────────────────────────────────────────────

    /**
     * Write the current {@link #PENDING_TRAVELS} map to JSON so in-flight travels
     * survive a server restart.
     */
    public static void flushPendingTravels() {
        synchronized (LOCK) {
            try {
                Path file = travelFile();
                Files.createDirectories(file.getParent());

                List<SavedTravel> list = new ArrayList<>();
                for (PendingTravel t : PENDING_TRAVELS.values()) {
                    SavedTravel s = new SavedTravel();
                    s.botUuid = t.botUuid().toString();
                    s.botAlias = t.botAlias();
                    s.destX = t.destination().getX();
                    s.destY = t.destination().getY();
                    s.destZ = t.destination().getZ();
                    s.dimension = t.dimension().getValue().toString();
                    s.departureTick = t.departureTick();
                    s.arrivalTick = t.arrivalTick();
                    s.ownerUuid = t.ownerUuid() != null ? t.ownerUuid().toString() : null;
                    s.mountEntityTypeId = t.mountEntityTypeId();
                    s.travelDistance = t.travelDistance();
                    s.magicTravel = t.magicTravel();
                    list.add(s);
                }

                try (Writer writer = Files.newBufferedWriter(file)) {
                    GSON.toJson(list, writer);
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to save pending travels: {}", e.getMessage());
            }
        }
    }

    /**
     * Reload pending travels from JSON after a server restart. Rebases arrival ticks
     * to current server time, preserving the remaining travel duration.
     *
     * @param server the freshly started server
     */
    public static void loadPendingTravels(MinecraftServer server) {
        synchronized (LOCK) {
            Path file = travelFile();
            if (!Files.exists(file)) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(file)) {
                Type listType = new TypeToken<List<SavedTravel>>() {}.getType();
                List<SavedTravel> list = GSON.fromJson(reader, listType);
                if (list == null || list.isEmpty()) {
                    return;
                }

                long now = server.getOverworld().getTime();

                for (SavedTravel s : list) {
                    try {
                        UUID botUuid = UUID.fromString(s.botUuid);
                        UUID ownerUuid = s.ownerUuid != null ? UUID.fromString(s.ownerUuid) : null;
                        BlockPos dest = new BlockPos(s.destX, s.destY, s.destZ);
                        RegistryKey<World> dim = RegistryKey.of(RegistryKeys.WORLD,
                                Identifier.of(s.dimension));

                        // Rebase: preserve the remaining travel duration from the
                        // original schedule. On restart we don't know wall-clock elapsed
                        // time, so we use the full planned duration as remaining ticks.
                        long remainingTicks = Math.max(0, s.arrivalTick - s.departureTick);
                        long newArrival = now + remainingTicks;

                        PendingTravel travel = new PendingTravel(botUuid, s.botAlias, dest, dim,
                                now, newArrival, ownerUuid, s.mountEntityTypeId, s.travelDistance,
                                s.magicTravel);
                        PENDING_TRAVELS.put(botUuid, travel);

                        LOGGER.info("Restored pending travel for '{}': destination {} in {}, ETA {} ticks",
                                s.botAlias, dest.toShortString(), s.dimension, remainingTicks);
                    } catch (Exception e) {
                        LOGGER.warn("Skipping malformed saved travel entry for '{}': {}",
                                s.botAlias, e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to load pending travels: {}", e.getMessage());
            }
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /** Send a message to a player if online, otherwise queue it for delivery on next login. */
    private static void notifyOwner(MinecraftServer server, UUID ownerUuid, String message) {
        if (server == null || ownerUuid == null) return;
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerUuid);
        if (owner != null) {
            owner.sendMessage(Text.literal(message), false);
        } else {
            QUEUED_NOTIFICATIONS.computeIfAbsent(ownerUuid, k -> new ArrayList<>()).add(message);
        }
    }

    /**
     * Send all queued travel notifications to a player who just joined.
     * Call from {@code Frens.java} ServerPlayConnectionEvents.JOIN handler.
     */
    public static void drainQueuedNotifications(ServerPlayerEntity player) {
        if (player == null) return;
        List<String> messages = QUEUED_NOTIFICATIONS.remove(player.getUuid());
        if (messages == null || messages.isEmpty()) return;
        for (String msg : messages) {
            player.sendMessage(Text.literal(msg), false);
        }
    }

    private static boolean hasItemInInventory(ServerPlayerEntity player, net.minecraft.item.Item item) {
        if (player == null) return false;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack != null && !stack.isEmpty() && stack.isOf(item)) return true;
        }
        return false;
    }
}
