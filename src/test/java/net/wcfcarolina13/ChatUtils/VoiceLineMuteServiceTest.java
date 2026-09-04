package net.wcfcarolina13.ChatUtils;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-player voice mute masks. Never touches {@code Frens} — the baseline config is injected
 * through the {@code setBaselineSupplier} test seam.
 */
class VoiceLineMuteServiceTest {

    private ManualConfig baseline;

    private static ManualConfig newRealConfig() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    @BeforeEach
    void setUp() throws Exception {
        baseline = newRealConfig();
        VoiceLineMuteService.setBaselineSupplier(() -> baseline);
        VoiceLineMuteService.clearAllPlayerMasks();
    }

    @AfterEach
    void tearDown() {
        VoiceLineMuteService.clearAllPlayerMasks();
        VoiceLineMuteService.resetBaselineSupplier();
    }

    @Test
    void nullCategoryIsNeverMuted() {
        assertFalse(VoiceLineMuteService.isMutedFor(null, UUID.randomUUID()));
        assertFalse(VoiceLineMuteService.isMutedFor(null, null));
    }

    @Test
    void baselineMuteAppliesToEveryViewerAndToNull() {
        baseline.setVoiceCategoryMuted(VoiceLineCategory.COMBAT_ALERTS.id(), true);

        assertTrue(VoiceLineMuteService.isMutedFor(VoiceLineCategory.COMBAT_ALERTS, null));
        assertTrue(VoiceLineMuteService.isMutedFor(VoiceLineCategory.COMBAT_ALERTS, UUID.randomUUID()));
        assertFalse(VoiceLineMuteService.isMutedFor(VoiceLineCategory.REACTIONS, UUID.randomUUID()));
    }

    @Test
    void playerMaskMutesOnlyThatPlayer() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        VoiceLineMuteService.setPlayerMask(u1, List.of(VoiceLineCategory.REACTIONS.id()));

        assertTrue(VoiceLineMuteService.isMutedFor(VoiceLineCategory.REACTIONS, u1));
        assertFalse(VoiceLineMuteService.isMutedFor(VoiceLineCategory.REACTIONS, u2));
        assertFalse(VoiceLineMuteService.isMutedFor(VoiceLineCategory.SKILL_TASK, u1));
    }

    @Test
    void clearPlayerMaskRestoresAudibility() {
        UUID u1 = UUID.randomUUID();
        VoiceLineMuteService.setPlayerMask(u1, List.of(VoiceLineCategory.REACTIONS.id()));
        assertTrue(VoiceLineMuteService.isMutedFor(VoiceLineCategory.REACTIONS, u1));

        VoiceLineMuteService.clearPlayerMask(u1);
        assertFalse(VoiceLineMuteService.isMutedFor(VoiceLineCategory.REACTIONS, u1));
    }

    @Test
    void nullViewerUsesBaselineOnlyAndIgnoresPlayerMasks() {
        UUID u1 = UUID.randomUUID();
        VoiceLineMuteService.setPlayerMask(u1, List.of(VoiceLineCategory.REACTIONS.id()));

        assertFalse(VoiceLineMuteService.isMutedFor(VoiceLineCategory.REACTIONS, null));
    }

    @Test
    void storedMaskIsAnUnmodifiableCopyAndIgnoresUnknownIds() {
        UUID u1 = UUID.randomUUID();
        VoiceLineMuteService.setPlayerMask(u1, List.of(VoiceLineCategory.REACTIONS.id(), "not_a_category"));

        assertTrue(VoiceLineMuteService.playerMask(u1).contains(VoiceLineCategory.REACTIONS.id()));
        assertFalse(VoiceLineMuteService.playerMask(u1).contains("not_a_category"));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> VoiceLineMuteService.playerMask(u1).add("general"));
    }
}
