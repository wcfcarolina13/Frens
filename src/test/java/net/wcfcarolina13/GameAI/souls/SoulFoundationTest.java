package net.wcfcarolina13.GameAI.souls;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SoulFoundationTest {

    static {
        // mockito-inline 5.2.0 bundles a Byte Buddy release that predates official Java 21
        // (class file version 65) support. Byte Buddy reads this system property once, at
        // net.bytebuddy.utility.OpenedClassReader's own class-init time, to opt into
        // "experimental" support for newer bytecode versions — this is the exact workaround
        // Byte Buddy's own exception message names ("set net.bytebuddy.experimental as a VM
        // property"). Setting it here (rather than as a build.gradle JVM arg) keeps the fix
        // scoped to this test; no other test in the suite uses Mockito.
        System.setProperty("net.bytebuddy.experimental", "true");
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
}
