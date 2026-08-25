package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.FilingSystem.ManualConfig;

/**
 * Validated, immutable snapshot of the soul generated-voice settings derived from
 * {@link ManualConfig} — mirrors {@code SoulSettings.from}. Structural validation only
 * (non-blank paths); file existence is checked by the enable command and at engine start,
 * never here (this must stay pure).
 */
public record SoulVoiceSettings(boolean enabled, boolean valid, String validationError,
                                String piperBinary, String voiceModel, int maxChars,
                                long synthTimeoutMs, float radioGain) {

    public static SoulVoiceSettings from(ManualConfig config) {
        if (config == null) {
            return new SoulVoiceSettings(false, false, "Frens configuration is unavailable.",
                    "", "", 400, 8000L, 0.6f);
        }
        boolean enabled = config.isSoulVoiceEnabled();
        String binary = config.getSoulVoicePiperBinary();
        String model = config.getSoulVoiceModel();
        int maxChars = config.getSoulVoiceMaxChars();
        long timeoutMs = config.getSoulVoiceSynthTimeoutMs();
        float radioGain = config.getSoulVoiceRadioGain();
        if (binary.isBlank()) {
            return new SoulVoiceSettings(enabled, false, "Configure the piper binary path first.",
                    binary, model, maxChars, timeoutMs, radioGain);
        }
        if (model.isBlank()) {
            return new SoulVoiceSettings(enabled, false, "Configure a piper voice model first.",
                    binary, model, maxChars, timeoutMs, radioGain);
        }
        return new SoulVoiceSettings(enabled, true, "", binary, model, maxChars, timeoutMs, radioGain);
    }

}
