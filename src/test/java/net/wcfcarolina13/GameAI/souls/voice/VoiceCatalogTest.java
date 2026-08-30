package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceCatalogTest {
    @Test
    void pocketCatalogHasTheEnglishPresets() {
        assertEquals(21, VoiceCatalog.POCKET_VOICES.size());
        assertTrue(VoiceCatalog.isKnown("pocket", "charles"));
        assertFalse(VoiceCatalog.isKnown("pocket", "giovanni"), "non-English presets are not offered");
        assertFalse(VoiceCatalog.needsDownload("pocket"));
        assertTrue(VoiceCatalog.needsDownload("piper"));
        assertEquals(VoiceCatalog.POCKET_VOICES.size(), VoiceCatalog.forEngine("pocket").size());
        assertFalse(VoiceCatalog.forEngine("piper").isEmpty());
        assertTrue(VoiceCatalog.forEngine("dreamsleeve").isEmpty());
    }
}
