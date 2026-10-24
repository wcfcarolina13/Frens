package net.wcfcarolina13.FilingSystem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-authoritative subset of {@link ManualConfig} that is safe to ship to every client.
 *
 * <p>Plain Gson DTO — <b>no Minecraft imports</b>. Every field is boxed so a field absent from
 * the incoming JSON leaves the target config untouched ("merge, don't clobber").
 *
 * <p>Deliberately excluded: API keys, custom-provider credentials, {@code ollamaBaseUrl},
 * bot skins, ownership/spawn data, soul-voice engine paths (host-local or secret), and
 * {@code mutedVoiceCategories} (per-player mask, synced on its own channel).
 */
public class SharedConfig {

    public Boolean textDialogueEnabled;
    public Boolean voicedDialogueEnabled;
    public List<String> mutedTextCategories;
    public Boolean gameplayTipsEnabled;
    public Boolean idleHobbiesAnywhereEnabled;
    public Boolean botsLogOffWhenCantSleep;
    /** Legacy flag, still sent for older peers. Applying ignores it: the finder follows {@link #pathfinder}. */
    public Boolean baritonePathfinderEnabled;
    /** Saved finder choice since 1.1.261: "unified", "baritone", "classic", or null for the default. */
    public String pathfinder;
    /** Null on older peers, whose non-null token is treated as an explicit choice. */
    public Boolean pathfinderExplicitlySet;
    public Boolean fortifyForcePlaceEnabled;
    /** Tri-state: {@code null} means "no global override". */
    public Boolean globalTeleportDuringSkills;
    public Integer fortBufferRadius;
    public Integer undergroundLingerMinutes;
    public Integer undergroundProximityBlocks;
    public Boolean survivalRecruitmentMode;
    public Boolean questingModeEnabled;
    public Boolean soulsEnabled;
    public Boolean soulPartyEnabled;
    public Boolean soulBanterEnabled;
    public Boolean soulLocalChatEnabled;
    public Boolean soulBanterActiveEnabled;
    public Boolean soulMemoryDigestEnabled;
    public Boolean soulNoveltyRejectionEnabled;
    public Boolean soulRelationsEnabled;
    public Boolean soulStructuredOutputEnabled;
    public Integer dialogueScriptedRate;
    public Integer soulBanterIdleRate;
    public Integer soulBanterActiveRate;
    public Integer soulLocalRate;
    public Boolean soulVoiceEnabled;
    public Map<String, Map<String, ManualConfig.BotControlSettings>> botControlsByWorld;
    /**
     * The sending server's current world key, so a client on a remote server edits
     * {@code botControlsByWorld[alias][serverWorldKey]}. Set only on server to client sends,
     * never by {@link #capture}, so a client save never carries one.
     */
    public String serverWorldKey;

    public SharedConfig() {
    }

    /** Snapshots the shared subset of {@code config}. Returns an empty snapshot for null input. */
    public static SharedConfig capture(ManualConfig config) {
        SharedConfig snapshot = new SharedConfig();
        if (config == null) {
            return snapshot;
        }
        snapshot.textDialogueEnabled = config.isTextDialogueEnabled();
        snapshot.voicedDialogueEnabled = config.isVoicedDialogueEnabled();
        snapshot.mutedTextCategories = new ArrayList<>(config.getMutedTextCategories());
        snapshot.gameplayTipsEnabled = config.isGameplayTipsEnabled();
        snapshot.idleHobbiesAnywhereEnabled = config.isIdleHobbiesAnywhereEnabled();
        snapshot.botsLogOffWhenCantSleep = config.isBotsLogOffWhenCantSleepEnabled();
        snapshot.baritonePathfinderEnabled = config.isBaritonePathfinderEnabled();
        // Always an explicit token ("unified" for the default): Gson drops nulls, so a null here
        // would let the receiver keep a stale pick of its own.
        snapshot.pathfinder = net.wcfcarolina13.PathFinding.PathFinderDefaultPolicy.toSaved(config.getPathfinderChoice());
        snapshot.pathfinderExplicitlySet = config.isPathfinderExplicitlySet();
        snapshot.fortifyForcePlaceEnabled = config.isFortifyForcePlaceEnabled();
        snapshot.globalTeleportDuringSkills = config.getGlobalTeleportDuringSkills();
        snapshot.fortBufferRadius = config.getFortBufferRadius();
        snapshot.undergroundLingerMinutes = config.getUndergroundLingerMinutes();
        snapshot.undergroundProximityBlocks = config.getUndergroundProximityBlocks();
        snapshot.survivalRecruitmentMode = config.isSurvivalRecruitmentMode();
        snapshot.questingModeEnabled = config.isQuestingModeEnabled();
        snapshot.soulsEnabled = config.isSoulsEnabled();
        snapshot.soulPartyEnabled = config.isSoulPartyEnabled();
        snapshot.soulBanterEnabled = config.isSoulBanterEnabled();
        snapshot.soulLocalChatEnabled = config.isSoulLocalChatEnabled();
        snapshot.soulBanterActiveEnabled = config.isSoulBanterActiveEnabled();
        snapshot.soulMemoryDigestEnabled = config.isSoulMemoryDigestEnabled();
        snapshot.soulNoveltyRejectionEnabled = config.isSoulNoveltyRejectionEnabled();
        snapshot.soulRelationsEnabled = config.isSoulRelationsEnabled();
        snapshot.soulStructuredOutputEnabled = config.isSoulStructuredOutputEnabled();
        snapshot.dialogueScriptedRate = config.getDialogueScriptedRate();
        snapshot.soulBanterIdleRate = config.getSoulBanterIdleRate();
        snapshot.soulBanterActiveRate = config.getSoulBanterActiveRate();
        snapshot.soulLocalRate = config.getSoulLocalRate();
        snapshot.soulVoiceEnabled = config.isSoulVoiceEnabled();
        snapshot.botControlsByWorld = copyBotControls(config.getBotControlsByWorld());
        return snapshot;
    }

    /** Applies every non-null field of this snapshot onto {@code config}. No-op for null input. */
    public void applyTo(ManualConfig config) {
        if (config == null) {
            return;
        }
        if (textDialogueEnabled != null) config.setTextDialogueEnabled(textDialogueEnabled);
        if (voicedDialogueEnabled != null) config.setVoicedDialogueEnabled(voicedDialogueEnabled);
        if (mutedTextCategories != null) {
            // Copy into a temporary FIRST so capture(cfg).applyTo(cfg) (same instance, or a
            // snapshot sharing the live list) cannot clear the source out from under us.
            List<String> staged = new ArrayList<>();
            for (String category : mutedTextCategories) {
                if (category != null && !category.isBlank() && !staged.contains(category)) {
                    staged.add(category);
                }
            }
            List<String> live = config.getMutedTextCategories();
            live.clear();
            live.addAll(staged);
        }
        if (gameplayTipsEnabled != null) config.setGameplayTipsEnabled(gameplayTipsEnabled);
        if (idleHobbiesAnywhereEnabled != null) config.setIdleHobbiesAnywhereEnabled(idleHobbiesAnywhereEnabled);
        if (botsLogOffWhenCantSleep != null) config.setBotsLogOffWhenCantSleepEnabled(botsLogOffWhenCantSleep);
        // The legacy boolean is not applied: an older peer resends it with every save, and it
        // cannot say "unified". A null string (a peer older than 1.1.261) leaves ours.
        if (pathfinder != null) config.setPathfinder(pathfinder, !Boolean.FALSE.equals(pathfinderExplicitlySet));
        if (fortifyForcePlaceEnabled != null) config.setFortifyForcePlaceEnabled(fortifyForcePlaceEnabled);
        // Tri-state: only overwritten when the sender actually carried a value.
        if (globalTeleportDuringSkills != null) config.setGlobalTeleportDuringSkills(globalTeleportDuringSkills);
        if (fortBufferRadius != null) config.setFortBufferRadius(fortBufferRadius);
        if (undergroundLingerMinutes != null) config.setUndergroundLingerMinutes(undergroundLingerMinutes);
        if (undergroundProximityBlocks != null) config.setUndergroundProximityBlocks(undergroundProximityBlocks);
        if (survivalRecruitmentMode != null) config.setSurvivalRecruitmentMode(survivalRecruitmentMode);
        if (questingModeEnabled != null) config.setQuestingModeEnabled(questingModeEnabled);
        if (soulsEnabled != null) config.setSoulsEnabled(soulsEnabled);
        if (soulPartyEnabled != null) config.setSoulPartyEnabled(soulPartyEnabled);
        if (soulBanterEnabled != null) config.setSoulBanterEnabled(soulBanterEnabled);
        if (soulLocalChatEnabled != null) config.setSoulLocalChatEnabled(soulLocalChatEnabled);
        if (soulBanterActiveEnabled != null) config.setSoulBanterActiveEnabled(soulBanterActiveEnabled);
        if (soulMemoryDigestEnabled != null) config.setSoulMemoryDigestEnabled(soulMemoryDigestEnabled);
        if (soulNoveltyRejectionEnabled != null) config.setSoulNoveltyRejectionEnabled(soulNoveltyRejectionEnabled);
        if (soulRelationsEnabled != null) config.setSoulRelationsEnabled(soulRelationsEnabled);
        if (soulStructuredOutputEnabled != null) config.setSoulStructuredOutputEnabled(soulStructuredOutputEnabled);
        if (dialogueScriptedRate != null) config.setDialogueScriptedRate(dialogueScriptedRate);
        if (soulBanterIdleRate != null) config.setSoulBanterIdleRate(soulBanterIdleRate);
        if (soulBanterActiveRate != null) config.setSoulBanterActiveRate(soulBanterActiveRate);
        if (soulLocalRate != null) config.setSoulLocalRate(soulLocalRate);
        if (soulVoiceEnabled != null) config.setSoulVoiceEnabled(soulVoiceEnabled);
        if (botControlsByWorld != null) {
            Map<String, Map<String, ManualConfig.BotControlSettings>> staged = copyBotControls(botControlsByWorld);
            Map<String, Map<String, ManualConfig.BotControlSettings>> live = config.getBotControlsByWorld();
            live.clear();
            live.putAll(staged);
        }
        // Only a client holding a remote server's config keeps the key (single player and the
        // server itself read their own world key directly).
        if (serverWorldKey != null && !serverWorldKey.isBlank() && config.isRemoteAuthoritative()) {
            config.setRemoteWorldKey(serverWorldKey);
        }
    }

    private static Map<String, Map<String, ManualConfig.BotControlSettings>> copyBotControls(
            Map<String, Map<String, ManualConfig.BotControlSettings>> source) {
        Map<String, Map<String, ManualConfig.BotControlSettings>> copy = new HashMap<>();
        if (source == null) {
            return copy;
        }
        for (Map.Entry<String, Map<String, ManualConfig.BotControlSettings>> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            copy.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        return copy;
    }
}
