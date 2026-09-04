package net.wcfcarolina13.GameAI.souls;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SoulFoundationTest {

    /**
     * Builds a real (non-mocked) ManualConfig instance via its private constructor.
     * ManualConfig() only assigns selectedLanguageModel from a system property — no file I/O,
     * no FILE_PATH resolution — so this is safe to call from a plain unit test JVM.
     */
    private static ManualConfig newRealConfig() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    @Test
    void rejectsHostedProviderAndDefaultsToDisabled() {
        ManualConfig config = mock(ManualConfig.class);
        when(config.isSoulsEnabled()).thenReturn(false);
        when(config.getSoulProvider()).thenReturn("openai");
        when(config.getSoulModel()).thenReturn("remote-model");
        when(config.getOllamaBaseUrl()).thenReturn("http://127.0.0.1:11434");
        when(config.getSoulRequestTimeoutSeconds()).thenReturn(60);
        when(config.getSoulQueueCapacity()).thenReturn(8);

        SoulSettings settings = SoulSettings.from(config);

        assertFalse(settings.enabled());
        assertFalse(settings.valid());
        assertEquals("Only the local ollama provider is supported by the pilot.", settings.validationError());
    }

    @Test
    void conversationKeyUsesUuidNotDisplayName() {
        UUID bot = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
                bot, player, SoulTypes.Channel.DIRECT);
        assertEquals(bot, key.botId());
        assertEquals(player, key.playerId());
    }

    @Test
    void nullConfigIsUnavailableAndDisabled() {
        SoulSettings settings = SoulSettings.from(null);

        assertFalse(settings.enabled());
        assertFalse(settings.valid());
        assertEquals("Frens configuration is unavailable.", settings.validationError());
        assertEquals("ollama", settings.provider());
    }

    @Test
    void blankModelIsRejected() {
        ManualConfig config = mock(ManualConfig.class);
        when(config.isSoulsEnabled()).thenReturn(true);
        when(config.getSoulProvider()).thenReturn("ollama");
        when(config.getSoulModel()).thenReturn("   ");
        when(config.getOllamaBaseUrl()).thenReturn("http://127.0.0.1:11434");
        when(config.getSoulRequestTimeoutSeconds()).thenReturn(60);
        when(config.getSoulQueueCapacity()).thenReturn(8);

        SoulSettings settings = SoulSettings.from(config);

        assertFalse(settings.valid());
        assertEquals("Configure a local soul model first.", settings.validationError());
    }

    @Test
    void nonHttpSchemeIsRejected() {
        ManualConfig config = mock(ManualConfig.class);
        when(config.isSoulsEnabled()).thenReturn(true);
        when(config.getSoulProvider()).thenReturn("ollama");
        when(config.getSoulModel()).thenReturn("llama3");
        when(config.getOllamaBaseUrl()).thenReturn("ftp://127.0.0.1:11434");
        when(config.getSoulRequestTimeoutSeconds()).thenReturn(60);
        when(config.getSoulQueueCapacity()).thenReturn(8);

        SoulSettings settings = SoulSettings.from(config);

        assertFalse(settings.valid());
        assertEquals("The Ollama URL must use HTTP or HTTPS.", settings.validationError());
    }

    @Test
    void malformedBaseUrlIsRejected() {
        ManualConfig config = mock(ManualConfig.class);
        when(config.isSoulsEnabled()).thenReturn(true);
        when(config.getSoulProvider()).thenReturn("ollama");
        when(config.getSoulModel()).thenReturn("llama3");
        when(config.getOllamaBaseUrl()).thenReturn("http://[invalid-host");
        when(config.getSoulRequestTimeoutSeconds()).thenReturn(60);
        when(config.getSoulQueueCapacity()).thenReturn(8);

        SoulSettings settings = SoulSettings.from(config);

        assertFalse(settings.valid());
        assertEquals("The Ollama base URL is invalid.", settings.validationError());
    }

    @Test
    void validLocalOllamaConfigProducesValidSettings() {
        ManualConfig config = mock(ManualConfig.class);
        when(config.isSoulsEnabled()).thenReturn(true);
        when(config.getSoulProvider()).thenReturn("ollama");
        when(config.getSoulModel()).thenReturn("llama3");
        when(config.getOllamaBaseUrl()).thenReturn("http://127.0.0.1:11434");
        when(config.getSoulRequestTimeoutSeconds()).thenReturn(60);
        when(config.getSoulQueueCapacity()).thenReturn(8);

        SoulSettings settings = SoulSettings.from(config);

        assertTrue(settings.valid());
        assertEquals("", settings.validationError());
        assertTrue(settings.enabled());
        assertEquals("ollama", settings.provider());
        assertEquals("llama3", settings.model());
    }

    @Test
    void blankProviderNormalizesToOllama() {
        ManualConfig config = mock(ManualConfig.class);
        when(config.isSoulsEnabled()).thenReturn(true);
        when(config.getSoulProvider()).thenReturn("   ");
        when(config.getSoulModel()).thenReturn("llama3");
        when(config.getOllamaBaseUrl()).thenReturn("http://127.0.0.1:11434");
        when(config.getSoulRequestTimeoutSeconds()).thenReturn(60);
        when(config.getSoulQueueCapacity()).thenReturn(8);

        SoulSettings settings = SoulSettings.from(config);

        assertEquals("ollama", settings.provider());
        assertTrue(settings.valid());
    }

    @Test
    void modelNameIsTrimmed() {
        ManualConfig config = mock(ManualConfig.class);
        when(config.isSoulsEnabled()).thenReturn(true);
        when(config.getSoulProvider()).thenReturn("ollama");
        when(config.getSoulModel()).thenReturn("  llama3  ");
        when(config.getOllamaBaseUrl()).thenReturn("http://127.0.0.1:11434");
        when(config.getSoulRequestTimeoutSeconds()).thenReturn(60);
        when(config.getSoulQueueCapacity()).thenReturn(8);

        SoulSettings settings = SoulSettings.from(config);

        assertEquals("llama3", settings.model());
    }

    @Test
    void timeoutSecondsAreClampedToBoundedRange() {
        ManualConfig config = mock(ManualConfig.class);
        when(config.isSoulsEnabled()).thenReturn(true);
        when(config.getSoulProvider()).thenReturn("ollama");
        when(config.getSoulModel()).thenReturn("llama3");
        when(config.getOllamaBaseUrl()).thenReturn("http://127.0.0.1:11434");
        when(config.getSoulRequestTimeoutSeconds()).thenReturn(5);
        when(config.getSoulQueueCapacity()).thenReturn(8);

        SoulSettings settings = SoulSettings.from(config);

        assertEquals(10, settings.timeout().toSeconds());
    }

    @Test
    void queueCapacityIsClampedToBoundedRange() {
        ManualConfig config = mock(ManualConfig.class);
        when(config.isSoulsEnabled()).thenReturn(true);
        when(config.getSoulProvider()).thenReturn("ollama");
        when(config.getSoulModel()).thenReturn("llama3");
        when(config.getOllamaBaseUrl()).thenReturn("http://127.0.0.1:11434");
        when(config.getSoulRequestTimeoutSeconds()).thenReturn(60);
        when(config.getSoulQueueCapacity()).thenReturn(999);

        SoulSettings settings = SoulSettings.from(config);

        assertEquals(32, settings.queueCapacity());
    }

    @Test
    void soulProfileDefensivelyCopiesListsAndRejectsMutation() {
        List<String> identity = new java.util.ArrayList<>(List.of("curious", "loyal"));
        SoulTypes.SoulProfile profile = new SoulTypes.SoulProfile(
                "jake", "Jake", identity, List.of(), List.of(), List.of());

        identity.add("mutated-after-construction");

        assertEquals(2, profile.identity().size());
        assertThrows(UnsupportedOperationException.class, () -> profile.identity().add("nope"));
    }

    @Test
    void conversationKeyRejectsNullBotId() {
        assertThrows(NullPointerException.class, () ->
                new SoulTypes.ConversationKey(null, UUID.randomUUID(), SoulTypes.Channel.DIRECT));
    }

    // === Real ManualConfig accessor coverage ===
    // Every test above mocks ManualConfig, so the real clamp/normalize/trim bodies never run.
    // These exercise the actual field-backed accessors on a real instance.

    @Test
    void freshConfigHasSoulsDisabledByDefault() throws Exception {
        ManualConfig config = newRealConfig();
        assertFalse(config.isSoulsEnabled());
    }

    @Test
    void realTimeoutSetterClampsBelowMinimum() throws Exception {
        ManualConfig config = newRealConfig();
        config.setSoulRequestTimeoutSeconds(1);
        assertEquals(10, config.getSoulRequestTimeoutSeconds());
    }

    @Test
    void realTimeoutSetterClampsAboveMaximum() throws Exception {
        ManualConfig config = newRealConfig();
        config.setSoulRequestTimeoutSeconds(999);
        assertEquals(180, config.getSoulRequestTimeoutSeconds());
    }

    @Test
    void realQueueCapacitySetterClampsBelowMinimum() throws Exception {
        ManualConfig config = newRealConfig();
        config.setSoulQueueCapacity(0);
        assertEquals(1, config.getSoulQueueCapacity());
    }

    @Test
    void realQueueCapacitySetterClampsAboveMaximum() throws Exception {
        ManualConfig config = newRealConfig();
        config.setSoulQueueCapacity(999);
        assertEquals(32, config.getSoulQueueCapacity());
    }

    @Test
    void realProviderSetterNormalizesBlankToOllama() throws Exception {
        ManualConfig config = newRealConfig();
        config.setSoulProvider("   ");
        assertEquals("ollama", config.getSoulProvider());
    }

    @Test
    void realProviderSetterNormalizesNullToOllama() throws Exception {
        ManualConfig config = newRealConfig();
        config.setSoulProvider(null);
        assertEquals("ollama", config.getSoulProvider());
    }

    @Test
    void realModelSetterTrimsWhitespace() throws Exception {
        ManualConfig config = newRealConfig();
        config.setSoulModel("  llama3  ");
        assertEquals("llama3", config.getSoulModel());
    }

    @Test
    void mindAndRecordCompatConstructorsDefaultNewFields() {
        SoulTypes.SoulMind legacy = new SoulTypes.SoulMind(1, SoulTypes.Stance.BASELINE, List.of(), List.of(),
                Set.of(), 0L, -1, -1);
        assertTrue(legacy.playerMemories().isEmpty());
        assertTrue(legacy.archivedPlayerMemories().isEmpty());
        assertTrue(legacy.digestCursors().isEmpty());
        assertEquals(legacy, SoulTypes.SoulMind.empty());

        SoulTypes.ConversationRecord rec = new SoulTypes.ConversationRecord(UUID.randomUUID(), 0L, 0L,
                SoulTypes.TurnKind.HEARD, "hi", Instant.EPOCH, "", "", null, null);
        assertTrue(rec.participants().isEmpty());

        SoulTypes.PlayerMemory pm = new SoulTypes.PlayerMemory(UUID.randomUUID(), 3, "  Roti hates the Nether ", 10, -1, null);
        assertEquals("Roti hates the Nether", pm.fact());
        assertTrue(pm.sourceCorrelationIds().isEmpty());
    }
}
