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
        register(loadFromClasspath(BUILT_IN_JAKE_RESOURCE));
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
        SoulTypes.SoulProfile profile = PROFILES.get(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown soul profile id: " + profileId);
        }
        return profile;
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
            return new SoulTypes.SoulProfile(id, displayName, identity, values, boundaries, examples);
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
