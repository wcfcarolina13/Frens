package net.wcfcarolina13.GameAI.souls.voice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Engine-neutral view of the voices a soul can be assigned, used by {@code /bot soul voice}.
 * Piper voices are downloadable files (see {@link PiperInstaller#VOICE_CATALOG}); Pocket TTS ships its
 * English presets inside the package, so they need no download. Dreamsleeve clones a reference clip and
 * has no catalogue at all.
 */
public final class VoiceCatalog {

    /** One assignable voice: its {@code VoiceSpec.voice()} name and a one-line description. */
    public record Entry(String name, String description) {}

    /** Pocket TTS English presets. Non-English presets (giovanni, lola, juergen, rafael, estelle) are not offered. */
    private static final Map<String, String> POCKET = pocketPresets();

    /** Pocket preset names, sorted. */
    public static final List<String> POCKET_VOICES = List.copyOf(POCKET.keySet());

    private VoiceCatalog() {
    }

    /** Voices assignable under {@code engine}; empty for engines without a catalogue (dreamsleeve, unknown). */
    public static List<Entry> forEngine(String engine) {
        switch (normalize(engine)) {
            case SoulVoiceSettings.ENGINE_POCKET -> {
                List<Entry> entries = new ArrayList<>(POCKET.size());
                POCKET.forEach((name, description) -> entries.add(new Entry(name, description)));
                return List.copyOf(entries);
            }
            case SoulVoiceSettings.ENGINE_PIPER -> {
                List<Entry> entries = new ArrayList<>(PiperInstaller.VOICE_CATALOG.size());
                for (PiperInstaller.CatalogVoice voice : PiperInstaller.VOICE_CATALOG) {
                    entries.add(new Entry(voice.name(), voice.description()));
                }
                return List.copyOf(entries);
            }
            default -> {
                return List.of();
            }
        }
    }

    /** True when {@code name} is a catalogue voice for {@code engine} (case-insensitive). */
    public static boolean isKnown(String engine, String name) {
        String want = name == null ? "" : name.trim();
        if (want.isEmpty()) {
            return false;
        }
        for (Entry entry : forEngine(engine)) {
            if (entry.name().equalsIgnoreCase(want)) {
                return true;
            }
        }
        return false;
    }

    /** True only for engines whose voices are files fetched by {@code /bot soul voice install}. */
    public static boolean needsDownload(String engine) {
        return SoulVoiceSettings.ENGINE_PIPER.equals(normalize(engine));
    }

    private static String normalize(String engine) {
        return engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> pocketPresets() {
        Map<String, String> presets = new LinkedHashMap<>();
        presets.put("alba", "warm Scottish woman");
        presets.put("anna", "bright young woman");
        presets.put("azelma", "soft-spoken woman");
        presets.put("bill_boerst", "older American man");
        presets.put("caro_davy", "calm woman");
        presets.put("charles", "steady English man");
        presets.put("cosette", "hesitant young woman");
        presets.put("eponine", "quick young woman");
        presets.put("eve", "low even woman");
        presets.put("fantine", "gentle woman");
        presets.put("george", "gruff older man");
        presets.put("jane", "clear woman");
        presets.put("javert", "deep stern man");
        presets.put("jean", "dry older man");
        presets.put("marius", "light young man");
        presets.put("mary", "matter-of-fact woman");
        presets.put("michael", "easy-going man");
        presets.put("paul", "plain-spoken man");
        presets.put("peter_yearsley", "British man");
        presets.put("stuart_bell", "brisk man");
        presets.put("vera", "measured woman");
        return presets;
    }
}
