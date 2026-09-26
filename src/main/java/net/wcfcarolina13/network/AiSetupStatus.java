package net.wcfcarolina13.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * What the "Set up AI companions" checklist knows about the server's AI setup — the plain,
 * Minecraft-free record carried as JSON by {@code AiSetupStatusPayload}.
 *
 * <p>{@code full} is true for an operator or the integrated-server host. A non-full record is
 * the redacted view a regular player gets: {@link #ollama()} is null, and the runtime's model,
 * validation error and provider health, and the voice engine details, are left at their
 * "unknown" defaults and omitted from the JSON (they describe the server machine).
 */
public record AiSetupStatus(boolean full,
                            Ollama ollama,
                            Runtime runtime,
                            List<Bot> bots,
                            Voice voice,
                            Viewer viewer) {

    /** Caps that keep the JSON far below the payload's string limit. */
    public static final int MAX_TAGS = 64;
    public static final int MAX_BOTS = 32;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Ollama on the server machine. Null in a non-full record. */
    public record Ollama(boolean reachable, String version, List<String> installedTags, double hostRamGb) {
        public Ollama {
            version = version == null ? "" : version;
            installedTags = installedTags == null ? List.of() : List.copyOf(installedTags);
        }

        public boolean isInstalled(String tag) {
            return tag != null && installedTags.contains(tag);
        }
    }

    /**
     * The live soul runtime. {@code running} false means no runtime is installed (server
     * stopping). {@code ready} is {@code pipelineAvailable()}: switch on, settings valid, index
     * loaded. {@code providerHealthy} is null when unknown (non-full view, or not probed).
     */
    public record Runtime(boolean running, boolean soulsEnabled, boolean ready,
                          String model, String validationError, Boolean providerHealthy) {
        public Runtime {
            model = model == null ? "" : model;
            validationError = validationError == null ? "" : validationError;
        }
    }

    /**
     * One registered bot. {@code repliedRecently}: the viewer has an open DM follow-up window
     * with this bot, i.e. it delivered a soul reply to them in the last 30 s.
     */
    public record Bot(String name, String uuid, String profileId, boolean active,
                      boolean ownedByViewer, boolean inReach, boolean repliedRecently) {
        public Bot {
            name = name == null ? "" : name;
            uuid = uuid == null ? "" : uuid;
            profileId = profileId == null ? "" : profileId;
        }

        /** Bound to a persona and active — what {@code /bot soul enable} leaves behind. */
        public boolean enabled() {
            return active && !profileId.isBlank();
        }
    }

    /** Soul voice. Engine details are empty/false in a non-full record ({@code detailKnown} false). */
    public record Voice(boolean enabled, boolean detailKnown, String engine, boolean valid, boolean engineAlive) {
        public Voice {
            engine = engine == null ? "" : engine;
        }
    }

    /** What the viewer may do, as the server decided it. */
    public record Viewer(boolean canEditServer, boolean isIntegratedHost) {
    }

    public AiSetupStatus {
        bots = bots == null ? List.of() : List.copyOf(bots);
        runtime = runtime == null ? new Runtime(false, false, false, "", "", null) : runtime;
        voice = voice == null ? new Voice(false, false, "", false, false) : voice;
        viewer = viewer == null ? new Viewer(false, false) : viewer;
    }

    /**
     * The redacted copy a non-operator receives: no Ollama block, no model/validation/health,
     * no voice engine details, only the viewer's own bots.
     */
    public AiSetupStatus redactedForPlayer() {
        List<Bot> own = new ArrayList<>();
        for (Bot bot : bots) {
            if (bot.ownedByViewer()) {
                own.add(bot);
            }
        }
        return new AiSetupStatus(false, null,
                new Runtime(runtime.running(), runtime.soulsEnabled(), runtime.ready(), "", "", null),
                own,
                new Voice(voice.enabled(), false, "", false, false),
                viewer);
    }

    // ── JSON ────────────────────────────────────────────────────────────────

    public String toJson() {
        ObjectNode root = JSON.createObjectNode();
        root.put("v", 1);
        root.put("full", full);
        if (full && ollama != null) {
            ObjectNode o = root.putObject("ollama");
            o.put("reachable", ollama.reachable());
            o.put("version", ollama.version());
            ArrayNode tags = o.putArray("installedTags");
            int n = 0;
            for (String tag : ollama.installedTags()) {
                if (n++ >= MAX_TAGS) break;
                tags.add(tag);
            }
            o.put("hostRamGb", ollama.hostRamGb());
        }
        ObjectNode r = root.putObject("runtime");
        r.put("running", runtime.running());
        r.put("soulsEnabled", runtime.soulsEnabled());
        r.put("ready", runtime.ready());
        if (full) {
            r.put("model", runtime.model());
            r.put("validationError", runtime.validationError());
            if (runtime.providerHealthy() != null) {
                r.put("providerHealthy", runtime.providerHealthy());
            }
        }
        ArrayNode b = root.putArray("bots");
        int count = 0;
        for (Bot bot : bots) {
            if (count++ >= MAX_BOTS) break;
            ObjectNode node = b.addObject();
            node.put("name", bot.name());
            node.put("uuid", bot.uuid());
            node.put("profileId", bot.profileId());
            node.put("active", bot.active());
            node.put("ownedByViewer", bot.ownedByViewer());
            node.put("inReach", bot.inReach());
            node.put("repliedRecently", bot.repliedRecently());
        }
        ObjectNode v = root.putObject("voice");
        v.put("enabled", voice.enabled());
        if (full && voice.detailKnown()) {
            v.put("engine", voice.engine());
            v.put("valid", voice.valid());
            v.put("engineAlive", voice.engineAlive());
        }
        ObjectNode w = root.putObject("viewer");
        w.put("canEditServer", viewer.canEditServer());
        w.put("isIntegratedHost", viewer.isIntegratedHost());
        return root.toString();
    }

    /** Parses {@link #toJson()} output; null on anything malformed. */
    public static AiSetupStatus fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isObject()) {
                return null;
            }
            boolean full = root.path("full").asBoolean(false);
            Ollama ollama = null;
            JsonNode o = root.get("ollama");
            if (full && o != null && o.isObject()) {
                List<String> tags = new ArrayList<>();
                for (JsonNode t : o.path("installedTags")) {
                    if (tags.size() >= MAX_TAGS) break;
                    tags.add(t.asText(""));
                }
                ollama = new Ollama(o.path("reachable").asBoolean(false), o.path("version").asText(""),
                        tags, o.path("hostRamGb").asDouble(-1));
            }
            JsonNode r = root.path("runtime");
            JsonNode health = r.get("providerHealthy");
            Runtime runtime = new Runtime(r.path("running").asBoolean(false),
                    r.path("soulsEnabled").asBoolean(false), r.path("ready").asBoolean(false),
                    r.path("model").asText(""), r.path("validationError").asText(""),
                    health != null && health.isBoolean() ? health.asBoolean() : null);
            List<Bot> bots = new ArrayList<>();
            for (JsonNode node : root.path("bots")) {
                if (bots.size() >= MAX_BOTS) break;
                bots.add(new Bot(node.path("name").asText(""), node.path("uuid").asText(""),
                        node.path("profileId").asText(""), node.path("active").asBoolean(false),
                        node.path("ownedByViewer").asBoolean(false), node.path("inReach").asBoolean(false),
                        node.path("repliedRecently").asBoolean(false)));
            }
            JsonNode v = root.path("voice");
            boolean detail = v.has("engine");
            Voice voice = new Voice(v.path("enabled").asBoolean(false), detail, v.path("engine").asText(""),
                    v.path("valid").asBoolean(false), v.path("engineAlive").asBoolean(false));
            JsonNode w = root.path("viewer");
            Viewer viewer = new Viewer(w.path("canEditServer").asBoolean(false),
                    w.path("isIntegratedHost").asBoolean(false));
            return new AiSetupStatus(full, ollama, runtime, bots, voice, viewer);
        } catch (Exception malformed) {
            return null;
        }
    }
}
