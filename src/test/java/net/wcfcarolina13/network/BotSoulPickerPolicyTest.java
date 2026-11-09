package net.wcfcarolina13.network;

import net.wcfcarolina13.network.AiSetupActionPolicy.Action;
import net.wcfcarolina13.network.AiSetupActionPolicy.Verdict;
import net.wcfcarolina13.network.BotSoulPickerPolicy.Option;
import net.wcfcarolina13.network.BotSoulPickerPolicy.PersonaCheck;
import net.wcfcarolina13.network.BotSoulPickerPolicy.VoiceCheck;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-bot persona and voice-preset pickers: arg format, validation, permissions, status JSON. */
class BotSoulPickerPolicyTest {

    private static final String BOB = "0b1f6a3e-2c4d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String AMY = "11111111-2222-3333-4444-555555555555";
    private static final List<String> IDS = List.of("frens:bob", "frens:jake", "frens:silas");

    // ── Arg format and length caps ──────────────────────────────────────────

    @Test
    void argRoundTrips() {
        String arg = BotSoulPickerPolicy.encodeArg(BOB, "frens:silas");
        BotSoulPickerPolicy.BotChoice choice = BotSoulPickerPolicy.parseArg(arg).orElseThrow();
        assertEquals(BOB, choice.botUuid());
        assertEquals("frens:silas", choice.value());
        assertEquals(BOB, BotSoulPickerPolicy.botUuidOf(Action.SET_BOT_PERSONA, arg));
        assertEquals(BOB, BotSoulPickerPolicy.botUuidOf(Action.ENABLE_BOT, BOB), "ENABLE_BOT's arg is the UUID itself");
    }

    @Test
    void malformedArgsAreRejected() {
        assertTrue(BotSoulPickerPolicy.parseArg(null).isEmpty());
        assertTrue(BotSoulPickerPolicy.parseArg("").isEmpty());
        assertTrue(BotSoulPickerPolicy.parseArg(BOB).isEmpty(), "no separator");
        assertTrue(BotSoulPickerPolicy.parseArg(BOB + "|").isEmpty(), "empty value");
        assertTrue(BotSoulPickerPolicy.parseArg(BOB + "|a|b").isEmpty(), "two separators");
        assertTrue(BotSoulPickerPolicy.parseArg("not-a-uuid-not-a-uuid-not-a-uuid-xx|alba").isEmpty());
        assertTrue(BotSoulPickerPolicy.parseArg("bob|alba").isEmpty(), "short UUID part");
        assertTrue(BotSoulPickerPolicy.parseArg(BOB + "|al ba").isEmpty(), "space");
        assertTrue(BotSoulPickerPolicy.parseArg(BOB + "|../etc").isEmpty(), "path separator");
        assertTrue(BotSoulPickerPolicy.parseArg(BOB + "|al\nba").isEmpty(), "control char");
        assertEquals("", BotSoulPickerPolicy.botUuidOf(Action.SET_BOT_VOICE, "junk"));
    }

    @Test
    void valueLengthIsCapped() {
        String max = "a".repeat(BotSoulPickerPolicy.MAX_VALUE_LENGTH);
        assertTrue(BotSoulPickerPolicy.parseArg(BOB + "|" + max).isPresent());
        assertTrue(BotSoulPickerPolicy.parseArg(BOB + "|" + max + "a").isEmpty());
        assertTrue(BotSoulPickerPolicy.MAX_ARG_LENGTH <= 256, "fits the payload's arg codec");
    }

    // ── Permissions (AiSetupActionPolicy.gate) ──────────────────────────────

    @Test
    void personaFollowsTheEnableRuleOwnerOpOrHost() {
        assertFalse(Action.SET_BOT_PERSONA.changesServerSettings());
        assertTrue(Action.SET_BOT_PERSONA.isBotScoped());
        assertEquals(Verdict.ALLOW, AiSetupActionPolicy.gate(Action.SET_BOT_PERSONA, false, true, true), "owner");
        assertEquals(Verdict.DENY_NOT_OWNER, AiSetupActionPolicy.gate(Action.SET_BOT_PERSONA, false, true, false));
        assertEquals(Verdict.DENY_NO_SUCH_BOT, AiSetupActionPolicy.gate(Action.SET_BOT_PERSONA, true, false, true));
    }

    @Test
    void voiceFollowsTheAssignCommandRuleOpOrHost() {
        assertTrue(Action.SET_BOT_VOICE.changesServerSettings());
        assertTrue(Action.SET_BOT_VOICE.isBotScoped());
        assertEquals(Verdict.DENY_NOT_OPERATOR, AiSetupActionPolicy.gate(Action.SET_BOT_VOICE, false, true, true),
                "an owner who is not op/host cannot write the server-wide voice map");
        assertEquals(Verdict.ALLOW, AiSetupActionPolicy.gate(Action.SET_BOT_VOICE, true, true, false),
                "op/host may assign a voice to any registered bot");
        assertEquals(Verdict.DENY_NO_SUCH_BOT, AiSetupActionPolicy.gate(Action.SET_BOT_VOICE, true, false, false));
    }

    @Test
    void denialsAreWordedForThePicker() {
        assertTrue(AiSetupActionPolicy.denialMessage(Action.SET_BOT_PERSONA, Verdict.DENY_NOT_OWNER).contains("persona"));
        assertTrue(AiSetupActionPolicy.denialMessage(Action.SET_BOT_VOICE, Verdict.DENY_NOT_OPERATOR).contains("voices"));
        assertEquals(AiSetupActionPolicy.denialMessage(Verdict.DENY_NOT_OWNER),
                AiSetupActionPolicy.denialMessage(Action.ENABLE_BOT, Verdict.DENY_NOT_OWNER));
    }

    @Test
    void sequencerKindsArePerBotAndPerPicker() {
        String bobPersona = AiSetupNetworkManager.ActionSequencer.kindOf(Action.SET_BOT_PERSONA,
                BotSoulPickerPolicy.encodeArg(BOB, "frens:jake"));
        assertEquals(bobPersona, AiSetupNetworkManager.ActionSequencer.kindOf(Action.SET_BOT_PERSONA,
                BotSoulPickerPolicy.encodeArg(BOB, "frens:silas")), "a newer pick for one bot supersedes");
        assertNotEquals(bobPersona, AiSetupNetworkManager.ActionSequencer.kindOf(Action.SET_BOT_PERSONA,
                BotSoulPickerPolicy.encodeArg(AMY, "frens:jake")));
        assertNotEquals(bobPersona, AiSetupNetworkManager.ActionSequencer.kindOf(Action.SET_BOT_VOICE,
                BotSoulPickerPolicy.encodeArg(BOB, "alba")));
        assertNotEquals(bobPersona, AiSetupNetworkManager.ActionSequencer.kindOf(Action.ENABLE_BOT, BOB));
    }

    // ── Persona validation ──────────────────────────────────────────────────

    @Test
    void personaMustBeRegisteredAndTheBotBound() {
        assertEquals(PersonaCheck.OK, BotSoulPickerPolicy.checkPersona("frens:silas", IDS, "frens:bob"));
        assertEquals(PersonaCheck.UNKNOWN_PERSONA, BotSoulPickerPolicy.checkPersona("frens:nobody", IDS, "frens:bob"));
        assertEquals(PersonaCheck.UNKNOWN_PERSONA, BotSoulPickerPolicy.checkPersona("FRENS:SILAS", IDS, "frens:bob"),
                "exact id only");
        assertEquals(PersonaCheck.UNKNOWN_PERSONA, BotSoulPickerPolicy.checkPersona(null, IDS, "frens:bob"));
        assertEquals(PersonaCheck.NOT_ENABLED, BotSoulPickerPolicy.checkPersona("frens:silas", IDS, ""));
        assertEquals(PersonaCheck.NOT_ENABLED, BotSoulPickerPolicy.checkPersona("frens:silas", IDS, null));
        assertEquals(PersonaCheck.UNCHANGED, BotSoulPickerPolicy.checkPersona("frens:bob", IDS, "frens:bob"));
        for (PersonaCheck c : PersonaCheck.values()) {
            assertEquals(c == PersonaCheck.OK, BotSoulPickerPolicy.personaProblem(c, "Bob").isEmpty());
        }
    }

    // ── Voice presets and validation ────────────────────────────────────────

    @Test
    void presetsComeFromTheActiveEngine() {
        List<String> pocket = List.of("alba", "marius");
        List<String> piper = List.of("en_US-lessac-medium", "bad name", "default");
        assertEquals(pocket, BotSoulPickerPolicy.presetsFor("pocket", pocket, piper));
        assertEquals(List.of("en_US-lessac-medium"), BotSoulPickerPolicy.presetsFor("piper", pocket, piper),
                "unsafe names and the reserved 'default' are dropped");
        assertEquals(List.of(), BotSoulPickerPolicy.presetsFor("dreamsleeve", pocket, piper));
        assertEquals(List.of(), BotSoulPickerPolicy.presetsFor("", pocket, piper));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) many.add("v" + i);
        assertEquals(BotSoulPickerPolicy.MAX_PRESETS, BotSoulPickerPolicy.presetsFor("pocket", many, List.of()).size());
    }

    @Test
    void voiceMustBeAListedPresetOrDefault() {
        List<String> presets = List.of("alba", "marius");
        assertEquals(VoiceCheck.OK, BotSoulPickerPolicy.checkVoice("alba", presets).check());
        assertEquals("marius", BotSoulPickerPolicy.checkVoice("MARIUS", presets).preset(), "server's spelling");
        assertEquals(VoiceCheck.UNKNOWN_PRESET, BotSoulPickerPolicy.checkVoice("nope", presets).check());
        assertEquals(VoiceCheck.CLEAR, BotSoulPickerPolicy.checkVoice("default", presets).check());
        assertEquals(VoiceCheck.CLEAR, BotSoulPickerPolicy.checkVoice("default", List.of()).check(),
                "clearing works on any engine");
        assertEquals(VoiceCheck.NO_PRESETS, BotSoulPickerPolicy.checkVoice("alba", List.of()).check());
        assertTrue(BotSoulPickerPolicy.voiceMessage("Bob",
                BotSoulPickerPolicy.checkVoice("alba", presets)).contains("alba"));
        assertFalse(BotSoulPickerPolicy.voiceProblem(VoiceCheck.NO_PRESETS).isEmpty());
    }

    // ── Picker rows ─────────────────────────────────────────────────────────

    @Test
    void personaRowsMarkTheCurrentOneAndWaitForEnable() {
        List<AiSetupStatus.Persona> personas = List.of(new AiSetupStatus.Persona("frens:bob", "Bob"),
                new AiSetupStatus.Persona("frens:jake", "Jake"));
        List<Option> bound = BotSoulPickerPolicy.personaOptions(personas, "frens:jake");
        assertFalse(bound.get(0).current());
        assertTrue(bound.get(0).enabled());
        assertTrue(bound.get(1).current());
        assertFalse(bound.get(1).enabled(), "the current persona is not clickable");
        for (Option o : BotSoulPickerPolicy.personaOptions(personas, "")) {
            assertFalse(o.enabled(), "nothing clickable before the bot is enabled");
        }
        assertFalse(BotSoulPickerPolicy.personaNote(true, "").isEmpty());
        assertEquals("", BotSoulPickerPolicy.personaNote(true, "frens:bob"));
    }

    @Test
    void voiceRowsStartWithDefaultAndNeedOpOrHost() {
        List<Option> none = BotSoulPickerPolicy.voiceOptions(List.of("alba"), "", true);
        assertEquals("default", none.get(0).value());
        assertTrue(none.get(0).current());
        assertTrue(none.get(1).enabled());
        List<Option> alba = BotSoulPickerPolicy.voiceOptions(List.of("alba"), "Alba", true);
        assertTrue(alba.get(0).enabled());
        assertTrue(alba.get(1).current());
        assertTrue(BotSoulPickerPolicy.voiceOptions(List.of("alba"), "", false).isEmpty());
        assertFalse(BotSoulPickerPolicy.voiceNote(false, 3).isEmpty());
        assertFalse(BotSoulPickerPolicy.voiceNote(true, 0).isEmpty());
        assertEquals("", BotSoulPickerPolicy.voiceNote(true, 3));
    }

    // ── Status JSON ─────────────────────────────────────────────────────────

    private static AiSetupStatus fullStatus() {
        return new AiSetupStatus(true, null, null,
                List.of(new AiSetupStatus.Bot("Bob", BOB, "frens:bob", true, true, true, false, "alba")),
                null, new AiSetupStatus.Viewer(true, true, true),
                List.of(new AiSetupStatus.Persona("frens:bob", "Bob"), new AiSetupStatus.Persona("frens:silas", "Silas")),
                List.of("alba", "marius"));
    }

    @Test
    void statusCarriesThePickerData() {
        AiSetupStatus back = AiSetupStatus.fromJson(fullStatus().toJson());
        assertTrue(back.offersPickers());
        assertEquals(2, back.personas().size());
        assertEquals("Silas", back.personas().get(1).name());
        assertEquals(List.of("alba", "marius"), back.voicePresets());
        assertEquals("alba", back.bot(BOB).voice());
        assertNull(back.bot(AMY));
    }

    @Test
    void redactedStatusKeepsPersonasButNotServerVoiceDetail() {
        AiSetupStatus back = AiSetupStatus.fromJson(fullStatus().redactedForPlayer().toJson());
        assertTrue(back.offersPickers(), "owners can pick personas");
        assertEquals(List.of(), back.voicePresets());
        assertEquals("", back.bot(BOB).voice());
    }

    @Test
    void anOldServerOffersNoPickers() {
        String old = "{\"v\":1,\"full\":true,\"runtime\":{},\"bots\":[{\"name\":\"Bob\",\"uuid\":\"" + BOB
                + "\",\"profileId\":\"frens:bob\",\"active\":true}],\"voice\":{},\"viewer\":{}}";
        AiSetupStatus back = AiSetupStatus.fromJson(old);
        assertFalse(back.offersPickers());
        assertEquals("", back.bot(BOB).voice());
    }
}
