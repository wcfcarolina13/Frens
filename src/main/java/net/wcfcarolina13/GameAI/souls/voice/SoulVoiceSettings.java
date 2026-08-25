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

    /**
     * Package-private factory for testing — bypasses ManualConfig construction.
     * Takes plain values and applies clamping rules.
     */
    static SoulVoiceSettings of(boolean enabled, String piperBinary, String voiceModel,
                                 int maxChars, long synthTimeoutMs, float radioGain) {
        int clampedMaxChars = Math.max(40, Math.min(1000, maxChars));
        long clampedTimeoutMs = Math.max(1000L, Math.min(30_000L, synthTimeoutMs));
        float clampedRadioGain = Math.max(0.0f, Math.min(1.0f, radioGain));

        if (piperBinary == null) piperBinary = "";
        if (voiceModel == null) voiceModel = "";
        piperBinary = piperBinary.trim();
        voiceModel = voiceModel.trim();

        boolean valid = !piperBinary.isBlank() && !voiceModel.isBlank();
        String validationError = "";
        if (!valid) {
            if (piperBinary.isBlank()) {
                validationError = "Configure the piper binary path first.";
            } else if (voiceModel.isBlank()) {
                validationError = "Configure a piper voice model first.";
            }
        }

        return new SoulVoiceSettings(enabled, valid, validationError,
                piperBinary, voiceModel, clampedMaxChars, clampedTimeoutMs, clampedRadioGain);
    }
}
