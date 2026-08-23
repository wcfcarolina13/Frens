package net.wcfcarolina13.GameAI.souls;

import net.wcfcarolina13.FilingSystem.ManualConfig;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/**
 * Validated, immutable snapshot of the soul-communication settings derived from
 * {@link ManualConfig}. Never consults hosted-provider keys — the pilot is local-Ollama-only.
 */
public record SoulSettings(boolean enabled, boolean valid, String validationError,
                            String provider, String model, URI ollamaBaseUri,
                            Duration timeout, int queueCapacity) {
    public static SoulSettings from(ManualConfig config) {
        if (config == null) {
            return new SoulSettings(false, false, "Frens configuration is unavailable.",
                    "ollama", "", URI.create("http://127.0.0.1:11434"),
                    Duration.ofSeconds(60), 8);
        }
        boolean enabled = config.isSoulsEnabled();
        String configuredProvider = config.getSoulProvider();
        String provider = configuredProvider == null || configuredProvider.isBlank()
                ? "ollama" : configuredProvider.trim().toLowerCase(Locale.ROOT);
        String model = config.getSoulModel() == null ? "" : config.getSoulModel().trim();
        int timeoutSeconds = Math.max(10, Math.min(180, config.getSoulRequestTimeoutSeconds()));
        int queueCapacity = Math.max(1, Math.min(32, config.getSoulQueueCapacity()));
        URI baseUri;
        try {
            baseUri = URI.create(config.getOllamaBaseUrl().trim());
        } catch (RuntimeException ex) {
            return new SoulSettings(enabled, false, "The Ollama base URL is invalid.",
                    provider, model, URI.create("http://127.0.0.1:11434"),
                    Duration.ofSeconds(timeoutSeconds), queueCapacity);
        }
        if (!"ollama".equals(provider)) {
            return new SoulSettings(enabled, false,
                    "Only the local ollama provider is supported by the pilot.",
                    provider, model, baseUri, Duration.ofSeconds(timeoutSeconds), queueCapacity);
        }
        if (model.isBlank()) {
            return new SoulSettings(enabled, false, "Configure a local soul model first.",
                    provider, model, baseUri, Duration.ofSeconds(timeoutSeconds), queueCapacity);
        }
        String scheme = baseUri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return new SoulSettings(enabled, false, "The Ollama URL must use HTTP or HTTPS.",
                    provider, model, baseUri, Duration.ofSeconds(timeoutSeconds), queueCapacity);
        }
        return new SoulSettings(enabled, true, "", provider, model, baseUri,
                Duration.ofSeconds(timeoutSeconds), queueCapacity);
    }
}
