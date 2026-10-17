package net.wcfcarolina13.FilingSystem;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import net.wcfcarolina13.LauncherDetection.LauncherEnvironment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;

/**
 * Handles all mod configuration settings using a plain JSON file.
 * This class replaces the owo-lib config wrapper to provide manual control
 * over saving and loading, resolving race conditions and initialization issues.
 */
public class ManualConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("ManualConfig");
    private static final String FILE_NAME = "settings.json5";

    // Lazily-resolved file path. Deliberately NOT a static final field initializer: eager
    // resolution here calls into LauncherEnvironment -> FabricLoader.getInstance().getGameDir(),
    // which throws outside a real Fabric launch (e.g. class-loading ManualConfig for a plain
    // JUnit mock). Resolving on first real use (save()/load()) keeps behavior identical in the
    // running mod while letting the class load safely in a test JVM that never calls into it.
    private static volatile String filePath;

    private static String filePath() {
        String resolved = filePath;
        if (resolved == null) {
            synchronized (ManualConfig.class) {
                resolved = filePath;
                if (resolved == null) {
                    resolved = getFilePath();
                    filePath = resolved;
                }
            }
        }
        return resolved;
    }

    // --- Configuration fields (same as before) ---
    private String ollamaBaseUrl = System.getProperty("frens.ollamaHost", System.getProperty("aiplayer.ollamaHost", "http://127.0.0.1:11434"));
    private Map<String, String> botGameProfile = new HashMap<>();
    private Map<String, BotOwnership> botOwnership = new HashMap<>();
    private Map<String, Boolean> playerPreserveExpensiveGear = new HashMap<>();
    private Map<String, Boolean> playerAutoAcceptPreciousFoods = new HashMap<>();
    private Map<String, BotSpawn> botSpawnPoints = new HashMap<>();
    // Per-world bot spawn points: alias → worldKey → BotSpawn.
    private Map<String, Map<String, BotSpawn>> botSpawnPointsByWorld = new HashMap<>();
    private boolean textDialogueEnabled = true;
    private boolean voicedDialogueEnabled = true;
    // Muted voiced-dialogue categories (VoiceLineCategory ids). Empty = nothing muted.
    private List<String> mutedVoiceCategories = new ArrayList<>();
    // Muted text categories (VoiceLineCategory ids) — same semantics as the voice mask:
    // consulted only while textDialogueEnabled is ON; empty = all text shows.
    // (Replaced the short-lived inverse "keep-visible exceptions" model, which made the
    // two Adv menus behave as confusing duals of each other.)
    private List<String> mutedTextCategories = new ArrayList<>();
    private boolean gameplayTipsEnabled = true;
    private boolean idleHobbiesAnywhereEnabled = false;
    // Baritone-style finder is the default since 1.1.240 (Bradley, 2026-10-14).
    private boolean baritonePathfinderEnabled = true;
    // One-time 1.1.240 migration marker: configs written before 1.1.240 persisted the old
    // default (false). Must initialise to false — Gson keeps the initializer for fields the
    // file lacks, so an old file reads as "not migrated yet". New files are stamped true.
    private boolean baritoneDefaultMigrated = false;
    private boolean fortifyForcePlaceEnabled = false;
    private boolean verboseDiagnostics = false;
    /** Global override for teleportDuringSkills. When non-null, overrides all per-bot settings. */
    private Boolean globalTeleportDuringSkills = null;
    private int fortBufferRadius = 8;
    private int undergroundLingerMinutes = 3;
    private int undergroundProximityBlocks = 32;
    // Per-bot generated voices (2026-08-29): soul profile id → voice assignment. Overrides the
    // profile JSON's own "voice" block; absent/blank = the global soul voice settings.
    private Map<String, SoulVoiceAssignment> soulProfileVoices = new HashMap<>();
    private Map<String, BotSkinSelection> botSkinSelections = new HashMap<>();
    // Old global bot controls (alias → settings). Read only by migrateLegacyToPerWorld, then set
    // to null so the key leaves settings.json5 (Gson skips nulls).
    private Map<String, BotControlSettings> botControls = null;
    // Per-world bot control settings: alias → worldKey → BotControlSettings.
    private Map<String, Map<String, BotControlSettings>> botControlsByWorld = new HashMap<>();
    // Seed-agnostic, bot-persistent quest continuity (non-power progression).
    private Map<String, BotQuestMemory> botQuestMemory = new HashMap<>();

    // === Survival recruitment mode ("find a village, then recruit") ===
    // When enabled, bots do not auto-spawn / restore until the world has been "recruited".
    private boolean survivalRecruitmentMode = false;
    // Server flag (1.1.235): Questing world mode is offered only when this is on. Off = a new world
    // goes straight to Admin; worlds already saved as Questing keep working. /bot config questingMode.
    private boolean questingModeEnabled = false;
    // Per-world (level-name key) recruitment state.
    private Map<String, SurvivalRecruitmentState> survivalRecruitment = new HashMap<>();
    // Legacy level-name key -> save fingerprint that adopted it (1.1.234 per-save keying).
    private Map<String, String> survivalRecruitmentLegacyClaims = new HashMap<>();

    // === Soul communication (opt-in, local-Ollama-only conversational pilot) ===
    // Default-off.
    // Non-secret fields only — no API keys belong here.
    private boolean soulsEnabled = false;
    private String soulProvider = "ollama";
    private String soulModel = "";
    private int soulRequestTimeoutSeconds = 60;
    private int soulQueueCapacity = 8;
    // Group scenes ("bots, ..." / "Jake and Sara, ...") kill switch. Default-on: it only takes
    // effect while the souls master above is on, so a souls-off install is unaffected.
    private boolean soulPartyEnabled = true;
    // Autonomous banter scenes. Default-OFF (explicit opt-in per the banter spec): companions
    // spending LLM/TTS time on their own initiative only happens after the user asks for it.
    private boolean soulBanterEnabled = false;
    // Ambient/local chat: bots overhearing unaddressed chat spoken near them. Default-OFF for the
    // same reason banter is — companions spending LLM/TTS time unprompted is opt-in. This toggle
    // also gates the overhear recorder's write, so off means nothing is recorded at all.
    private boolean soulLocalChatEnabled = false;
    // Active banter: companions chat while WORKING (skill running / actively following) —
    // a second lane in SoulBanterDirector with its own cadence. Default-OFF like banter.
    private boolean soulBanterActiveEnabled = false;
    // Player-memory digest: at each Minecraft day rollover a soul-bound bot distils what it heard
    // from each player into durable memories. Default-ON — it costs one small local LLM call per
    // (bot, player) with fresh conversation, and everything downstream of it (recall, ABOUT block)
    // degrades to the pre-digest behaviour when it is off.
    private boolean soulMemoryDigestEnabled = true;
    private boolean soulNoveltyRejectionEnabled = false;
    // Typed relation facts (ontology Phase 3b): the BELIEFS prompt block, relation banter
    // anchors, and the deterministic SEEN producer. Default-OFF pending field verification.
    private boolean soulRelationsEnabled = false;
    // Structured LLM output (ontology Phase 3c): the optional ##FRENS side channel and the mind
    // side-effects it carries. Default-OFF pending field verification.
    private boolean soulStructuredOutputEnabled = false;
    // Dialogue pacing sliders (0–100, 50 = shipped cadence). Never "off": the toggles above
    // and the lane masters (Scripted Text/Voice, Soul Chat/Voice) are the kill switches.
    private int dialogueScriptedRate = 50;
    private int soulBanterIdleRate = 50;
    private int soulBanterActiveRate = 50;
    private int soulLocalRate = 50;

    // Soul generated-voice (TTS). Default-off; local engines only. See
    // docs/superpowers/specs/2026-08-24-soul-generated-voice-design.md.
    private boolean soulVoiceEnabled = false;
    // Engine: "piper" (CPU, simple), "dreamsleeve" (the Qwen3-TTS voice-clone warm server
    // from ~/pontus/openmw-forge/dreamsleeve — same stack that voices Casca in OpenMW), or
    // "pocket" (Kyutai Pocket TTS — CPU, warm HTTP server, English preset voices).
    private String soulVoiceEngine = "piper";
    private String soulVoicePiperBinary = "";
    private String soulVoiceModel = "";
    // Dreamsleeve engine: repo dir (holds scripts/tts_server.py), plus the voice anchor —
    // a reference clip + its transcript that Qwen3-TTS clones for Jake's voice.
    private String soulVoiceDreamsleeveDir = "";
    private String soulVoiceRefAudio = "";
    private String soulVoiceRefText = "";
    // Pocket engine (Kyutai Pocket TTS, CPU): install dir holding the managed venv, plus the
    // default English preset voice; per-bot presets come from soulVoiceAssignments.
    private String soulVoicePocketDir = "";
    private String soulVoicePocketVoice = "charles";
    private int soulVoiceMaxChars = 400;
    private long soulVoiceSynthTimeoutMs = 8000L;
    private float soulVoiceRadioGain = 0.6f;

    /**
     * Private constructor to prevent direct instantiation.
     * Use the static load() method instead.
     */
    private ManualConfig() {
    }

    /**
     * Helper method to get the correct file path using the LauncherEnvironment class.
     * @return The absolute path to the settings file.
     */
    private static String getFilePath() {
        return LauncherEnvironment.getStorageDirectory("config") + File.separator + FILE_NAME;
    }

    /** Lock object for save/load serialization — prevents concurrent writes corrupting the JSON. */
    private static final Object SAVE_LOCK = new Object();

    /**
     * True while this client is connected to a remote (dedicated) server whose global config has
     * been synced into this in-memory instance. Transient so Gson never serializes it.
     */
    private transient volatile boolean remoteAuthoritative;

    /** Marks (or unmarks) this config as holding a remote server's globals. */
    public void setRemoteAuthoritative(boolean remoteAuthoritative) {
        this.remoteAuthoritative = remoteAuthoritative;
        if (!remoteAuthoritative) {
            this.remoteWorldKey = null;
        }
    }

    /**
     * World key of the remote server this client is connected to, sent with every config sync
     * ({@link SharedConfig#serverWorldKey}). Null in single player and before the first sync.
     * Transient so Gson never serializes it.
     */
    private transient volatile String remoteWorldKey;

    public void setRemoteWorldKey(String remoteWorldKey) {
        this.remoteWorldKey = remoteWorldKey == null || remoteWorldKey.isBlank() ? null : remoteWorldKey.trim();
    }

    public String getRemoteWorldKey() {
        return remoteWorldKey;
    }

    /**
     * The world key the Bot Controls screen edits under: the integrated server's key when there is
     * one, else the key the remote server sent, else null (per-bot edits cannot be kept).
     */
    public String botControlEditWorldKey(String integratedServerWorldKey) {
        if (integratedServerWorldKey != null && !integratedServerWorldKey.isBlank()) {
            return integratedServerWorldKey;
        }
        return remoteWorldKey;
    }

    /** @return true when these values came from a remote server and must not hit local disk. */
    public boolean isRemoteAuthoritative() {
        return remoteAuthoritative;
    }

    /**
     * Saves the current configuration to the settings.json5 file.
     */
    public void save() {
        if (remoteAuthoritative) {
            LOGGER.debug("[config] save skipped — config is remote-authoritative while connected to a server");
            return;
        }
        synchronized (SAVE_LOCK) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (FileWriter writer = new FileWriter(filePath())) {
                gson.toJson(this, writer);
            } catch (IOException e) {
                LOGGER.error("Failed to save config file: {}", e.getMessage());
            }
        }
    }

    /**
     * Loads the configuration from the settings.json5 file. If the file does not exist,
     * it creates and returns a new default configuration instance.
     *
     * @return A loaded ManualConfig instance, or a new one if the file is not found.
     */
    public static ManualConfig load() {
        File file = new File(filePath());
        // Ensure the directory for the file exists before attempting to write.
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        if (!file.exists()) {
            LOGGER.info("Config file not found. Creating a new one.");
            ManualConfig defaultConfig = new ManualConfig();
            defaultConfig.baritoneDefaultMigrated = true; // fresh file already has the new default
            defaultConfig.save(); // Save the new config to create the file
            return defaultConfig;
        }

        try (FileReader reader = new FileReader(file)) {
            Gson gson = new Gson();
            Type type = new TypeToken<ManualConfig>(){}.getType();
            ManualConfig loadedConfig = gson.fromJson(reader, type);
            if (loadedConfig.botQuestMemory == null) {
                loadedConfig.botQuestMemory = new HashMap<>();
            }
            if (loadedConfig.botSkinSelections == null) {
                loadedConfig.botSkinSelections = new HashMap<>();
            }
            if (loadedConfig.survivalRecruitment == null) {
                loadedConfig.survivalRecruitment = new HashMap<>();
            }
            if (loadedConfig.botSpawnPointsByWorld == null) {
                loadedConfig.botSpawnPointsByWorld = new HashMap<>();
            }
            if (loadedConfig.botControlsByWorld == null) {
                loadedConfig.botControlsByWorld = new HashMap<>();
            }
            if (loadedConfig.mutedVoiceCategories == null) {
                loadedConfig.mutedVoiceCategories = new ArrayList<>();
            }
            if (loadedConfig.mutedTextCategories == null) {
                loadedConfig.mutedTextCategories = new ArrayList<>();
            }
            // ── Legacy migration: global → per-world ──
            // Old configs stored one BotSpawn per alias; migrate to per-world under "_legacy" key.
            // The first real access with a worldKey will resolve the _legacy entry.
            loadedConfig.migrateLegacyToPerWorld();
            loadedConfig.normalizeAliasBackedMaps();
            if (loadedConfig.applyBaritoneDefaultMigration()) {
                loadedConfig.save();
            }
            return loadedConfig;
        } catch (IOException e) {
            LOGGER.error("Failed to load config file. Using default config.", e);
            ManualConfig fallback = new ManualConfig();
            fallback.baritoneDefaultMigrated = true; // already on the new default; never flip a later classic pick
            return fallback;
        }
    }

    public String getOllamaBaseUrl() {
        return (ollamaBaseUrl == null || ollamaBaseUrl.isBlank())
                ? "http://127.0.0.1:11434"
                : ollamaBaseUrl;
    }

    public void setOllamaBaseUrl(String ollamaBaseUrl) {
        if (ollamaBaseUrl == null || ollamaBaseUrl.isBlank()) {
            this.ollamaBaseUrl = "http://127.0.0.1:11434";
        } else {
            this.ollamaBaseUrl = ollamaBaseUrl.trim();
        }
    }

    public Map<String, String> getBotGameProfile() {
        if (botGameProfile == null) {
            botGameProfile = new HashMap<>();
        }
        return botGameProfile;
    }

    public void setBotGameProfile(Map<String, String> botGameProfile) {
        this.botGameProfile = normalizeAliasMap(botGameProfile);
    }

    public String getBotProfileUuid(String alias) {
        String key = resolveAliasKey(getBotGameProfile(), alias);
        return key != null ? getBotGameProfile().get(key) : null;
    }

    public void setBotProfile(String alias, String uuid) {
        if (alias == null || alias.isBlank() || uuid == null || uuid.isBlank()) {
            return;
        }
        putAliasValue(getBotGameProfile(), alias, uuid.trim());
    }

    // ── Bot skin presets ──

    public Map<String, SoulVoiceAssignment> getSoulProfileVoices() {
        if (soulProfileVoices == null) {
            soulProfileVoices = new HashMap<>();
        }
        return soulProfileVoices;
    }

    public void setSoulProfileVoice(String profileId, SoulVoiceAssignment assignment) {
        if (profileId == null || profileId.isBlank()) {
            return;
        }
        if (assignment == null || assignment.isEmpty()) {
            getSoulProfileVoices().remove(profileId.trim());
        } else {
            getSoulProfileVoices().put(profileId.trim(), assignment);
        }
    }

    /**
     * One profile's voice pick: an engine-interpreted voice name (+ optional speaker) and/or a
     * Dreamsleeve clone anchor. Pre-pocket configs wrote {@code piperModel}/{@code piperSpeaker};
     * those keys are still read and are rewritten as {@code voice}/{@code speaker} on save.
     */
    public static class SoulVoiceAssignment {
        @SerializedName(value = "voice", alternate = {"piperModel"})
        private String voice = "";
        @SerializedName(value = "speaker", alternate = {"piperSpeaker"})
        private int speaker = -1;
        private String refAudio = "";
        private String refText = "";

        public SoulVoiceAssignment() {
        }

        public SoulVoiceAssignment(String voice, int speaker, String refAudio, String refText) {
            this.voice = voice == null ? "" : voice.trim();
            this.speaker = speaker;
            this.refAudio = refAudio == null ? "" : refAudio.trim();
            this.refText = refText == null ? "" : refText;
        }

        public String getVoice() { return voice == null ? "" : voice; }
        public void setVoice(String v) { this.voice = v == null ? "" : v.trim(); }
        public int getSpeaker() { return speaker; }
        public void setSpeaker(int v) { this.speaker = v; }
        public String getRefAudio() { return refAudio == null ? "" : refAudio; }
        public void setRefAudio(String v) { this.refAudio = v == null ? "" : v.trim(); }
        public String getRefText() { return refText == null ? "" : refText; }
        public void setRefText(String v) { this.refText = v == null ? "" : v; }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isEmpty() {
            return getVoice().isEmpty() && getRefAudio().isEmpty();
        }
    }

    public Map<String, BotSkinSelection> getBotSkinSelections() {
        if (botSkinSelections == null) {
            botSkinSelections = new HashMap<>();
        }
        return botSkinSelections;
    }

    public void setBotSkinSelections(Map<String, BotSkinSelection> botSkinSelections) {
        this.botSkinSelections = normalizeAliasMap(botSkinSelections);
    }

    public BotSkinSelection getBotSkinSelection(String alias) {
        String key = resolveAliasKey(getBotSkinSelections(), alias);
        return key != null ? getBotSkinSelections().get(key) : null;
    }

    public void setBotSkinSelection(String alias, BotSkinSelection selection) {
        if (alias == null || alias.isBlank() || selection == null) {
            return;
        }
        putAliasValue(getBotSkinSelections(), alias, selection.sanitized());
    }

    public Map<String, BotOwnership> getBotOwnership() {
        if (botOwnership == null) {
            botOwnership = new HashMap<>();
        }
        return botOwnership;
    }

    public void setBotOwnership(Map<String, BotOwnership> botOwnership) {
        this.botOwnership = normalizeAliasMap(botOwnership);
    }

    public void setOwner(String alias, BotOwnership owner) {
        if (alias == null || alias.isBlank()) {
            return;
        }
        putAliasValue(getBotOwnership(), alias, owner);
    }

    public BotOwnership getOwner(String alias) {
        String key = resolveAliasKey(getBotOwnership(), alias);
        return key != null ? getBotOwnership().get(key) : null;
    }

    public boolean getPreserveExpensiveGear(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        if (playerPreserveExpensiveGear == null) {
            return false;
        }
        return playerPreserveExpensiveGear.getOrDefault(playerUuid.toString(), Boolean.FALSE);
    }

    public void setPreserveExpensiveGear(UUID playerUuid, boolean value) {
        if (playerUuid == null) {
            return;
        }
        if (playerPreserveExpensiveGear == null) {
            playerPreserveExpensiveGear = new HashMap<>();
        }
        playerPreserveExpensiveGear.put(playerUuid.toString(), value);
    }

    public boolean getAutoAcceptPreciousFoods(UUID playerUuid) {
        if (playerUuid == null || playerAutoAcceptPreciousFoods == null) {
            return false;
        }
        return playerAutoAcceptPreciousFoods.getOrDefault(playerUuid.toString(), Boolean.FALSE);
    }

    public void setAutoAcceptPreciousFoods(UUID playerUuid, boolean value) {
        if (playerUuid == null) {
            return;
        }
        if (playerAutoAcceptPreciousFoods == null) {
            playerAutoAcceptPreciousFoods = new HashMap<>();
        }
        playerAutoAcceptPreciousFoods.put(playerUuid.toString(), value);
    }

    /** @deprecated Legacy accessor. Use {@link #getBotSpawnPointsByWorld()} instead. */
    @Deprecated
    public Map<String, BotSpawn> getBotSpawnPoints() {
        if (botSpawnPoints == null) {
            botSpawnPoints = new HashMap<>();
        }
        return botSpawnPoints;
    }

    // ── Per-world BotSpawn accessors ──

    public Map<String, Map<String, BotSpawn>> getBotSpawnPointsByWorld() {
        if (botSpawnPointsByWorld == null) {
            botSpawnPointsByWorld = new HashMap<>();
        }
        return botSpawnPointsByWorld;
    }

    /**
     * Returns the BotSpawn for the given alias in the given world.
     * If a legacy entry exists (from pre-per-world config) and no world-specific
     * entry is found, migrates the legacy entry to the provided worldKey.
     */
    public BotSpawn getBotSpawn(String alias, String worldKey) {
        if (alias == null || alias.isBlank()) return null;
        Map<String, BotSpawn> worldMap = resolveNestedAliasEntry(getBotSpawnPointsByWorld(), alias);
        if (worldMap == null) return null;
        if (worldKey != null && !worldKey.isBlank()) {
            BotSpawn spawn = worldMap.get(worldKey);
            if (spawn != null) return spawn;
            // Migrate legacy entry on first per-world access.
            BotSpawn legacy = worldMap.remove("_legacy");
            if (legacy != null) {
                worldMap.put(worldKey, legacy);
                return legacy;
            }
        }
        return null;
    }

    public void setBotSpawn(String alias, String worldKey, BotSpawn spawn) {
        if (alias == null || alias.isBlank() || worldKey == null || worldKey.isBlank() || spawn == null) {
            return;
        }
        String key = resolveAliasKey(getBotSpawnPointsByWorld(), alias);
        if (key == null) key = alias.trim();
        Map<String, BotSpawn> worldMap = getBotSpawnPointsByWorld().computeIfAbsent(key, ignored -> new HashMap<>());
        worldMap.put(worldKey, spawn);
        // Clear legacy entry if present since we now have a real worldKey.
        worldMap.remove("_legacy");
    }

    public void clearBotSpawn(String alias, String worldKey) {
        if (alias == null || alias.isBlank() || worldKey == null || worldKey.isBlank()) return;
        Map<String, BotSpawn> worldMap = resolveNestedAliasEntry(getBotSpawnPointsByWorld(), alias);
        if (worldMap != null) {
            worldMap.remove(worldKey);
        }
    }

    // ── Per-world BotControlSettings accessors ──

    public Map<String, Map<String, BotControlSettings>> getBotControlsByWorld() {
        if (botControlsByWorld == null) {
            botControlsByWorld = new HashMap<>();
        }
        return botControlsByWorld;
    }

    /**
     * Returns or creates per-world BotControlSettings for the given alias and worldKey.
     * If a legacy entry exists, migrates it to the provided worldKey.
     */
    public BotControlSettings getOrCreateBotControl(String alias, String worldKey) {
        if (alias == null || alias.isBlank()) alias = "default";
        if (worldKey != null && !worldKey.isBlank()) {
            String key = resolveAliasKey(getBotControlsByWorld(), alias);
            if (key == null) key = alias.trim();
            Map<String, BotControlSettings> worldMap = getBotControlsByWorld()
                    .computeIfAbsent(key, ignored -> new HashMap<>());
            BotControlSettings settings = worldMap.get(worldKey);
            if (settings != null) return settings;
            // First world to ask takes a migrated global entry.
            BotControlSettings legacy = LegacyBotControlMigrationPolicy.claimLegacy(worldMap, worldKey);
            if (legacy != null) return legacy;
            // Create new entry for this world.
            BotControlSettings created = new BotControlSettings();
            worldMap.put(worldKey, created);
            return created;
        }
        // No world key: defaults that are never stored, so an edit without a world is not kept.
        return new BotControlSettings();
    }

    /**
     * Returns the per-world effective bot control (specific alias, then "default" alias).
     */
    public BotControlSettings getEffectiveBotControl(String alias, String worldKey) {
        if (alias != null && worldKey != null && !worldKey.isBlank()) {
            Map<String, BotControlSettings> worldMap = resolveNestedAliasEntry(getBotControlsByWorld(), alias);
            if (worldMap != null) {
                BotControlSettings specific = worldMap.get(worldKey);
                if (specific != null) return specific;
                BotControlSettings legacy = LegacyBotControlMigrationPolicy.claimLegacy(worldMap, worldKey);
                if (legacy != null) return legacy;
            }
        }
        // Fall back to "default" alias
        if (worldKey != null && !worldKey.isBlank()) {
            Map<String, BotControlSettings> defaultWorld = resolveNestedAliasEntry(getBotControlsByWorld(), "default");
            if (defaultWorld != null) {
                BotControlSettings d = defaultWorld.get(worldKey);
                if (d != null) return d;
            }
        }
        // No settings for this bot in this world. Every caller treats null as "use defaults".
        return null;
    }

    /**
     * Returns the set of all bot aliases that have per-world spawn or control entries.
     * Useful for GUI alias lists and autocomplete where no world context is needed.
     */
    public java.util.Set<String> getAllBotAliases() {
        java.util.Set<String> aliases = new java.util.LinkedHashSet<>();
        // From per-world maps
        if (botSpawnPointsByWorld != null) aliases.addAll(botSpawnPointsByWorld.keySet());
        if (botControlsByWorld != null) aliases.addAll(botControlsByWorld.keySet());
        // From legacy maps (in case migration hasn't happened yet)
        if (botSpawnPoints != null) aliases.addAll(botSpawnPoints.keySet());
        // From identity maps
        if (botGameProfile != null) aliases.addAll(botGameProfile.keySet());
        aliases.remove("default");
        return aliases;
    }

    public boolean isTextDialogueEnabled() {
        return textDialogueEnabled;
    }

    public void setTextDialogueEnabled(boolean textDialogueEnabled) {
        this.textDialogueEnabled = textDialogueEnabled;
    }

    public boolean isVoicedDialogueEnabled() {
        return voicedDialogueEnabled;
    }

    public void setVoicedDialogueEnabled(boolean voicedDialogueEnabled) {
        this.voicedDialogueEnabled = voicedDialogueEnabled;
    }

    public boolean isVoiceCategoryMuted(String categoryId) {
        return categoryId != null
                && mutedVoiceCategories != null
                && mutedVoiceCategories.contains(categoryId);
    }

    public void setVoiceCategoryMuted(String categoryId, boolean muted) {
        if (categoryId == null || categoryId.isBlank()) {
            return;
        }
        if (mutedVoiceCategories == null) {
            mutedVoiceCategories = new ArrayList<>();
        }
        if (muted) {
            if (!mutedVoiceCategories.contains(categoryId)) {
                mutedVoiceCategories.add(categoryId);
            }
        } else {
            mutedVoiceCategories.remove(categoryId);
        }
    }

    public List<String> getMutedVoiceCategories() {
        if (mutedVoiceCategories == null) {
            mutedVoiceCategories = new ArrayList<>();
        }
        return mutedVoiceCategories;
    }

    public boolean isTextCategoryMuted(String categoryId) {
        return categoryId != null
                && mutedTextCategories != null
                && mutedTextCategories.contains(categoryId);
    }

    public void setTextCategoryMuted(String categoryId, boolean muted) {
        if (categoryId == null || categoryId.isBlank()) {
            return;
        }
        if (mutedTextCategories == null) {
            mutedTextCategories = new ArrayList<>();
        }
        if (muted) {
            if (!mutedTextCategories.contains(categoryId)) {
                mutedTextCategories.add(categoryId);
            }
        } else {
            mutedTextCategories.remove(categoryId);
        }
    }

    public List<String> getMutedTextCategories() {
        if (mutedTextCategories == null) {
            mutedTextCategories = new ArrayList<>();
        }
        return mutedTextCategories;
    }

    public boolean isGameplayTipsEnabled() {
        return gameplayTipsEnabled;
    }

    public void setGameplayTipsEnabled(boolean gameplayTipsEnabled) {
        this.gameplayTipsEnabled = gameplayTipsEnabled;
    }

    public boolean isIdleHobbiesAnywhereEnabled() {
        return idleHobbiesAnywhereEnabled;
    }

    public void setIdleHobbiesAnywhereEnabled(boolean idleHobbiesAnywhereEnabled) {
        this.idleHobbiesAnywhereEnabled = idleHobbiesAnywhereEnabled;
    }

    public boolean isBaritonePathfinderEnabled() { return baritonePathfinderEnabled; }
    public void setBaritonePathfinderEnabled(boolean v) { this.baritonePathfinderEnabled = v; }
    public boolean isBaritoneDefaultMigrated() { return baritoneDefaultMigrated; }

    /**
     * One-time 1.1.240 migration: a config written before Baritone became the default holds
     * {@code baritonePathfinderEnabled=false} only because that was the old default, so flip
     * it to true once. The marker is set either way, so a user who picks classic afterwards
     * keeps classic. No I/O here; {@link #load()} saves when this returns true.
     *
     * @return true when the config changed (flag set and/or finder flipped) and should be saved
     */
    public boolean applyBaritoneDefaultMigration() {
        if (baritoneDefaultMigrated) {
            return false;
        }
        baritoneDefaultMigrated = true;
        if (!baritonePathfinderEnabled) {
            baritonePathfinderEnabled = true;
            LOGGER.info("[Frens] 1.1.240 config migration: pathfinder switched to baritone (the new default); "
                    + "use /bot config pathfinder classic to go back.");
        }
        return true;
    }

    public boolean isFortifyForcePlaceEnabled() { return fortifyForcePlaceEnabled; }
    public void setFortifyForcePlaceEnabled(boolean v) { this.fortifyForcePlaceEnabled = v; }

    public boolean isVerboseDiagnostics() { return verboseDiagnostics; }
    public void setVerboseDiagnostics(boolean v) { this.verboseDiagnostics = v; }

    /** Returns the global teleport override, or null if per-bot settings should apply. */
    public Boolean getGlobalTeleportDuringSkills() { return globalTeleportDuringSkills; }
    public void setGlobalTeleportDuringSkills(Boolean v) { this.globalTeleportDuringSkills = v; }

    public int getFortBufferRadius() { return fortBufferRadius > 0 ? fortBufferRadius : 8; }
    public void setFortBufferRadius(int r) { this.fortBufferRadius = r; }

    public int getUndergroundLingerMinutes() { return undergroundLingerMinutes > 0 ? undergroundLingerMinutes : 3; }
    public void setUndergroundLingerMinutes(int m) { this.undergroundLingerMinutes = m; }

    public int getUndergroundProximityBlocks() { return undergroundProximityBlocks > 0 ? undergroundProximityBlocks : 32; }
    public void setUndergroundProximityBlocks(int b) { this.undergroundProximityBlocks = b; }

    // === Soul communication accessors ===
    // Default-off, non-secret, local-Ollama-only.

    public boolean isSoulsEnabled() { return soulsEnabled; }
    public void setSoulsEnabled(boolean soulsEnabled) { this.soulsEnabled = soulsEnabled; }

    public boolean isSoulPartyEnabled() { return soulPartyEnabled; }
    public void setSoulPartyEnabled(boolean v) { this.soulPartyEnabled = v; }

    public boolean isSoulBanterEnabled() { return soulBanterEnabled; }
    public void setSoulBanterEnabled(boolean v) { this.soulBanterEnabled = v; }

    public boolean isSoulLocalChatEnabled() { return soulLocalChatEnabled; }
    public void setSoulLocalChatEnabled(boolean v) { this.soulLocalChatEnabled = v; }

    public boolean isSoulBanterActiveEnabled() { return soulBanterActiveEnabled; }
    public void setSoulBanterActiveEnabled(boolean v) { this.soulBanterActiveEnabled = v; }

    public boolean isSoulMemoryDigestEnabled() { return soulMemoryDigestEnabled; }
    public void setSoulMemoryDigestEnabled(boolean v) { this.soulMemoryDigestEnabled = v; }
    public boolean isSoulNoveltyRejectionEnabled() { return soulNoveltyRejectionEnabled; }
    public void setSoulNoveltyRejectionEnabled(boolean v) { this.soulNoveltyRejectionEnabled = v; }
    public boolean isSoulRelationsEnabled() { return soulRelationsEnabled; }
    public void setSoulRelationsEnabled(boolean v) { this.soulRelationsEnabled = v; }
    public boolean isSoulStructuredOutputEnabled() { return soulStructuredOutputEnabled; }
    public void setSoulStructuredOutputEnabled(boolean v) { this.soulStructuredOutputEnabled = v; }

    private static int clampRate(int rate) { return Math.max(0, Math.min(100, rate)); }
    public int getDialogueScriptedRate() { return clampRate(dialogueScriptedRate); }
    public void setDialogueScriptedRate(int v) { this.dialogueScriptedRate = clampRate(v); }
    public int getSoulBanterIdleRate() { return clampRate(soulBanterIdleRate); }
    public void setSoulBanterIdleRate(int v) { this.soulBanterIdleRate = clampRate(v); }
    public int getSoulBanterActiveRate() { return clampRate(soulBanterActiveRate); }
    public void setSoulBanterActiveRate(int v) { this.soulBanterActiveRate = clampRate(v); }
    public int getSoulLocalRate() { return clampRate(soulLocalRate); }
    public void setSoulLocalRate(int v) { this.soulLocalRate = clampRate(v); }

    public String getSoulProvider() {
        return (soulProvider == null || soulProvider.isBlank()) ? "ollama" : soulProvider;
    }

    public void setSoulProvider(String soulProvider) {
        this.soulProvider = (soulProvider == null || soulProvider.isBlank())
                ? "ollama" : soulProvider.trim();
    }

    public String getSoulModel() {
        return soulModel == null ? "" : soulModel;
    }

    public void setSoulModel(String soulModel) {
        this.soulModel = soulModel == null ? "" : soulModel.trim();
    }

    public int getSoulRequestTimeoutSeconds() {
        return Math.max(10, Math.min(180, soulRequestTimeoutSeconds));
    }

    public void setSoulRequestTimeoutSeconds(int soulRequestTimeoutSeconds) {
        this.soulRequestTimeoutSeconds = Math.max(10, Math.min(180, soulRequestTimeoutSeconds));
    }

    public int getSoulQueueCapacity() {
        return Math.max(1, Math.min(32, soulQueueCapacity));
    }

    public void setSoulQueueCapacity(int soulQueueCapacity) {
        this.soulQueueCapacity = Math.max(1, Math.min(32, soulQueueCapacity));
    }

    // === Soul generated-voice accessors ===

    public boolean isSoulVoiceEnabled() { return soulVoiceEnabled; }
    public void setSoulVoiceEnabled(boolean v) { this.soulVoiceEnabled = v; }

    public String getSoulVoiceEngine() {
        return (soulVoiceEngine == null || soulVoiceEngine.isBlank()) ? "piper" : soulVoiceEngine.trim();
    }
    public void setSoulVoiceEngine(String v) { this.soulVoiceEngine = v == null ? "piper" : v.trim(); }

    public String getSoulVoiceDreamsleeveDir() { return soulVoiceDreamsleeveDir == null ? "" : soulVoiceDreamsleeveDir; }
    public void setSoulVoiceDreamsleeveDir(String v) { this.soulVoiceDreamsleeveDir = v == null ? "" : v.trim(); }

    public String getSoulVoiceRefAudio() { return soulVoiceRefAudio == null ? "" : soulVoiceRefAudio; }
    public void setSoulVoiceRefAudio(String v) { this.soulVoiceRefAudio = v == null ? "" : v.trim(); }

    public String getSoulVoiceRefText() { return soulVoiceRefText == null ? "" : soulVoiceRefText; }
    public void setSoulVoiceRefText(String v) { this.soulVoiceRefText = v == null ? "" : v.trim(); }

    public String getSoulVoicePiperBinary() { return soulVoicePiperBinary == null ? "" : soulVoicePiperBinary; }
    public void setSoulVoicePiperBinary(String v) { this.soulVoicePiperBinary = v == null ? "" : v.trim(); }

    public String getSoulVoiceModel() { return soulVoiceModel == null ? "" : soulVoiceModel; }
    public void setSoulVoiceModel(String v) { this.soulVoiceModel = v == null ? "" : v.trim(); }

    public String getSoulVoicePocketDir() { return soulVoicePocketDir == null ? "" : soulVoicePocketDir.trim(); }
    public void setSoulVoicePocketDir(String v) { this.soulVoicePocketDir = v == null ? "" : v.trim(); }

    /** Default Pocket TTS preset; blank reads as {@code "charles"}. */
    public String getSoulVoicePocketVoice() {
        return (soulVoicePocketVoice == null || soulVoicePocketVoice.isBlank()) ? "charles" : soulVoicePocketVoice.trim();
    }
    public void setSoulVoicePocketVoice(String v) { this.soulVoicePocketVoice = v == null ? "" : v.trim(); }

    public int getSoulVoiceMaxChars() { return Math.max(40, Math.min(1000, soulVoiceMaxChars)); }
    public void setSoulVoiceMaxChars(int v) { this.soulVoiceMaxChars = v; }

    public long getSoulVoiceSynthTimeoutMs() { return Math.max(1000L, Math.min(30_000L, soulVoiceSynthTimeoutMs)); }
    public void setSoulVoiceSynthTimeoutMs(long v) { this.soulVoiceSynthTimeoutMs = v; }

    public float getSoulVoiceRadioGain() { return Math.max(0.0f, Math.min(1.0f, soulVoiceRadioGain)); }
    public void setSoulVoiceRadioGain(float v) { this.soulVoiceRadioGain = v; }

    public Map<String, BotQuestMemory> getBotQuestMemory() {
        if (botQuestMemory == null) {
            botQuestMemory = new HashMap<>();
        }
        return botQuestMemory;
    }

    // ===== Survival recruitment mode =====

    public boolean isSurvivalRecruitmentMode() {
        return survivalRecruitmentMode;
    }

    public void setSurvivalRecruitmentMode(boolean survivalRecruitmentMode) {
        this.survivalRecruitmentMode = survivalRecruitmentMode;
    }

    public boolean isQuestingModeEnabled() {
        return questingModeEnabled;
    }

    public void setQuestingModeEnabled(boolean questingModeEnabled) {
        this.questingModeEnabled = questingModeEnabled;
    }

    public Map<String, SurvivalRecruitmentState> getSurvivalRecruitment() {
        if (survivalRecruitment == null) {
            survivalRecruitment = new HashMap<>();
        }
        return survivalRecruitment;
    }

    public Map<String, String> getSurvivalRecruitmentLegacyClaims() {
        if (survivalRecruitmentLegacyClaims == null) {
            survivalRecruitmentLegacyClaims = new HashMap<>();
        }
        return survivalRecruitmentLegacyClaims;
    }

    public SurvivalRecruitmentState getOrCreateSurvivalRecruitmentState(String worldKey) {
        if (worldKey == null || worldKey.isBlank()) {
            worldKey = "default";
        }
        String key = worldKey.trim();
        survivalRecruitment = getSurvivalRecruitment();
        return survivalRecruitment.computeIfAbsent(key, ignored -> new SurvivalRecruitmentState());
    }

    public void setSurvivalRecruitmentState(String worldKey, SurvivalRecruitmentState state) {
        if (worldKey == null || worldKey.isBlank() || state == null) {
            return;
        }
        getSurvivalRecruitment().put(worldKey.trim(), state);
    }

    public BotQuestMemory getOrCreateBotQuestMemory(String alias) {
        if (alias == null || alias.isBlank()) {
            alias = "default";
        }
        String key = resolveAliasKey(getBotQuestMemory(), alias);
        if (key == null) {
            key = alias.trim();
        }
        botQuestMemory = getBotQuestMemory();
        return botQuestMemory.computeIfAbsent(key, ignored -> new BotQuestMemory());
    }

    public void ensureOwner(String alias, UUID ownerUuid, String ownerName) {
        if (alias == null || ownerUuid == null) {
            return;
        }
        BotOwnership existing = getOwner(alias);
        if (existing != null && existing.ownerUuid() != null && !existing.ownerUuid().isBlank()) {
            return;
        }
        BotOwnership updated = new BotOwnership(ownerUuid.toString(), ownerName);
        setOwner(alias, updated);
        save();
    }

    public void removeBotEntry(String alias) {
        if (alias == null || alias.isBlank()) {
            return;
        }
        removeAliasVariants(botGameProfile, alias);
        removeAliasVariants(botOwnership, alias);
        removeAliasVariants(botSpawnPoints, alias);
        removeAliasVariants(botQuestMemory, alias);
        removeAliasVariants(botSkinSelections, alias);
        removeNestedAliasVariants(botSpawnPointsByWorld, alias);
        removeNestedAliasVariants(botControlsByWorld, alias);
    }

    private void normalizeAliasBackedMaps() {
        botGameProfile = normalizeAliasMap(botGameProfile);
        botOwnership = normalizeAliasMap(botOwnership);
        botSpawnPoints = normalizeAliasMap(botSpawnPoints);
        botQuestMemory = normalizeAliasMap(botQuestMemory);
        botSkinSelections = normalizeAliasMap(botSkinSelections);
    }

    private static String normalizeAlias(String alias) {
        return alias == null ? "" : alias.trim().toLowerCase(Locale.ROOT);
    }

    private static <T> String resolveAliasKey(Map<String, T> map, String alias) {
        if (map == null || map.isEmpty() || alias == null || alias.isBlank()) {
            return null;
        }
        String trimmed = alias.trim();
        if (map.containsKey(trimmed)) {
            return trimmed;
        }
        String normalized = normalizeAlias(trimmed);
        for (String key : map.keySet()) {
            if (normalizeAlias(key).equals(normalized)) {
                return key;
            }
        }
        return null;
    }

    private static <T> Map<String, T> normalizeAliasMap(Map<String, T> source) {
        Map<String, T> out = new HashMap<>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        Map<String, String> canonicalByNormalized = new HashMap<>();
        for (Map.Entry<String, T> entry : source.entrySet()) {
            String rawKey = entry.getKey();
            if (rawKey == null || rawKey.isBlank()) {
                continue;
            }
            String trimmed = rawKey.trim();
            String normalized = normalizeAlias(trimmed);
            String canonical = canonicalByNormalized.computeIfAbsent(normalized, ignored -> trimmed);
            out.putIfAbsent(canonical, entry.getValue());
        }
        return out;
    }

    private static <T> void putAliasValue(Map<String, T> map, String alias, T value) {
        if (map == null || alias == null || alias.isBlank()) {
            return;
        }
        String key = resolveAliasKey(map, alias);
        if (key == null) {
            key = alias.trim();
        }
        map.put(key, value);
        removeAliasVariantsExcept(map, key);
    }

    private static <T> void removeAliasVariants(Map<String, T> map, String alias) {
        if (map == null || map.isEmpty() || alias == null || alias.isBlank()) {
            return;
        }
        String normalized = normalizeAlias(alias);
        Iterator<String> it = map.keySet().iterator();
        while (it.hasNext()) {
            if (normalizeAlias(it.next()).equals(normalized)) {
                it.remove();
            }
        }
    }

    private static <T> void removeAliasVariantsExcept(Map<String, T> map, String canonicalAlias) {
        if (map == null || map.isEmpty() || canonicalAlias == null || canonicalAlias.isBlank()) {
            return;
        }
        String normalized = normalizeAlias(canonicalAlias);
        Iterator<String> it = map.keySet().iterator();
        while (it.hasNext()) {
            String key = it.next();
            if (!key.equals(canonicalAlias) && normalizeAlias(key).equals(normalized)) {
                it.remove();
            }
        }
    }

    // ── Nested (per-world) map helpers ──

    /**
     * Resolve an alias key in a nested map (alias → worldKey → T).
     * Returns the inner map for the alias, or null.
     */
    private static <T> Map<String, T> resolveNestedAliasEntry(Map<String, Map<String, T>> map, String alias) {
        if (map == null || map.isEmpty() || alias == null || alias.isBlank()) return null;
        String key = resolveAliasKey(map, alias);
        return key != null ? map.get(key) : null;
    }

    private static <T> void removeNestedAliasVariants(Map<String, Map<String, T>> map, String alias) {
        if (map == null || map.isEmpty() || alias == null || alias.isBlank()) return;
        String normalized = normalizeAlias(alias);
        Iterator<String> it = map.keySet().iterator();
        while (it.hasNext()) {
            if (normalizeAlias(it.next()).equals(normalized)) {
                it.remove();
            }
        }
    }

    /**
     * Migrates legacy global botSpawnPoints and botControls into per-world maps.
     * Legacy entries are stored under the "_legacy" worldKey and resolved on first
     * per-world access (see getBotSpawn/getOrCreateBotControl).
     */
    private void migrateLegacyToPerWorld() {
        // Migrate botSpawnPoints → botSpawnPointsByWorld
        if (botSpawnPoints != null && !botSpawnPoints.isEmpty()) {
            if (botSpawnPointsByWorld == null) botSpawnPointsByWorld = new HashMap<>();
            for (Map.Entry<String, BotSpawn> entry : botSpawnPoints.entrySet()) {
                String alias = entry.getKey();
                BotSpawn spawn = entry.getValue();
                if (alias == null || alias.isBlank() || spawn == null) continue;
                // Only migrate if no per-world entry exists for this alias yet.
                Map<String, BotSpawn> existing = resolveNestedAliasEntry(botSpawnPointsByWorld, alias);
                if (existing == null || existing.isEmpty()) {
                    Map<String, BotSpawn> worldMap = new HashMap<>();
                    // If the BotSpawn has a levelName, use it as the worldKey hint;
                    // otherwise store under _legacy for deferred resolution.
                    String worldKey = (spawn.levelName() != null && !spawn.levelName().isBlank())
                            ? spawn.levelName()
                            : "_legacy";
                    worldMap.put(worldKey, spawn);
                    botSpawnPointsByWorld.put(alias.trim(), worldMap);
                }
            }
            botSpawnPoints = new HashMap<>(); // clear legacy
            LOGGER.info("[Frens] Migrated {} legacy BotSpawn entries to per-world format.", botSpawnPointsByWorld.size());
        }

        // Migrate botControls → botControlsByWorld
        if (botControls != null && !botControls.isEmpty()) {
            if (botControlsByWorld == null) botControlsByWorld = new HashMap<>();
            LegacyBotControlMigrationPolicy.Result result =
                    LegacyBotControlMigrationPolicy.migrate(botControls, botControlsByWorld);
            if (!result.migrated().isEmpty()) {
                LOGGER.info("[Frens] Migrated global bot controls to per-world format for {}.", result.migrated());
            }
            if (!result.dropped().isEmpty()) {
                LOGGER.info("[Frens] Dropped global bot controls for {}: per-world settings already exist.", result.dropped());
            }
        }
        // Always null (even when it loaded as an empty map) so the key leaves the saved file.
        botControls = null;
    }

    public static class BotOwnership {
        private String ownerUuid;
        private String ownerName;

        public BotOwnership() {
        }

        public BotOwnership(String ownerUuid, String ownerName) {
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName;
        }

        public String ownerUuid() {
            return ownerUuid;
        }

        public String ownerName() {
            return ownerName;
        }

        public void setOwnerUuid(String ownerUuid) {
            this.ownerUuid = ownerUuid;
        }

        public void setOwnerName(String ownerName) {
            this.ownerName = ownerName;
        }
    }

    public static class BotSkinSelection {
        private String source = "preset";
        private String value = "steve";
        private String lastSetByUuid;
        private String lastSetByName;
        private boolean lastSetByAdmin;
        private long lastSetAtEpochMs;

        public BotSkinSelection() {
        }

        public BotSkinSelection(String source,
                                String value,
                                String lastSetByUuid,
                                String lastSetByName,
                                boolean lastSetByAdmin,
                                long lastSetAtEpochMs) {
            this.source = source;
            this.value = value;
            this.lastSetByUuid = lastSetByUuid;
            this.lastSetByName = lastSetByName;
            this.lastSetByAdmin = lastSetByAdmin;
            this.lastSetAtEpochMs = lastSetAtEpochMs;
        }

        public BotSkinSelection sanitized() {
            String normalizedSource = (source == null || source.isBlank())
                    ? "preset"
                    : source.trim().toLowerCase(Locale.ROOT);
            if (!normalizedSource.equals("preset") && !normalizedSource.equals("custom_url")) {
                normalizedSource = "preset";
            }
            String normalizedValue = (value == null || value.isBlank()) ? "steve" : value.trim();
            return new BotSkinSelection(
                    normalizedSource,
                    normalizedValue,
                    (lastSetByUuid == null || lastSetByUuid.isBlank()) ? null : lastSetByUuid.trim(),
                    (lastSetByName == null || lastSetByName.isBlank()) ? null : lastSetByName.trim(),
                    lastSetByAdmin,
                    Math.max(0L, lastSetAtEpochMs)
            );
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getLastSetByUuid() {
            return lastSetByUuid;
        }

        public void setLastSetByUuid(String lastSetByUuid) {
            this.lastSetByUuid = lastSetByUuid;
        }

        public String getLastSetByName() {
            return lastSetByName;
        }

        public void setLastSetByName(String lastSetByName) {
            this.lastSetByName = lastSetByName;
        }

        public boolean isLastSetByAdmin() {
            return lastSetByAdmin;
        }

        public void setLastSetByAdmin(boolean lastSetByAdmin) {
            this.lastSetByAdmin = lastSetByAdmin;
        }

        public long getLastSetAtEpochMs() {
            return lastSetAtEpochMs;
        }

        public void setLastSetAtEpochMs(long lastSetAtEpochMs) {
            this.lastSetAtEpochMs = lastSetAtEpochMs;
        }

        public boolean isCustomUrl() {
            return "custom_url".equalsIgnoreCase(source);
        }
    }

    public static class BotSpawn {
        private String levelName;
        private String dimension;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;

        public BotSpawn() {
        }

        public BotSpawn(String levelName, String dimension, double x, double y, double z, float yaw, float pitch) {
            this.levelName = levelName;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        public String levelName() {
            return levelName;
        }

        public String dimension() {
            return dimension;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public float yaw() {
            return yaw;
        }

        public float pitch() {
            return pitch;
        }
    }

    public static class BotControlSettings {
        /** @deprecated Legacy field kept for JSON backward compat; no longer drives any behavior. */
        @Deprecated @SuppressWarnings("unused")
        private boolean autoSpawn;
        private Boolean autoRespawnOnDeath;  // null = use mode-based default
        private String spawnMode = "admin";
        private String gameMode = "survival";
        private boolean teleportDuringSkills = false;
        private boolean followTeleport = false;
        private boolean pauseOnFullInventory;
        private boolean teleportDuringDropSweep = false;
        private boolean voicedDialogue = true;
        private String failsafeSpawnMode = "world_spawn";  // owner_bed | world_spawn | saved_base
        private boolean autoRegroupOnLost = false;  // auto-regroup when bot loses contact at a drop-off
        private boolean autoSpawnOnLoad = true;  // shelved when false

        /** @deprecated Use {@link #isAutoRespawnOnDeath()} instead. Kept for JSON compat. */
        @Deprecated
        public boolean isAutoSpawn() {
            return autoSpawn;
        }

        /** @deprecated No-op. Kept for JSON compat. */
        @Deprecated
        public void setAutoSpawn(boolean autoSpawn) {
            this.autoSpawn = autoSpawn;
        }

        /**
         * Whether this bot should automatically respawn on death (skip resurrection ritual).
         * If the explicit value has not been set, derives a default from the spawn mode:
         * admin → true, questing/play → false.
         */
        public boolean isAutoRespawnOnDeath() {
            if (autoRespawnOnDeath != null) {
                return autoRespawnOnDeath;
            }
            // Mode-based default: admin bots auto-respawn; questing/play bots require ritual.
            String mode = getSpawnMode();
            return !"play".equalsIgnoreCase(mode);
        }

        public void setAutoRespawnOnDeath(Boolean autoRespawnOnDeath) {
            this.autoRespawnOnDeath = autoRespawnOnDeath;
        }

        public Boolean getRawAutoRespawnOnDeath() {
            return autoRespawnOnDeath;
        }

        public String getSpawnMode() {
            return (spawnMode == null || spawnMode.isBlank()) ? "admin" : spawnMode;
        }

        public void setSpawnMode(String spawnMode) {
            if (spawnMode == null) {
                this.spawnMode = "admin";
                return;
            }
            String normalized = spawnMode.trim().toLowerCase();
            // Preserve the actual mode (admin, questing, play).
            // Legacy 'play' is kept as-is for backward compat. Removed 'training' falls to admin.
            switch (normalized) {
                case "admin", "questing", "play" -> this.spawnMode = normalized;
                default -> this.spawnMode = "admin";
            }
        }

        public String getGameMode() {
            return (gameMode == null || gameMode.isBlank()) ? "survival" : gameMode;
        }

        public void setGameMode(String gameMode) {
            if (gameMode == null) {
                this.gameMode = "survival";
                return;
            }
            String normalized = gameMode.trim().toLowerCase();
            this.gameMode = normalized.equals("creative") ? "creative" : "survival";
        }

        public boolean isTeleportDuringSkills() {
            return teleportDuringSkills;
        }

        public void setTeleportDuringSkills(boolean teleportDuringSkills) {
            this.teleportDuringSkills = teleportDuringSkills;
        }

        public boolean isFollowTeleport() { return followTeleport; }
        public void setFollowTeleport(boolean v) { this.followTeleport = v; }

        public boolean isPauseOnFullInventory() {
            return pauseOnFullInventory;
        }

        public void setPauseOnFullInventory(boolean pauseOnFullInventory) {
            this.pauseOnFullInventory = pauseOnFullInventory;
        }

        public boolean isTeleportDuringDropSweep() {
            return teleportDuringDropSweep;
        }

        public void setTeleportDuringDropSweep(boolean teleportDuringDropSweep) {
            this.teleportDuringDropSweep = teleportDuringDropSweep;
        }

        public boolean isVoicedDialogue() {
            return voicedDialogue;
        }

        public void setVoicedDialogue(boolean voicedDialogue) {
            this.voicedDialogue = voicedDialogue;
        }

        /**
         * Where the bot should spawn when all higher-priority checkpoints
         * (bed, recruitment anchor, BotSpawn config) fail.
         *
         * @return one of {@code "owner_bed"}, {@code "world_spawn"}, or {@code "saved_base"}.
         */
        public String getFailsafeSpawnMode() {
            if (failsafeSpawnMode == null || failsafeSpawnMode.isBlank()) {
                return "world_spawn";
            }
            String normalized = failsafeSpawnMode.trim().toLowerCase();
            return switch (normalized) {
                case "owner_bed", "saved_base" -> normalized;
                default -> "world_spawn";
            };
        }

        public void setFailsafeSpawnMode(String failsafeSpawnMode) {
            this.failsafeSpawnMode = failsafeSpawnMode;
        }

        public boolean isAutoRegroupOnLost() {
            return autoRegroupOnLost;
        }

        public void setAutoRegroupOnLost(boolean autoRegroupOnLost) {
            this.autoRegroupOnLost = autoRegroupOnLost;
        }

        /**
         * Whether this bot should automatically spawn when the world loads.
         * When {@code false}, the bot is "shelved" and must be manually spawned
         * with {@code /bot spawn}.  Defaults to {@code true}.
         */
        public boolean isAutoSpawnOnLoad() {
            return autoSpawnOnLoad;
        }

        public void setAutoSpawnOnLoad(boolean autoSpawnOnLoad) {
            this.autoSpawnOnLoad = autoSpawnOnLoad;
        }
    }

    /**
     * Persistent, non-power quest continuity for a bot.
     *
     * <p>Intentionally minimal. Quests are templates; continuity lives here.
     */
    public static class BotQuestMemory {
        private List<String> completedQuestIds = new ArrayList<>();
        private Map<String, Integer> tags = new HashMap<>();

        public List<String> getCompletedQuestIds() {
            if (completedQuestIds == null) {
                completedQuestIds = new ArrayList<>();
            }
            return completedQuestIds;
        }

        public Map<String, Integer> getTags() {
            if (tags == null) {
                tags = new HashMap<>();
            }
            return tags;
        }

        public void recordCompletion(String questId, boolean success) {
            if (questId == null || questId.isBlank()) {
                return;
            }
            // Keep order (most-recent last) but avoid duplicates.
            List<String> list = getCompletedQuestIds();
            list.removeIf(id -> id != null && id.equals(questId));
            list.add(questId);
            // Bound history to avoid config bloat.
            while (list.size() > 64) {
                list.remove(0);
            }

            // Lightweight tag hooks (optional). No power progression.
            // Example: track reliability/patience signals without schema rewrite.
            if (success) {
                bumpTag("quest_success");
            } else {
                bumpTag("quest_failure");
            }
        }

        private void bumpTag(String tag) {
            if (tag == null || tag.isBlank()) {
                return;
            }
            Map<String, Integer> map = getTags();
            map.put(tag, map.getOrDefault(tag, 0) + 1);
        }
    }

    /** Per-world gating info for the survival recruitment flow. */
    public static class SurvivalRecruitmentState {
        private boolean recruited;
        private String recruitedByUuid;
        private String recruitedByName;
        private String botAlias = "Jake";
        private long recruitedAtEpochMs;
        // Whether this world has gone through the first-time mode selection prompt.
        private boolean modeSelectionDone;
        // Last selected world mode ("questing" or "admin") for UI/status transparency.
        private String selectedWorldMode;
        // Skin policy controls (server-authoritative).
        /** Retired in 1.1.233 (the owner check decides skin changes). Kept so saved configs still load. */
        @Deprecated
        private boolean allowEveryoneSkinChange;
        private boolean allowCustomSkins;
        private boolean autonomousRescuesEnabled;
        // Granular admin-tab permissions (server-authoritative).
        // Global defaults applied to all non-operators.
        private Map<String, Boolean> adminPermissionDefaultsByKey;
        // Per-user overrides: uuid -> (permissionKey -> allowed)
        private Map<String, Map<String, Boolean>> adminPermissionOverridesByUserUuid;
        // Optional delegated players (uuid -> last known name) that may choose world mode.
        // Operators are always allowed regardless of this map.
        private Map<String, String> modeSelectionDelegatesByUuid;

        // ===== Companion questline (per-world, survival recruitment mode) =====
        // Anchor location for village improvement checks.
        private boolean companionAnchorSet;
        private String companionAnchorDimension;
        private long companionAnchorPos;

        // Progression stage for the village/companion arc.
        // 0..N (implementation-defined); stage advances only via server-side validation.
        private int companionQuestStage;
        private boolean permanentCompanion;

        // ===== Companion death / resurrection (survival recruitment mode) =====
        // When true, the recruited companion has died and must be resurrected via the ritual.
        private boolean companionDead;
        private long companionDiedAtEpochMs;
        private String companionDiedDimension;
        private long companionDiedPos;
        private int companionDeathCount;

        public boolean isRecruited() {
            return recruited;
        }

        public void setRecruited(boolean recruited) {
            this.recruited = recruited;
        }

        public String getRecruitedByUuid() {
            return recruitedByUuid;
        }

        public void setRecruitedByUuid(String recruitedByUuid) {
            this.recruitedByUuid = recruitedByUuid;
        }

        public String getRecruitedByName() {
            return recruitedByName;
        }

        public void setRecruitedByName(String recruitedByName) {
            this.recruitedByName = recruitedByName;
        }

        public String getBotAlias() {
            return (botAlias == null || botAlias.isBlank()) ? "Jake" : botAlias;
        }

        public void setBotAlias(String botAlias) {
            this.botAlias = (botAlias == null || botAlias.isBlank()) ? "Jake" : botAlias.trim();
        }

        public long getRecruitedAtEpochMs() {
            return recruitedAtEpochMs;
        }

        public void setRecruitedAtEpochMs(long recruitedAtEpochMs) {
            this.recruitedAtEpochMs = recruitedAtEpochMs;
        }

        public boolean isModeSelectionDone() {
            return modeSelectionDone;
        }

        public void setModeSelectionDone(boolean modeSelectionDone) {
            this.modeSelectionDone = modeSelectionDone;
        }

        public String getSelectedWorldMode() {
            return selectedWorldMode;
        }

        public void setSelectedWorldMode(String selectedWorldMode) {
            if (selectedWorldMode == null || selectedWorldMode.isBlank()) {
                this.selectedWorldMode = null;
                return;
            }
            String normalized = selectedWorldMode.trim().toLowerCase(Locale.ROOT);
            if (!"questing".equals(normalized) && !"admin".equals(normalized)) {
                this.selectedWorldMode = null;
                return;
            }
            this.selectedWorldMode = normalized;
        }

        @Deprecated
        public boolean isAllowEveryoneSkinChange() {
            return allowEveryoneSkinChange;
        }

        @Deprecated
        public void setAllowEveryoneSkinChange(boolean allowEveryoneSkinChange) {
            this.allowEveryoneSkinChange = allowEveryoneSkinChange;
        }

        public boolean isAllowCustomSkins() {
            return allowCustomSkins;
        }

        public void setAllowCustomSkins(boolean allowCustomSkins) {
            this.allowCustomSkins = allowCustomSkins;
        }

        public boolean isAutonomousRescuesEnabled() {
            return autonomousRescuesEnabled;
        }

        public void setAutonomousRescuesEnabled(boolean autonomousRescuesEnabled) {
            this.autonomousRescuesEnabled = autonomousRescuesEnabled;
        }

        public Map<String, Boolean> getAdminPermissionDefaultsByKey() {
            if (adminPermissionDefaultsByKey == null) {
                adminPermissionDefaultsByKey = new HashMap<>();
            }
            return adminPermissionDefaultsByKey;
        }

        public Map<String, Map<String, Boolean>> getAdminPermissionOverridesByUserUuid() {
            if (adminPermissionOverridesByUserUuid == null) {
                adminPermissionOverridesByUserUuid = new HashMap<>();
            }
            return adminPermissionOverridesByUserUuid;
        }

        public boolean getAdminPermissionDefault(String permissionKey, boolean fallbackDefault) {
            String key = normalizePermissionKey(permissionKey);
            if (key.isBlank()) {
                return fallbackDefault;
            }
            Boolean stored = getAdminPermissionDefaultsByKey().get(key);
            return stored != null ? stored : fallbackDefault;
        }

        public void setAdminPermissionDefault(String permissionKey, boolean allowed) {
            String key = normalizePermissionKey(permissionKey);
            if (key.isBlank()) {
                return;
            }
            getAdminPermissionDefaultsByKey().put(key, allowed);
        }

        public Boolean getAdminPermissionUserOverride(String uuid, String permissionKey) {
            String userKey = normalizeUuid(uuid);
            String permKey = normalizePermissionKey(permissionKey);
            if (userKey.isBlank() || permKey.isBlank()) {
                return null;
            }
            Map<String, Boolean> userMap = getAdminPermissionOverridesByUserUuid().get(userKey);
            if (userMap == null || userMap.isEmpty()) {
                return null;
            }
            return userMap.get(permKey);
        }

        public void setAdminPermissionUserOverride(String uuid, String permissionKey, boolean allowed) {
            String userKey = normalizeUuid(uuid);
            String permKey = normalizePermissionKey(permissionKey);
            if (userKey.isBlank() || permKey.isBlank()) {
                return;
            }
            Map<String, Boolean> userMap = getAdminPermissionOverridesByUserUuid()
                    .computeIfAbsent(userKey, ignored -> new HashMap<>());
            userMap.put(permKey, allowed);
        }

        public void clearAdminPermissionUserOverride(String uuid, String permissionKey) {
            String userKey = normalizeUuid(uuid);
            String permKey = normalizePermissionKey(permissionKey);
            if (userKey.isBlank() || permKey.isBlank()) {
                return;
            }
            Map<String, Boolean> userMap = getAdminPermissionOverridesByUserUuid().get(userKey);
            if (userMap == null) {
                return;
            }
            userMap.remove(permKey);
            if (userMap.isEmpty()) {
                getAdminPermissionOverridesByUserUuid().remove(userKey);
            }
        }

        public void clearAdminPermissionUserOverrides(String uuid) {
            String userKey = normalizeUuid(uuid);
            if (userKey.isBlank()) {
                return;
            }
            getAdminPermissionOverridesByUserUuid().remove(userKey);
        }

        public Map<String, String> getModeSelectionDelegatesByUuid() {
            if (modeSelectionDelegatesByUuid == null) {
                modeSelectionDelegatesByUuid = new HashMap<>();
            }
            return modeSelectionDelegatesByUuid;
        }

        public boolean canDelegateChooseWorldMode(String uuid) {
            if (uuid == null || uuid.isBlank()) {
                return false;
            }
            return getModeSelectionDelegatesByUuid().containsKey(normalizeUuid(uuid));
        }

        public void setDelegateWorldModeChoice(String uuid, String name, boolean allowed) {
            if (uuid == null || uuid.isBlank()) {
                return;
            }
            String key = normalizeUuid(uuid);
            if (!allowed) {
                getModeSelectionDelegatesByUuid().remove(key);
                return;
            }
            String displayName = (name == null || name.isBlank()) ? key : name.trim();
            getModeSelectionDelegatesByUuid().put(key, displayName);
        }

        public void clearWorldModeDelegates() {
            getModeSelectionDelegatesByUuid().clear();
        }

        private static String normalizeUuid(String uuid) {
            return uuid == null ? "" : uuid.trim().toLowerCase(Locale.ROOT);
        }

        private static String normalizePermissionKey(String key) {
            return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        }

        public boolean isCompanionAnchorSet() {
            return companionAnchorSet;
        }

        public void setCompanionAnchorSet(boolean companionAnchorSet) {
            this.companionAnchorSet = companionAnchorSet;
        }

        public String getCompanionAnchorDimension() {
            return companionAnchorDimension;
        }

        public void setCompanionAnchorDimension(String companionAnchorDimension) {
            this.companionAnchorDimension = companionAnchorDimension;
        }

        public long getCompanionAnchorPos() {
            return companionAnchorPos;
        }

        public void setCompanionAnchorPos(long companionAnchorPos) {
            this.companionAnchorPos = companionAnchorPos;
        }

        public int getCompanionQuestStage() {
            return companionQuestStage;
        }

        public void setCompanionQuestStage(int companionQuestStage) {
            this.companionQuestStage = Math.max(0, companionQuestStage);
        }

        public boolean isPermanentCompanion() {
            return permanentCompanion;
        }

        public void setPermanentCompanion(boolean permanentCompanion) {
            this.permanentCompanion = permanentCompanion;
        }

        public boolean isCompanionDead() {
            return companionDead;
        }

        public void setCompanionDead(boolean companionDead) {
            this.companionDead = companionDead;
        }

        public long getCompanionDiedAtEpochMs() {
            return companionDiedAtEpochMs;
        }

        public void setCompanionDiedAtEpochMs(long companionDiedAtEpochMs) {
            this.companionDiedAtEpochMs = Math.max(0L, companionDiedAtEpochMs);
        }

        public String getCompanionDiedDimension() {
            return companionDiedDimension;
        }

        public void setCompanionDiedDimension(String companionDiedDimension) {
            this.companionDiedDimension = companionDiedDimension;
        }

        public long getCompanionDiedPos() {
            return companionDiedPos;
        }

        public void setCompanionDiedPos(long companionDiedPos) {
            this.companionDiedPos = companionDiedPos;
        }

        public int getCompanionDeathCount() {
            return companionDeathCount;
        }

        public void setCompanionDeathCount(int companionDeathCount) {
            this.companionDeathCount = Math.max(0, companionDeathCount);
        }
    }
}
