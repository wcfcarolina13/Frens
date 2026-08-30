package net.wcfcarolina13.GameAI.souls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and holds the immutable {@link SoulTypes.SoulProfile} definitions available to the
 * soul-communication pipeline.
 *
 * <p>Built-in profiles ship as JSON resources under {@code data/frens/souls/} and are loaded
 * from the mod classloader via {@link #loadBuiltIns()}. Additional profiles (e.g. future
 * player-authored or admin-authored souls) can be added at runtime via {@link #register}.
 * Registration is defensive: blank ids and duplicate ids are rejected so a bad profile can
 * never silently shadow another one.
 *
 * <p>This class is a static registry (like {@code SkillManager}) rather than an injectable
 * instance, since soul profiles are process-wide, immutable, read-mostly data — there is no
 * per-world or per-bot state here to isolate.
 */
public final class SoulProfileRegistry {

    private static final String BUILT_IN_JAKE_RESOURCE = "data/frens/souls/jake.json";
    private static final String BUILT_IN_BOB_RESOURCE = "data/frens/souls/bob.json";

    private static final Map<String, SoulTypes.SoulProfile> PROFILES = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile boolean builtInsLoaded = false;

    private SoulProfileRegistry() {
        // Static registry — not instantiable.
    }

    /**
     * Loads the mod's built-in soul profiles (currently just Jake) from classpath resources.
     * Safe to call more than once — subsequent calls are no-ops once the built-ins are loaded.
     */
    public static synchronized void loadBuiltIns() {
        if (builtInsLoaded) {
            return;
        }
        // Each built-in registers independently: a fault in one resource must not leave the
        // registry half-loaded with the loaded flag unset, where a retry would then throw
        // "Duplicate soul profile id" for the profile that DID load (review minor). A profile
        // that fails to parse is logged and skipped; the others still work.
        for (String resource : new String[] {BUILT_IN_JAKE_RESOURCE, BUILT_IN_BOB_RESOURCE}) {
            try {
                register(loadFromClasspath(resource));
            } catch (RuntimeException loadFailure) {
                org.slf4j.LoggerFactory.getLogger("frens.souls")
                        .error("[souls] built-in profile {} failed to load: {}", resource,
                                loadFailure.toString());
            }
        }
        builtInsLoaded = true;
    }

    /**
     * Registers a profile. Rejects a blank id or an id already registered so profiles can never
     * silently collide or shadow one another.
     */
    public static void register(SoulTypes.SoulProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (profile.id().isBlank()) {
            throw new IllegalArgumentException("Soul profile id must not be blank");
        }
        if (PROFILES.putIfAbsent(profile.id(), profile) != null) {
            throw new IllegalStateException("Duplicate soul profile id: " + profile.id());
        }
    }

    /**
     * Returns the profile for {@code profileId}, throwing if no such profile has been
     * registered (via {@link #loadBuiltIns()} or {@link #register}).
     */
    public static SoulTypes.SoulProfile require(String profileId) {
        // Lazy, idempotent: SoulRuntime.start() loads the built-ins, but callers that reach
        // here without a started runtime (bindProfile in isolation, tests) must not fail
        // with "Unknown soul profile id" for a profile that ships with the mod.
        loadBuiltIns();
        SoulTypes.SoulProfile profile = PROFILES.get(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown soul profile id: " + profileId);
        }
        return profile;
    }

    /** The authored voice for {@code profileId}; {@link SoulTypes.VoiceSpec#EMPTY} when unknown. */
    public static SoulTypes.VoiceSpec voiceFor(String profileId) {
        SoulTypes.SoulProfile profile = profileId == null ? null : PROFILES.get(profileId);
        return profile == null ? SoulTypes.VoiceSpec.EMPTY : profile.voice();
    }

    private static SoulTypes.SoulProfile loadFromClasspath(String resourcePath) {
        try (InputStream in = SoulProfileRegistry.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing soul profile resource: " + resourcePath);
            }
            JsonNode root = MAPPER.readTree(in);
            String id = root.path("id").asText("");
            String displayName = root.path("displayName").asText("");
            List<String> identity = textList(root.path("identity"));
            List<String> values = textList(root.path("values"));
            List<String> boundaries = textList(root.path("boundaries"));
            List<SoulTypes.Message> examples = new ArrayList<>();
            for (JsonNode exampleNode : root.path("examples")) {
                SoulTypes.Role role = SoulTypes.Role.valueOf(exampleNode.path("role").asText());
                String content = exampleNode.path("content").asText("");
                examples.add(new SoulTypes.Message(role, content));
            }
            SoulTypes.VoiceSpec voice = SoulTypes.VoiceSpec.EMPTY;
            JsonNode voiceNode = root.path("voice");
            if (voiceNode.isObject()) {
                voice = new SoulTypes.VoiceSpec(
                        voiceNode.path("piperModel").asText(""),
                        voiceNode.path("piperSpeaker").asInt(-1),
                        voiceNode.path("refAudio").asText(""),
                        voiceNode.path("refText").asText(""));
            }
            return new SoulTypes.SoulProfile(id, displayName, identity, values, boundaries, examples, voice);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load soul profile resource: " + resourcePath, e);
        }
    }

    private static List<String> textList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            values.add(node.asText(""));
        }
        return values;
    }
}
