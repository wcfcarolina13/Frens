package net.shasankp000.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.Heightmap;
import net.shasankp000.AIPlayer;
import net.shasankp000.FilingSystem.ManualConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Server-authoritative questline that turns "recruited" into "permanent companion" via village improvements.
 *
 * <p>Key design constraints:
 * <ul>
 *   <li>Seed-agnostic: uses only local environment checks (no structure start lookup).</li>
 *   <li>Bounded: checks are done on demand (topic click), not every tick.</li>
 *   <li>Per-world: progression is stored in {@link ManualConfig.SurvivalRecruitmentState}.</li>
 * </ul>
 */
public final class SurvivalCompanionQuestService {

    // Block scan bounds around the anchor.
    private static final int SCAN_RADIUS_XZ = 24;
    private static final int SCAN_RADIUS_Y = 8;

    // Light sampling (surface samples, block-light only).
    private static final int LIGHT_SAMPLE_RADIUS = 16;
    private static final int LIGHT_SAMPLE_STEP = 4;
    private static final int LIGHT_OK_THRESHOLD = 8;
    private static final double LIGHT_REQUIRED_RATIO = 0.70D;

    // Villager scan bounds (wider than block scan).
    private static final double VILLAGER_RADIUS_XZ = 48.0D;
    private static final double VILLAGER_RADIUS_Y = 16.0D;

    // Perimeter heuristic.
    private static final int PERIMETER_REQUIRED_COUNT = 24;

    // Anti-spam + caching. These checks involve bounded block scans; keep them responsive.
    private static final long REQUEST_COOLDOWN_TICKS = 10L; // 0.5s
    private static final long SCAN_CACHE_TTL_TICKS = 20L;   // 1s
    private static final java.util.Map<java.util.UUID, Long> LAST_REQUEST_TICK = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, CachedScan> SCAN_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedScan(long tick, Progress progress) {
    }

    private SurvivalCompanionQuestService() {
    }

    public record Response(String linesJoined, int stage, boolean permanent) {
        public static Response ofLines(List<String> lines, int stage, boolean permanent) {
            if (lines == null || lines.isEmpty()) {
                return new Response("", stage, permanent);
            }
            return new Response(String.join("\n", lines), stage, permanent);
        }
    }

    private record Progress(
            int beds,
            int storage,
            boolean bell,
            int perimeter,
            int perimeterRing,
            int villagers,
            double lightRatio
    ) {
    }

    public static Response handleTopic(ServerPlayerEntity player, String botAlias, String topicKey) {
        MinecraftServer server = (player != null && !player.isRemoved()) ? player.getCommandSource().getServer() : null;
        if (player == null || player.isRemoved() || server == null) {
            return Response.ofLines(List.of("..."), 0, false);
        }

        long nowTick = server.getTicks();

        if (!SurvivalRecruitmentService.isEnabled(server)) {
            return Response.ofLines(List.of("This world isn't using survival recruitment."), 0, false);
        }

        ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);
        if (st == null || !st.isRecruited()) {
            return Response.ofLines(List.of("We haven't even done the recruitment yet."), 0, false);
        }

        // Best-effort: only answer for the recruited bot (prevents weirdness if multiple bots exist).
        String recruitedAlias = st.getBotAlias();
        if (botAlias != null && !botAlias.isBlank() && recruitedAlias != null
                && !recruitedAlias.equalsIgnoreCase(botAlias.trim())) {
            return Response.ofLines(List.of("I'm not the one you recruited here."), st.getCompanionQuestStage(), st.isPermanentCompanion());
        }

        // Resolve anchor world/pos.
        Anchor anchor = resolveAnchor(server, player, st);
        if (anchor.world == null || anchor.pos == null) {
            return Response.ofLines(List.of("I can't get my bearings here."), st.getCompanionQuestStage(), st.isPermanentCompanion());
        }

        boolean canAdvance = isAuthorizedAdvancer(player, st);

        int stage = st.getCompanionQuestStage();
        boolean permanent = st.isPermanentCompanion();

        String k = topicKey == null ? "" : topicKey.trim().toLowerCase(Locale.ROOT);

        // Anchor changes should be explicit and authorized.
        if (k.equals("companion_anchor_set")) {
            if (!isAuthorizedAdvancer(player, st)) {
                return Response.ofLines(List.of("This isn't your call to make."), st.getCompanionQuestStage(), st.isPermanentCompanion());
            }
            st.setCompanionAnchorSet(true);
            st.setCompanionAnchorDimension(player.getCommandSource().getWorld().getRegistryKey().getValue().toString());
            st.setCompanionAnchorPos(player.getBlockPos().asLong());

            // Moving the anchor changes what "the settlement" means; reset progress unless we're already permanent.
            if (!st.isPermanentCompanion()) {
                st.setCompanionQuestStage(0);
            }

            trySaveConfig();
            BlockPos p = player.getBlockPos();
            return Response.ofLines(List.of(
                    "Alright.",
                    "This is the place you want to build?",
                    "Then this is where I'll judge it.",
                    "(Anchor set to " + p.getX() + "," + p.getY() + "," + p.getZ() + ")"),
                    st.getCompanionQuestStage(),
                    st.isPermanentCompanion());
        }

        // Heavy topics: cooldown + cached scan.
        boolean heavy = k.equals("companion_status")
                || k.equals("companion_check")
                || k.equals("village_missing")
                || k.equals("village_projects");
        if (heavy) {
            Long last = LAST_REQUEST_TICK.get(player.getUuid());
            if (last != null && (nowTick - last) < REQUEST_COOLDOWN_TICKS) {
                return Response.ofLines(List.of("Give me a second."), st.getCompanionQuestStage(), st.isPermanentCompanion());
            }
            LAST_REQUEST_TICK.put(player.getUuid(), nowTick);
        }

        Progress progress = heavy ? scanCached(anchor.world, anchor.pos, nowTick) : scan(anchor.world, anchor.pos);

        if (permanent || stage >= 4) {
            st.setPermanentCompanion(true);
            st.setCompanionQuestStage(Math.max(stage, 4));
            trySaveConfig();
            return Response.ofLines(List.of(
                    "This place has a pulse now.",
                    "I won't vanish when it gets hard.",
                    "Call it what you want — I'm staying."), st.getCompanionQuestStage(), true);
        }

        return switch (k) {
            case "companion_status" -> Response.ofLines(buildStatusLines(st, anchor, progress), stage, permanent);
            case "village_missing" -> Response.ofLines(buildMissingLines(stage, progress), stage, permanent);
            case "village_projects", "companion_check" -> {
                if (!canAdvance) {
                    yield Response.ofLines(List.of("This isn't your call to make."), stage, permanent);
                }
                int newStage = tryAdvanceStage(st, progress);
                boolean nowPermanent = st.isPermanentCompanion();
                List<String> out = new ArrayList<>();
                if (newStage != stage) {
                    out.add("Good. That's one more reason to stay.");
                    out.addAll(buildStageIntroLines(newStage));
                } else {
                    out.addAll(buildMissingLines(stage, progress));
                }
                yield Response.ofLines(out, st.getCompanionQuestStage(), nowPermanent);
            }
            default -> Response.ofLines(List.of("..."), stage, permanent);
        };
    }

    private static final class Anchor {
        final ServerWorld world;
        final BlockPos pos;

        private Anchor(ServerWorld world, BlockPos pos) {
            this.world = world;
            this.pos = pos;
        }
    }

    private static Anchor resolveAnchor(MinecraftServer server, ServerPlayerEntity player, ManualConfig.SurvivalRecruitmentState st) {
        ServerWorld world = null;
        BlockPos pos = null;

        if (st != null && st.isCompanionAnchorSet()) {
            world = resolveWorld(server, st.getCompanionAnchorDimension());
            pos = BlockPos.fromLong(st.getCompanionAnchorPos());
        }

        if (world == null) {
            world = player.getCommandSource().getWorld();
        }
        if (pos == null) {
            pos = player.getBlockPos();
        }

        // Backfill anchor for older worlds.
        if (st != null && !st.isCompanionAnchorSet()) {
            st.setCompanionAnchorSet(true);
            st.setCompanionAnchorDimension(world.getRegistryKey().getValue().toString());
            st.setCompanionAnchorPos(pos.asLong());
            trySaveConfig();
        }

        return new Anchor(world, pos);
    }

    private static ServerWorld resolveWorld(MinecraftServer server, String dimId) {
        if (server == null || dimId == null || dimId.isBlank()) {
            return null;
        }
        Identifier id = Identifier.tryParse(dimId.trim());
        if (id == null) {
            return null;
        }
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, id);
        return server.getWorld(key);
    }

    private static boolean isAuthorizedAdvancer(ServerPlayerEntity player, ManualConfig.SurvivalRecruitmentState st) {
        if (player == null || st == null) {
            return false;
        }
        String recruiterUuid = st.getRecruitedByUuid();
        if (recruiterUuid != null && !recruiterUuid.isBlank() && recruiterUuid.equals(player.getUuidAsString())) {
            return true;
        }
        return AIPlayer.isOperator(player);
    }

    private static Progress scan(ServerWorld world, BlockPos anchor) {
        int bedBlocks = 0;
        int storage = 0;
        boolean bell = false;
        int perimeter = 0;
        int perimeterRing = 0;

        BlockPos.Mutable p = new BlockPos.Mutable();
        int ax = anchor.getX();
        int ay = anchor.getY();
        int az = anchor.getZ();

        // Ring band around the anchor: encourages actual perimeter building instead of dumping blocks in a pile.
        int ringCenter = 20;
        int ringHalfWidth = 3;
        int ringMinSq = (ringCenter - ringHalfWidth) * (ringCenter - ringHalfWidth);
        int ringMaxSq = (ringCenter + ringHalfWidth) * (ringCenter + ringHalfWidth);

        for (int dy = -SCAN_RADIUS_Y; dy <= SCAN_RADIUS_Y; dy++) {
            int y = ay + dy;
            for (int dx = -SCAN_RADIUS_XZ; dx <= SCAN_RADIUS_XZ; dx++) {
                int x = ax + dx;
                for (int dz = -SCAN_RADIUS_XZ; dz <= SCAN_RADIUS_XZ; dz++) {
                    int z = az + dz;
                    p.set(x, y, z);
                    BlockState s = world.getBlockState(p);
                    if (s == null) {
                        continue;
                    }
                    if (!bell && s.isOf(Blocks.BELL)) {
                        bell = true;
                    }
                    if (s.isIn(BlockTags.BEDS)) {
                        bedBlocks++;
                    }
                    if (s.isOf(Blocks.CHEST) || s.isOf(Blocks.TRAPPED_CHEST) || s.isOf(Blocks.BARREL)) {
                        storage++;
                    }
                    if (s.isIn(BlockTags.FENCES) || s.isIn(BlockTags.WALLS) || s.isIn(BlockTags.FENCE_GATES)) {
                        perimeter++;
                        int distSq = dx * dx + dz * dz;
                        if (distSq >= ringMinSq && distSq <= ringMaxSq) {
                            perimeterRing++;
                        }
                    }
                }
            }
        }

        int villagers = countVillagers(world, anchor);
        double lightRatio = computeLightRatio(world, anchor);

        int beds = Math.max(0, bedBlocks / 2); // beds are 2 blocks each.
        return new Progress(beds, storage, bell, perimeter, perimeterRing, villagers, lightRatio);
    }

    private static Progress scanCached(ServerWorld world, BlockPos anchor, long nowTick) {
        if (world == null || anchor == null) {
            return new Progress(0, 0, false, 0, 0, 0, 0.0D);
        }
        String key = world.getRegistryKey().getValue() + ":" + anchor.asLong();
        CachedScan cached = SCAN_CACHE.get(key);
        if (cached != null && (nowTick - cached.tick) <= SCAN_CACHE_TTL_TICKS) {
            return cached.progress;
        }
        Progress fresh = scan(world, anchor);
        SCAN_CACHE.put(key, new CachedScan(nowTick, fresh));
        return fresh;
    }

    private static int countVillagers(ServerWorld world, BlockPos anchor) {
        Box box = new Box(anchor).expand(VILLAGER_RADIUS_XZ, VILLAGER_RADIUS_Y, VILLAGER_RADIUS_XZ);
        return world.getEntitiesByClass(VillagerEntity.class, box, v -> v != null && v.isAlive()).size();
    }

    private static double computeLightRatio(ServerWorld world, BlockPos anchor) {
        int ok = 0;
        int total = 0;
        int ax = anchor.getX();
        int ay = anchor.getY();
        int az = anchor.getZ();

        for (int dx = -LIGHT_SAMPLE_RADIUS; dx <= LIGHT_SAMPLE_RADIUS; dx += LIGHT_SAMPLE_STEP) {
            for (int dz = -LIGHT_SAMPLE_RADIUS; dz <= LIGHT_SAMPLE_RADIUS; dz += LIGHT_SAMPLE_STEP) {
                int sx = ax + dx;
                int sz = az + dz;
                BlockPos feet = findWalkableSamplePos(world, sx, sz, ay);
                int light = world.getLightLevel(LightType.BLOCK, feet);
                if (light >= LIGHT_OK_THRESHOLD) {
                    ok++;
                }
                total++;
            }
        }

        if (total <= 0) {
            return 0.0D;
        }
        return ok / (double) total;
    }

    /**
     * Finds a nearby walkable sample position (air block with a solid block beneath) near the anchor Y.
     * This is more robust than pure heightmap sampling for villages with roofs/overhangs/interiors.
     */
    private static BlockPos findWalkableSamplePos(ServerWorld world, int x, int z, int anchorY) {
        if (world == null) {
            return new BlockPos(x, anchorY + 1, z);
        }

        int minY = anchorY - 4;
        int maxY = anchorY + 6;
        BlockPos.Mutable m = new BlockPos.Mutable();
        BlockPos.Mutable below = new BlockPos.Mutable();

        for (int y = maxY; y >= minY; y--) {
            m.set(x, y, z);
            below.set(x, y - 1, z);
            BlockState feet = world.getBlockState(m);
            if (feet == null || !feet.isAir()) {
                continue;
            }
            BlockState ground = world.getBlockState(below);
            if (ground != null && ground.isSolidBlock(world, below)) {
                return m.toImmutable();
            }
        }

        // Fallback: use heightmap position's air-above-solid when possible.
        BlockPos base = new BlockPos(x, anchorY, z);
        BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, base);
        return top.up();
    }

    private static List<String> buildStatusLines(ManualConfig.SurvivalRecruitmentState st, Anchor anchor, Progress p) {
        int stage = st.getCompanionQuestStage();
        List<String> out = new ArrayList<>();
        out.add("Settlement review:");
        out.add("- Beds nearby: " + p.beds);
        out.add("- Storage nearby: " + p.storage);
        out.add("- Bell: " + (p.bell ? "yes" : "no"));
        out.add("- Villagers nearby: " + p.villagers);
        out.add(String.format(java.util.Locale.ROOT, "- Lighting coverage: %.0f%%", p.lightRatio * 100.0D));
        out.add("- Perimeter blocks: " + p.perimeter + " (ring: " + p.perimeterRing + ")");
        out.add("Anchor: " + anchor.world.getRegistryKey().getValue() + " @ " + anchor.pos.getX() + "," + anchor.pos.getY() + "," + anchor.pos.getZ());
        out.add("Companion stage: " + stageName(stage));
        return out;
    }

    private static List<String> buildStageIntroLines(int stage) {
        return switch (stage) {
            case 1 -> List.of("Next: light the area so nights stop being a gamble.");
            case 2 -> List.of("Next: give this place a center — a bell, and people to answer it.");
            case 3 -> List.of("Next: build a perimeter. Make it hard to die here.");
            case 4 -> List.of("That's the last of it.");
            default -> List.of("Let's start with the basics.");
        };
    }

    private static List<String> buildMissingLines(int stage, Progress p) {
        List<String> out = new ArrayList<>();
        out.add("What we're missing:");

        switch (stage) {
            case 0 -> {
                if (p.beds < 2) {
                    out.add("- At least 2 beds near the anchor.");
                }
                if (p.storage < 1) {
                    out.add("- A chest or barrel for supplies.");
                }
                if (p.beds >= 2 && p.storage >= 1) {
                    out.add("- Nothing. Ask me to check progress.");
                }
            }
            case 1 -> {
                if (p.lightRatio < LIGHT_REQUIRED_RATIO) {
                    out.add(String.format(java.util.Locale.ROOT, "- More lighting (need %.0f%%+ coverage at night; currently %.0f%%).",
                            LIGHT_REQUIRED_RATIO * 100.0D, p.lightRatio * 100.0D));
                } else {
                    out.add("- Lighting looks good. Ask me to check progress.");
                }
            }
            case 2 -> {
                if (!p.bell) {
                    out.add("- A bell near the anchor.");
                }
                if (!(p.villagers >= 2 || p.beds >= 3)) {
                    out.add("- More life here (2+ villagers nearby or 3+ beds). ");
                }
                if (p.bell && (p.villagers >= 2 || p.beds >= 3)) {
                    out.add("- Meeting point is ready. Ask me to check progress.");
                }
            }
            case 3 -> {
                if (p.perimeterRing < PERIMETER_REQUIRED_COUNT) {
                    out.add("- A perimeter: fences/walls/gates (need " + PERIMETER_REQUIRED_COUNT + "+ blocks around the edge; currently " + p.perimeterRing + ").");
                } else {
                    out.add("- Perimeter looks solid. Ask me to check progress.");
                }
            }
            default -> out.add("- ...");
        }

        return out;
    }

    private static int tryAdvanceStage(ManualConfig.SurvivalRecruitmentState st, Progress p) {
        int stage = st.getCompanionQuestStage();
        boolean advanced = false;

        if (stage == 0 && p.beds >= 2 && p.storage >= 1) {
            stage = 1;
            advanced = true;
        }
        if (stage == 1 && p.lightRatio >= LIGHT_REQUIRED_RATIO) {
            stage = 2;
            advanced = true;
        }
        if (stage == 2 && p.bell && (p.villagers >= 2 || p.beds >= 3)) {
            stage = 3;
            advanced = true;
        }
        if (stage == 3 && p.perimeterRing >= PERIMETER_REQUIRED_COUNT) {
            stage = 4;
            st.setPermanentCompanion(true);
            enableAutoSpawnForPermanentCompanion(st);
            advanced = true;
        }

        if (advanced) {
            st.setCompanionQuestStage(stage);
            trySaveConfig();
        }

        return st.getCompanionQuestStage();
    }

    private static void enableAutoSpawnForPermanentCompanion(ManualConfig.SurvivalRecruitmentState st) {
        if (st == null || AIPlayer.CONFIG == null) {
            return;
        }
        String alias = st.getBotAlias();
        if (alias == null || alias.isBlank()) {
            return;
        }
        try {
            ManualConfig.BotControlSettings ctrl = AIPlayer.CONFIG.getOrCreateBotControl(alias);
            if (ctrl != null) {
                ctrl.setAutoSpawn(true);
                ctrl.setSpawnMode("play");
                AIPlayer.CONFIG.save();
            }
        } catch (Exception ignored) {
        }
    }

    private static String stageName(int stage) {
        return switch (stage) {
            case 0 -> "Shelter";
            case 1 -> "Lighting";
            case 2 -> "Meeting Point";
            case 3 -> "Perimeter";
            case 4 -> "Permanent";
            default -> "Stage " + stage;
        };
    }

    private static void trySaveConfig() {
        try {
            if (AIPlayer.CONFIG != null) {
                AIPlayer.CONFIG.save();
            }
        } catch (Exception ignored) {
        }
    }
}
