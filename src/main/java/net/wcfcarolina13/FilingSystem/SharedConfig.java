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

    public Boolean defaultLlmWorldEnabled;
    public Boolean textDialogueEnabled;
    public Boolean voicedDialogueEnabled;
    public List<String> mutedTextCategories;
    public Boolean gameplayTipsEnabled;
    public Boolean idleHobbiesAnywhereEnabled;
    public Boolean baritonePathfinderEnabled;
    public Boolean fortifyForcePlaceEnabled;
    /** Tri-state: {@code null} means "no global override". */
    public Boolean globalTeleportDuringSkills;
    public Integer fortBufferRadius;
    public Integer undergroundLingerMinutes;
    public Integer undergroundProximityBlocks;
    public Boolean survivalRecruitmentMode;
    public Boolean soulsEnabled;
    public Boolean soulPartyEnabled;
    public Boolean soulBanterEnabled;
    public Boolean soulLocalChatEnabled;
    public Boolean soulBanterActiveEnabled;
    public Boolean soulMemoryDigestEnabled;
    public Integer dialogueScriptedRate;
    public Integer soulBanterIdleRate;
    public Integer soulBanterActiveRate;
    public Integer soulLocalRate;
    public Boolean soulVoiceEnabled;
    public Map<String, Map<String, ManualConfig.BotControlSettings>> botControlsByWorld;

    public SharedConfig() {
    }

    /** Snapshots the shared subset of {@code config}. Returns an empty snapshot for null input. */
    public static SharedConfig capture(ManualConfig config) {
        SharedConfig snapshot = new SharedConfig();
        if (config == null) {
            return snapshot;
        }
        snapshot.defaultLlmWorldEnabled = config.isDefaultLlmWorldEnabled();
        snapshot.textDialogueEnabled = config.isTextDialogueEnabled();
        snapshot.voicedDialogueEnabled = config.isVoicedDialogueEnabled();
        snapshot.mutedTextCategories = new ArrayList<>(config.getMutedTextCategories());
        snapshot.gameplayTipsEnabled = config.isGameplayTipsEnabled();
        snapshot.idleHobbiesAnywhereEnabled = config.isIdleHobbiesAnywhereEnabled();
        snapshot.baritonePathfinderEnabled = config.isBaritonePathfinderEnabled();
        snapshot.fortifyForcePlaceEnabled = config.isFortifyForcePlaceEnabled();
        snapshot.globalTeleportDuringSkills = config.getGlobalTeleportDuringSkills();
        snapshot.fortBufferRadius = config.getFortBufferRadius();
        snapshot.undergroundLingerMinutes = config.getUndergroundLingerMinutes();
        snapshot.undergroundProximityBlocks = config.getUndergroundProximityBlocks();
        snapshot.survivalRecruitmentMode = config.isSurvivalRecruitmentMode();
        snapshot.soulsEnabled = config.isSoulsEnabled();
        snapshot.soulPartyEnabled = config.isSoulPartyEnabled();
        snapshot.soulBanterEnabled = config.isSoulBanterEnabled();
        snapshot.soulLocalChatEnabled = config.isSoulLocalChatEnabled();
        snapshot.soulBanterActiveEnabled = config.isSoulBanterActiveEnabled();
        snapshot.soulMemoryDigestEnabled = config.isSoulMemoryDigestEnabled();
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
        if (defaultLlmWorldEnabled != null) config.setDefaultLlmWorldEnabled(defaultLlmWorldEnabled);
        if (textDialogueEnabled != null) config.setTextDialogueEnabled(textDialogueEnabled);
        if (voicedDialogueEnabled != null) config.setVoicedDialogueEnabled(voicedDialogueEnabled);
        if (mutedTextCategories != null) {
            List<String> live = config.getMutedTextCategories();
            live.clear();
            for (String category : mutedTextCategories) {
                if (category != null && !category.isBlank() && !live.contains(category)) {
                    live.add(category);
                }
            }
        }
        if (gameplayTipsEnabled != null) config.setGameplayTipsEnabled(gameplayTipsEnabled);
        if (idleHobbiesAnywhereEnabled != null) config.setIdleHobbiesAnywhereEnabled(idleHobbiesAnywhereEnabled);
        if (baritonePathfinderEnabled != null) config.setBaritonePathfinderEnabled(baritonePathfinderEnabled);
        if (fortifyForcePlaceEnabled != null) config.setFortifyForcePlaceEnabled(fortifyForcePlaceEnabled);
        // Tri-state: only overwritten when the sender actually carried a value.
        if (globalTeleportDuringSkills != null) config.setGlobalTeleportDuringSkills(globalTeleportDuringSkills);
        if (fortBufferRadius != null) config.setFortBufferRadius(fortBufferRadius);
        if (undergroundLingerMinutes != null) config.setUndergroundLingerMinutes(undergroundLingerMinutes);
        if (undergroundProximityBlocks != null) config.setUndergroundProximityBlocks(undergroundProximityBlocks);
        if (survivalRecruitmentMode != null) config.setSurvivalRecruitmentMode(survivalRecruitmentMode);
        if (soulsEnabled != null) config.setSoulsEnabled(soulsEnabled);
        if (soulPartyEnabled != null) config.setSoulPartyEnabled(soulPartyEnabled);
        if (soulBanterEnabled != null) config.setSoulBanterEnabled(soulBanterEnabled);
        if (soulLocalChatEnabled != null) config.setSoulLocalChatEnabled(soulLocalChatEnabled);
        if (soulBanterActiveEnabled != null) config.setSoulBanterActiveEnabled(soulBanterActiveEnabled);
        if (soulMemoryDigestEnabled != null) config.setSoulMemoryDigestEnabled(soulMemoryDigestEnabled);
        if (dialogueScriptedRate != null) config.setDialogueScriptedRate(dialogueScriptedRate);
        if (soulBanterIdleRate != null) config.setSoulBanterIdleRate(soulBanterIdleRate);
        if (soulBanterActiveRate != null) config.setSoulBanterActiveRate(soulBanterActiveRate);
        if (soulLocalRate != null) config.setSoulLocalRate(soulLocalRate);
        if (soulVoiceEnabled != null) config.setSoulVoiceEnabled(soulVoiceEnabled);
        if (botControlsByWorld != null) {
            Map<String, Map<String, ManualConfig.BotControlSettings>> live = config.getBotControlsByWorld();
            live.clear();
            live.putAll(copyBotControls(botControlsByWorld));
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
