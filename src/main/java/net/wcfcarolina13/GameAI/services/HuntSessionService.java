package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent hunt session tracking for multi-day hunts.
 * When a hunt is paused at sunset, the session state is saved here so it can be
 * resumed at sunrise via SkillResumeService.
 * Persisted to config/frens/hunt_sessions.json so sessions survive server restarts.
 */
public final class HuntSessionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("hunt-session");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "hunt_sessions.json";
    private static final Object LOCK = new Object();
    private static final long EXPIRY_MS = 24L * 60 * 60 * 1000; // 24 hours

    private static Map<String, SessionData> DATA = new HashMap<>();
    private static boolean loaded = false;

    private HuntSessionService() {}

    // ── Data model ──────────────────────────────────────────────────────

    public record HuntSession(
            UUID botId,
            BlockPos huntOrigin,
            BlockPos chestPos,
            List<String> targetIds,
            int killsCompleted,
            int killsTarget,
            String zoneName,
            boolean depopulationEnabled,
            String rawArgs
    ) {}

    /** JSON-serializable wrapper since records with BlockPos don't serialize cleanly. */
    private static final class SessionData {
        String botId;
        int originX, originY, originZ;
        int chestX, chestY, chestZ;
        boolean hasChest;
        List<String> targetIds;
        int killsCompleted;
        int killsTarget;
        String zoneName;
        boolean depopulationEnabled;
        String rawArgs;
        long savedAtMs;

        SessionData() {}

        static SessionData from(HuntSession s) {
            SessionData d = new SessionData();
            d.botId = s.botId.toString();
            d.originX = s.huntOrigin != null ? s.huntOrigin.getX() : 0;
            d.originY = s.huntOrigin != null ? s.huntOrigin.getY() : 64;
            d.originZ = s.huntOrigin != null ? s.huntOrigin.getZ() : 0;
            d.hasChest = s.chestPos != null;
            d.chestX = s.chestPos != null ? s.chestPos.getX() : 0;
            d.chestY = s.chestPos != null ? s.chestPos.getY() : 0;
            d.chestZ = s.chestPos != null ? s.chestPos.getZ() : 0;
            d.targetIds = s.targetIds != null ? List.copyOf(s.targetIds) : List.of();
            d.killsCompleted = s.killsCompleted;
            d.killsTarget = s.killsTarget;
            d.zoneName = s.zoneName;
            d.depopulationEnabled = s.depopulationEnabled;
            d.rawArgs = s.rawArgs;
            d.savedAtMs = System.currentTimeMillis();
            return d;
        }

        HuntSession toSession() {
            UUID id;
            try { id = UUID.fromString(botId); }
            catch (Exception e) { return null; }
            BlockPos origin = new BlockPos(originX, originY, originZ);
            BlockPos chest = hasChest ? new BlockPos(chestX, chestY, chestZ) : null;
            return new HuntSession(id, origin, chest, targetIds != null ? targetIds : List.of(),
                    killsCompleted, killsTarget, zoneName, depopulationEnabled, rawArgs);
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private static Path stateFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("frens").resolve(FILE_NAME);
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (loaded) return;
            Path file = stateFile();
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    @SuppressWarnings("unchecked")
                    Map<String, SessionData> parsed = GSON.fromJson(reader,
                            new com.google.gson.reflect.TypeToken<Map<String, SessionData>>() {}.getType());
                    if (parsed != null) DATA = parsed;
                } catch (Exception e) {
                    LOGGER.warn("Failed to load hunt sessions: {}", e.getMessage());
                    DATA = new HashMap<>();
                }
            }
            // Expire stale sessions on load
            long now = System.currentTimeMillis();
            DATA.entrySet().removeIf(e -> now - e.getValue().savedAtMs > EXPIRY_MS);
            loaded = true;
        }
    }

    private static void flush() {
        Path file = stateFile();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(DATA, writer);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to save hunt sessions: {}", e.getMessage());
        }
    }

    // ── Public API ──────────────────────────────────────────────────────

    public static void saveSession(ServerPlayerEntity bot, BlockPos huntOrigin, BlockPos chestPos,
                                    List<String> targetIds, int killsCompleted, int killsTarget,
                                    String zoneName, boolean depopulationEnabled, String rawArgs) {
        if (bot == null) return;
        HuntSession session = new HuntSession(
                bot.getUuid(), huntOrigin, chestPos, targetIds,
                killsCompleted, killsTarget, zoneName, depopulationEnabled, rawArgs);
        ensureLoaded();
        synchronized (LOCK) {
            DATA.put(bot.getUuid().toString(), SessionData.from(session));
        }
        flush();
        LOGGER.info("Hunt session saved for {}: kills={}/{} origin={}",
                bot.getName().getString(), killsCompleted, killsTarget, huntOrigin);
    }

    public static HuntSession getSession(UUID botId) {
        if (botId == null) return null;
        ensureLoaded();
        synchronized (LOCK) {
            SessionData d = DATA.get(botId.toString());
            if (d == null) return null;
            if (System.currentTimeMillis() - d.savedAtMs > EXPIRY_MS) {
                DATA.remove(botId.toString());
                flush();
                return null;
            }
            return d.toSession();
        }
    }

    public static HuntSession consumeSession(UUID botId) {
        if (botId == null) return null;
        ensureLoaded();
        synchronized (LOCK) {
            SessionData d = DATA.remove(botId.toString());
            if (d == null) return null;
            flush();
            if (System.currentTimeMillis() - d.savedAtMs > EXPIRY_MS) return null;
            return d.toSession();
        }
    }

    public static boolean hasSession(UUID botId) {
        if (botId == null) return false;
        ensureLoaded();
        synchronized (LOCK) {
            SessionData d = DATA.get(botId.toString());
            if (d == null) return false;
            if (System.currentTimeMillis() - d.savedAtMs > EXPIRY_MS) {
                DATA.remove(botId.toString());
                flush();
                return false;
            }
            return true;
        }
    }

    public static void clearSession(UUID botId) {
        if (botId == null) return;
        ensureLoaded();
        synchronized (LOCK) {
            if (DATA.remove(botId.toString()) != null) {
                flush();
                LOGGER.info("Hunt session cleared for {}", botId);
            }
        }
    }
}
