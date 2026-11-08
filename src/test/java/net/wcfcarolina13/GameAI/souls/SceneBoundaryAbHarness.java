package net.wcfcarolina13.GameAI.souls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Manual A/B for scene smoothing step 2, not part of the normal suite: it runs only when
 * FRENS_AB_PARTY names a party transcript (active.jsonl). It replays real banter scenes through
 * the real assembler, Ollama provider and validator, with and without scene boundaries, and
 * counts scenes whose first spoken line opens with a filler.
 *
 * <pre>
 * FRENS_AB_PARTY=.../active.jsonl FRENS_AB_OUT=.../ab.jsonl [FRENS_AB_MODEL=llama3.1:8b]
 *   [FRENS_AB_SCENES=8] [FRENS_AB_SAMPLES=5] ./gradlew test --tests '*SceneBoundaryAbHarness*'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "FRENS_AB_PARTY", matches = ".+")
class SceneBoundaryAbHarness {

    /** Fixed before the first call (pre-registered): a filler opener is one of these words first. */
    static final Pattern FILLER = Pattern.compile(
            "^\\W*(yeah|yep|yes|yup|oh|ah|well|ha|haha|hmm|right|true|exactly|totally|sure|agreed|i know|same|okay)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final String OWNER = "RotiWokeman";
    private static final List<String> ROSTER = List.of("Jake", "Bob");

    private record Scene(UUID id, String cue, int firstIndex, List<String> speakers) {
    }

    @Test
    void run() throws Exception {
        Path party = Path.of(System.getenv("FRENS_AB_PARTY"));
        Path out = Path.of(env("FRENS_AB_OUT", "scene-boundary-ab.jsonl"));
        String model = env("FRENS_AB_MODEL", "llama3.1:8b");
        int sceneCount = Integer.parseInt(env("FRENS_AB_SCENES", "8"));
        int samples = Integer.parseInt(env("FRENS_AB_SAMPLES", "5"));
        ObjectMapper mapper = new ObjectMapper();

        List<SoulTypes.ConversationRecord> records = new ArrayList<>();
        for (String line : Files.readAllLines(party, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode n = mapper.readTree(line);
            double at = n.path("occurredAt").asDouble();
            records.add(new SoulTypes.ConversationRecord(UUID.fromString(n.path("correlationId").asText()),
                    n.path("epoch").asLong(), n.path("sequence").asLong(),
                    SoulTypes.TurnKind.valueOf(n.path("kind").asText()), n.path("content").asText(),
                    Instant.ofEpochMilli((long) (at * 1000)), n.path("provider").asText(""),
                    n.path("model").asText(""), null, null));
        }

        // banter scenes spoken only by Jake and Bob, with at least 2 earlier scenes in the history window
        Map<UUID, Scene> scenes = new LinkedHashMap<>();
        for (int i = 0; i < records.size(); i++) {
            SoulTypes.ConversationRecord r = records.get(i);
            if (r.kind() == SoulTypes.TurnKind.HEARD && r.content().startsWith(SoulGroupPromptAssembler.BANTER_HEARD_PREFIX)) {
                scenes.put(r.correlationId(), new Scene(r.correlationId(),
                        r.content().substring(SoulGroupPromptAssembler.BANTER_HEARD_PREFIX.length()), i, new ArrayList<>()));
            } else if (r.kind() == SoulTypes.TurnKind.SPOKEN && scenes.containsKey(r.correlationId())) {
                String speaker = r.content().contains(":") ? r.content().substring(0, r.content().indexOf(':')) : "";
                scenes.get(r.correlationId()).speakers().add(speaker);
            }
        }
        List<Scene> eligible = new ArrayList<>();
        for (Scene s : scenes.values()) {
            if (s.speakers().isEmpty() || !ROSTER.containsAll(s.speakers())) {
                continue;
            }
            long priorScenes = records.subList(Math.max(0, s.firstIndex() - 12), s.firstIndex()).stream()
                    .filter(r -> r.kind() == SoulTypes.TurnKind.SPOKEN).map(SoulTypes.ConversationRecord::correlationId)
                    .distinct().count();
            if (priorScenes >= 2) {
                eligible.add(s);
            }
        }
        List<Scene> targets = new ArrayList<>();
        for (int k = 0; k < Math.min(sceneCount, eligible.size()); k++) {
            targets.add(eligible.get((int) ((long) k * eligible.size() / Math.min(sceneCount, eligible.size()))));
        }
        System.out.println("[ab] eligible scenes " + eligible.size() + ", running " + targets.size()
                + " x " + samples + " samples x 2 variants on " + model);

        SoulProfileRegistry.loadBuiltIns();
        List<SoulTypes.SoulProfile> profiles = List.of(SoulProfileRegistry.require("frens:jake"),
                SoulProfileRegistry.require("frens:bob"));
        OllamaSoulProvider provider = new OllamaSoulProvider(URI.create("http://127.0.0.1:11434"), model, mapper);
        SoulGroupResponseValidator validator = new SoulGroupResponseValidator();
        SoulGroupPromptAssembler plain = new SoulGroupPromptAssembler(null, () -> false, () -> false, () -> false);
        SoulGroupPromptAssembler marked = new SoulGroupPromptAssembler(null, () -> false, () -> false, () -> true);

        Map<String, int[]> tally = new LinkedHashMap<>(); // variant -> {scenes, accepted, firstFiller, anyFillerLines, lines}
        StringBuilder rows = new StringBuilder();
        try {
            for (Scene scene : targets) {
                List<SoulTypes.ConversationRecord> history = records.subList(0, scene.firstIndex());
                for (int sample = 0; sample < samples; sample++) {
                    String[] order = sample % 2 == 0 ? new String[]{"A", "B"} : new String[]{"B", "A"};
                    for (String variant : order) {
                        SoulGroupPromptAssembler assembler = variant.equals("A") ? plain : marked;
                        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), model,
                                banterTurn(scene.cue()), profiles, history, Duration.ofSeconds(120));
                        SoulTypes.ProviderResult result = provider.generate(request).result().get(150, TimeUnit.SECONDS);
                        SoulGroupResponseValidator.SceneParse parse = validator.parse(result.text(), ROSTER,
                                SoulGroupTypes.BANTER_MAX_SCENE_LINES, OWNER, true);
                        List<String> spoken = new ArrayList<>();
                        for (SoulGroupTypes.SceneLine l : parse.lines()) {
                            spoken.add(ROSTER.get(l.participantIndex()) + ": " + l.text());
                        }
                        String first = spoken.isEmpty() ? "" : parse.lines().get(0).text();
                        boolean firstFiller = !first.isEmpty() && FILLER.matcher(first).find();
                        int fillerLines = 0;
                        for (SoulGroupTypes.SceneLine l : parse.lines()) {
                            if (FILLER.matcher(l.text()).find()) {
                                fillerLines++;
                            }
                        }
                        int[] t = tally.computeIfAbsent(variant, v -> new int[5]);
                        t[0]++;
                        t[1] += parse.accepted() ? 1 : 0;
                        t[2] += firstFiller ? 1 : 0;
                        t[3] += fillerLines;
                        t[4] += parse.lines().size();
                        ObjectNode row = mapper.createObjectNode();
                        row.put("variant", variant);
                        row.put("scene", scene.id().toString().substring(0, 8));
                        row.put("cue", scene.cue());
                        row.put("sample", sample);
                        row.put("success", result.success());
                        row.put("accepted", parse.accepted());
                        row.put("firstFiller", firstFiller);
                        row.put("fillerLines", fillerLines);
                        row.put("first", first);
                        row.putPOJO("spoken", spoken);
                        row.put("raw", result.text());
                        row.put("inputTokens", result.inputTokens() == null ? -1 : result.inputTokens());
                        row.put("outputTokens", result.outputTokens() == null ? -1 : result.outputTokens());
                        row.put("elapsedMs", result.elapsedMillis());
                        rows.append(mapper.writeValueAsString(row)).append('\n');
                        Files.writeString(out, rows.toString(), StandardCharsets.UTF_8);
                        System.out.println(String.format(Locale.ROOT, "[ab] %s %s s%d filler=%s first=%s",
                                scene.id().toString().substring(0, 8), variant, sample, firstFiller, first));
                    }
                }
            }
        } finally {
            provider.close();
        }
        tally.forEach((v, t) -> System.out.println(String.format(Locale.ROOT,
                "[ab] variant %s: scenes %d, accepted %d, first line filler %d, filler lines %d of %d",
                v, t[0], t[1], t[2], t[3], t[4])));
    }

    private static SoulGroupTypes.GroupSceneTurn banterTurn(String cue) {
        UUID jake = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        return new SoulGroupTypes.GroupSceneTurn(SoulGroupTypes.SceneKind.BANTER, UUID.randomUUID(), OWNER,
                List.of(new SoulGroupTypes.SceneParticipant(jake, "frens:jake", "Jake", grounding(jake, "Jake")),
                        new SoulGroupTypes.SceneParticipant(bob, "frens:bob", "Bob", grounding(bob, "Bob"))),
                cue, Instant.now(), UUID.randomUUID());
    }

    private static SoulTypes.GroundingSnapshot grounding(UUID botId, String name) {
        SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(botId, name, "overworld", "plains",
                0, 64, 0, true, "day", "clear", 20f, 20f, 18, 5, "stone axe", 10, 36,
                List.of(), "cheerful", "FOLLOW", "idle", "idle", "home", OWNER,
                true, 0, true, Optional.empty());
        return new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot, Optional.empty(), Instant.EPOCH);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
