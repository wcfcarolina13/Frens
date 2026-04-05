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
import java.util.Map;
import java.util.UUID;

/**
 * Persistent fishing session tracking for multi-day fishing.
 * When fishing is paused at sunset, the session state is saved here so it can be
 * resumed at sunrise via SkillResumeService.
 * Persisted to config/frens/fishing_sessions.json so sessions survive server restarts.
 */
public final class FishingSessionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("fishing-session");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "fishing_sessions.json";
    private static final Object LOCK = new Object();
    private static final long EXPIRY_MS = 24L * 60 * 60 * 1000; // 24 hours

    private static Map<String, SessionData> DATA = new HashMap<>();
    private static boolean loaded = false;

    private FishingSessionService() {}

    // ── Data model ──────────────────────────────────────────────────────

    public record FishingSession(
            UUID botId,
            BlockPos standPos,
            BlockPos waterPos,
            BlockPos castTarget,
            int fishCaught,
            int targetFish,
            String rawArgs
    ) {}

    /** JSON-serializable wrapper since records with BlockPos don't serialize cleanly. */
    private static final class SessionData {
        String botId;
        int standX, standY, standZ;
        int waterX, waterY, waterZ;
        int castX, castY, castZ;
        boolean hasCastTarget;
        int fishCaught;
        int targetFish;
        String rawArgs;
        long savedAtMs;

        SessionData() {}

        static SessionData from(FishingSession s) {
            SessionData d = new SessionData();
            d.botId = s.botId.toString();
            d.standX = s.standPos != null ? s.standPos.getX() : 0;
            d.standY = s.standPos != null ? s.standPos.getY() : 64;
            d.standZ = s.standPos != null ? s.standPos.getZ() : 0;
            d.waterX = s.waterPos != null ? s.waterPos.getX() : 0;
            d.waterY = s.waterPos != null ? s.waterPos.getY() : 64;
            d.waterZ = s.waterPos != null ? s.waterPos.getZ() : 0;
            d.hasCastTarget = s.castTarget != null;
            d.castX = s.castTarget != null ? s.castTarget.getX() : 0;
            d.castY = s.castTarget != null ? s.castTarget.getY() : 0;
            d.castZ = s.castTarget != null ? s.castTarget.getZ() : 0;
            d.fishCaught = s.fishCaught;
            d.targetFish = s.targetFish;
            d.rawArgs = s.rawArgs;
            d.savedAtMs = System.currentTimeMillis();
            return d;
        }

        FishingSession toSession() {
            UUID id;
            try { id = UUID.fromString(botId); }
            catch (Exception e) { return null; }
            BlockPos stand = new BlockPos(standX, standY, standZ);
            BlockPos water = new BlockPos(waterX, waterY, waterZ);
            BlockPos cast = hasCastTarget ? new BlockPos(castX, castY, castZ) : null;
            return new FishingSession(id, stand, water, cast, fishCaught, targetFish, rawArgs);
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
                    LOGGER.warn("Failed to load fishing sessions: {}", e.getMessage());
                    DATA = new HashMap<>();
                }
            }
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
            LOGGER.warn("Failed to save fishing sessions: {}", e.getMessage());
        }
    }

    // ── Public API ──────────────────────────────────────────────────────

    public static void saveSession(ServerPlayerEntity bot, BlockPos stand, BlockPos water,
                                    BlockPos castTarget, int fishCaught, int targetFish,
                                    String rawArgs) {
        if (bot == null) return;
        FishingSession session = new FishingSession(
                bot.getUuid(), stand, water, castTarget,
                fishCaught, targetFish, rawArgs);
        ensureLoaded();
        synchronized (LOCK) {
            DATA.put(bot.getUuid().toString(), SessionData.from(session));
        }
        flush();
        LOGGER.info("Fishing session saved for {}: caught={}/{} stand={}",
                bot.getName().getString(), fishCaught, targetFish,
                stand != null ? stand.toShortString() : "null");
    }

    public static FishingSession getSession(UUID botId) {
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

    public static FishingSession consumeSession(UUID botId) {
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
                LOGGER.info("Fishing session cleared for {}", botId);
            }
        }
    }
}
