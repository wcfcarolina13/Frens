package net.wcfcarolina13.network;

import net.wcfcarolina13.network.AiSetupActionPolicy.Action;
import net.wcfcarolina13.network.AiSetupActionPolicy.TagCheck;
import net.wcfcarolina13.network.AiSetupActionPolicy.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSetupActionPolicyTest {

    private static final List<String> CATALOG = List.of("llama3.1:8b", "llama3.2:3b", "llama3.2:1b");

    // ── Action parsing and gates ────────────────────────────────────────────

    @Test
    void actionsParseByExactNameOnly() {
        for (Action a : Action.values()) {
            assertEquals(a, Action.parse(a.name()).orElseThrow());
        }
        assertTrue(Action.parse("souls_on").isEmpty());
        assertTrue(Action.parse(" SOULS_ON").isEmpty());
        assertTrue(Action.parse("").isEmpty());
        assertTrue(Action.parse(null).isEmpty());
        assertTrue(Action.parse("DELETE_WORLD").isEmpty());
    }

    @Test
    void serverWideActionsNeedOperatorOrHost() {
        for (Action a : List.of(Action.SOULS_ON, Action.SOULS_OFF, Action.SELECT_MODEL, Action.SELECT_VOICE_ENGINE)) {
            assertTrue(a.changesServerSettings());
            assertEquals(Verdict.ALLOW, AiSetupActionPolicy.gate(a, true, false, false));
            // Owning a bot never grants server-wide changes.
            assertEquals(Verdict.DENY_NOT_OPERATOR, AiSetupActionPolicy.gate(a, false, true, true));
        }
    }

    @Test
    void enableBotNeedsARegisteredBotTheSenderMayAdminister() {
        assertFalse(Action.ENABLE_BOT.changesServerSettings());
        assertEquals(Verdict.ALLOW, AiSetupActionPolicy.gate(Action.ENABLE_BOT, false, true, true));
        assertEquals(Verdict.DENY_NOT_OWNER, AiSetupActionPolicy.gate(Action.ENABLE_BOT, false, true, false));
        assertEquals(Verdict.DENY_NO_SUCH_BOT, AiSetupActionPolicy.gate(Action.ENABLE_BOT, true, false, false));
        // An operator still needs a real registered bot (a real player is never "enabled").
        assertEquals(Verdict.DENY_NO_SUCH_BOT, AiSetupActionPolicy.gate(Action.ENABLE_BOT, true, false, true));
    }

    @Test
    void nullActionIsDenied() {
        assertEquals(Verdict.DENY_NOT_OPERATOR, AiSetupActionPolicy.gate(null, true, true, true));
    }

    @Test
    void everyDenialHasAPlayerVisibleReason() {
        assertEquals("Only an operator or the host can change the server's AI settings.",
                AiSetupActionPolicy.denialMessage(Verdict.DENY_NOT_OPERATOR));
        assertFalse(AiSetupActionPolicy.denialMessage(Verdict.DENY_NO_SUCH_BOT).isBlank());
        assertFalse(AiSetupActionPolicy.denialMessage(Verdict.DENY_NOT_OWNER).isBlank());
        assertEquals("", AiSetupActionPolicy.denialMessage(Verdict.ALLOW));
    }

    // ── Model tag validation ────────────────────────────────────────────────

    @Test
    void wellFormedTagsPassSyntax() {
        assertEquals(TagCheck.OK, AiSetupActionPolicy.checkTagSyntax("llama3.2:3b"));
        assertEquals(TagCheck.OK, AiSetupActionPolicy.checkTagSyntax("hf.co/bartowski/Qwen2.5-7B_GGUF:Q4_K_M"));
        assertEquals(TagCheck.OK, AiSetupActionPolicy.checkTagSyntax("a"));
    }

    @Test
    void injectionIshTagsFailSyntax() {
        for (String bad : List.of("llama3 ; rm -rf /", "llama\n3", "llama3\u0000", "../../etc/passwd;",
                "model\"}", "$(whoami)", "a b", "tag\t", "§cred", "llama3.2:3b\r\n", "ünicode", "a&b", "a|b",
                "{\"model\":\"x\"}", " llama3.2:3b")) {
            assertEquals(TagCheck.BAD_CHARS, AiSetupActionPolicy.checkTagSyntax(bad), bad);
        }
    }

    @Test
    void blankAndOverLengthTagsFail() {
        assertEquals(TagCheck.BLANK, AiSetupActionPolicy.checkTagSyntax(""));
        assertEquals(TagCheck.BLANK, AiSetupActionPolicy.checkTagSyntax(null));
        String max = "a".repeat(AiSetupActionPolicy.MAX_TAG_LENGTH);
        assertEquals(TagCheck.OK, AiSetupActionPolicy.checkTagSyntax(max));
        assertEquals(TagCheck.TOO_LONG, AiSetupActionPolicy.checkTagSyntax(max + "a"));
        // Length is checked before charset, so a huge hostile string never gets scanned.
        assertEquals(TagCheck.TOO_LONG, AiSetupActionPolicy.checkTagSyntax(";".repeat(10_000)));
    }

    @Test
    void catalogTagsPassWithoutInstalledList() {
        assertEquals(TagCheck.OK, AiSetupActionPolicy.checkModelTag("llama3.2:1b", CATALOG, null));
        assertFalse(AiSetupActionPolicy.needsInstalledTags("llama3.2:1b", CATALOG));
    }

    @Test
    void nonCatalogTagMustBeInstalledOnTheServer() {
        assertTrue(AiSetupActionPolicy.needsInstalledTags("qwen2.5:7b", CATALOG));
        assertEquals(TagCheck.OK, AiSetupActionPolicy.checkModelTag("qwen2.5:7b", CATALOG, List.of("qwen2.5:7b")));
        assertEquals(TagCheck.NOT_AVAILABLE, AiSetupActionPolicy.checkModelTag("qwen2.5:7b", CATALOG, List.of()));
        // Unknown installed list (Ollama unreachable) never lets a non-catalog tag through.
        assertEquals(TagCheck.NOT_AVAILABLE, AiSetupActionPolicy.checkModelTag("qwen2.5:7b", CATALOG, null));
        // Syntax failures win over availability, even if the bad string is "installed".
        assertEquals(TagCheck.BAD_CHARS, AiSetupActionPolicy.checkModelTag("x;y", CATALOG, List.of("x;y")));
        assertFalse(AiSetupActionPolicy.needsInstalledTags("x;y", CATALOG));
    }

    @Test
    void everyTagProblemIsWorded() {
        for (TagCheck c : TagCheck.values()) {
            assertEquals(c == TagCheck.OK, AiSetupActionPolicy.tagProblem(c).isEmpty(), c.name());
        }
    }

    // ── Voice engines ───────────────────────────────────────────────────────

    @Test
    void onlyKnownVoiceEnginesAreAccepted() {
        assertTrue(AiSetupActionPolicy.isKnownEngine("piper"));
        assertTrue(AiSetupActionPolicy.isKnownEngine("pocket"));
        assertTrue(AiSetupActionPolicy.isKnownEngine("dreamsleeve"));
        assertFalse(AiSetupActionPolicy.isKnownEngine("Piper"));
        assertFalse(AiSetupActionPolicy.isKnownEngine("espeak"));
        assertFalse(AiSetupActionPolicy.isKnownEngine(""));
        assertFalse(AiSetupActionPolicy.isKnownEngine(null));
    }

    // ── Throttle / cache ────────────────────────────────────────────────────

    @Test
    void throttleAllowsOncePerInterval() {
        long interval = AiSetupActionPolicy.STATUS_REQUEST_INTERVAL_MS;
        assertTrue(AiSetupActionPolicy.throttleAllows(null, 1_000, interval));
        assertFalse(AiSetupActionPolicy.throttleAllows(1_000L, 1_000 + interval - 1, interval));
        assertTrue(AiSetupActionPolicy.throttleAllows(1_000L, 1_000 + interval, interval));
        // A clock that went backwards never locks a player out.
        assertTrue(AiSetupActionPolicy.throttleAllows(5_000L, 1_000, interval));
    }

    @Test
    void probeCacheIsFreshOnlyWithinTtl() {
        long ttl = AiSetupActionPolicy.OLLAMA_PROBE_TTL_MS;
        assertFalse(AiSetupActionPolicy.cacheFresh(0, 5_000, ttl));
        assertTrue(AiSetupActionPolicy.cacheFresh(1_000, 1_000, ttl));
        assertTrue(AiSetupActionPolicy.cacheFresh(1_000, 1_000 + ttl - 1, ttl));
        assertFalse(AiSetupActionPolicy.cacheFresh(1_000, 1_000 + ttl, ttl));
        assertFalse(AiSetupActionPolicy.cacheFresh(5_000, 1_000, ttl));
    }

    // ── Enable message (all four branches, first problem wins) ──────────────

    @Test
    void enableMessageSoulChatOff() {
        assertEquals("Bob will speak as Bob, but Soul Chat is OFF. Turn it on in Set up AI companions or run /bot soul system on.",
                AiSetupActionPolicy.enableMessage("Bob", "Bob", false, false, "Configure a local soul model first.", false));
    }

    @Test
    void enableMessageInvalidSettings() {
        assertEquals("Bob will speak as Bob, but Bob can't reply yet: Configure a local soul model first.",
                AiSetupActionPolicy.enableMessage("Bob", "Bob", false, true, "Configure a local soul model first.", false));
    }

    @Test
    void enableMessageOllamaDown() {
        assertEquals("Steve will speak as Jake, but Ollama isn't answering on the server machine.",
                AiSetupActionPolicy.enableMessage("Steve", "Jake", true, true, "", false));
    }

    @Test
    void enableMessageAllGoodKeepsTheShippedSuccessText() {
        assertEquals("Bob is now speaking as Bob.",
                AiSetupActionPolicy.enableMessage("Bob", "Bob", false, true, "", true));
        assertEquals("Steve is now speaking as Jake (no profile of their own is registered yet).",
                AiSetupActionPolicy.enableMessage("Steve", "Jake", true, true, "", true));
        // Health not checked (null) never claims Ollama is down.
        assertEquals("Bob is now speaking as Bob.",
                AiSetupActionPolicy.enableMessage("Bob", "Bob", false, true, null, null));
    }

    @Test
    void fallbackPersonaOnlyForNonJakeBotsBoundToJake() {
        assertTrue(AiSetupActionPolicy.isFallbackPersona("Steve", "frens:jake", "frens:jake"));
        assertFalse(AiSetupActionPolicy.isFallbackPersona("Jake", "frens:jake", "frens:jake"));
        assertFalse(AiSetupActionPolicy.isFallbackPersona("jake", "frens:jake", "frens:jake"));
        assertFalse(AiSetupActionPolicy.isFallbackPersona("Bob", "frens:bob", "frens:jake"));
    }
}
