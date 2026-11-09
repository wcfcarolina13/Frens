package net.wcfcarolina13.guide;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GuideCatalogTest {

    private static ByteArrayInputStream json(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void bundledTopicsLoadWithUniqueIdsAndRequiredText() {
        List<GuideCatalog.Topic> topics = GuideCatalog.loadBundled();
        assertTrue(topics.size() >= 55, "expected the full Guide, got " + topics.size());
        Set<String> ids = new HashSet<>();
        for (GuideCatalog.Topic t : topics) {
            assertTrue(ids.add(t.id()), "duplicate id " + t.id());
            assertFalse(t.title().isBlank(), t.id());
            assertFalse(t.summary().isBlank(), t.id());
            assertFalse(t.details().isEmpty(), t.id());
        }
    }

    @Test
    void visibilityFlagsMatchTheOldScreenRules() {
        Set<String> admin = new HashSet<>();
        Set<String> questing = new HashSet<>();
        for (GuideCatalog.Topic t : GuideCatalog.loadBundled()) {
            if (t.adminOnly()) admin.add(t.id());
            if (t.questingOnly()) questing.add(t.id());
        }
        assertEquals(Set.of("modes_delegate", "modes_direct", "admin_worldmode_reset"), admin);
        assertEquals(Set.of("modes_quest", "modes_delegate", "admin_worldmode_reset"), questing);
    }

    @Test
    void resolveFillsBotAndGuideKeyEverywhereButTags() {
        GuideCatalog.Topic t = new GuideCatalog.Topic("x", "Basics", "Hi {bot}", "Press {guideKey}",
                List.of("Tell {bot}", "plain"), "bot stop {bot}", "Open Guide: {guideKey}", "{bot}",
                false, false);
        GuideCatalog.Topic r = t.resolve("\"Big Jake\"", "G");
        assertEquals("Hi \"Big Jake\"", r.title());
        assertEquals("Press G", r.summary());
        assertEquals(List.of("Tell \"Big Jake\"", "plain"), r.details());
        assertEquals("bot stop \"Big Jake\"", r.command());
        assertEquals("Open Guide: G", r.shortcuts());
        assertEquals("{bot}", r.tags());
    }

    @Test
    void parseRejectsDuplicateIdsAndMissingFields() {
        String dup = "{\"topics\":[{\"id\":\"a\",\"category\":\"c\",\"title\":\"t\",\"summary\":\"s\",\"details\":[\"d\"]},"
                + "{\"id\":\"a\",\"category\":\"c\",\"title\":\"t\",\"summary\":\"s\",\"details\":[\"d\"]}]}";
        assertThrows(IllegalArgumentException.class, () -> GuideCatalog.parse(json(dup)));
        String noTitle = "{\"topics\":[{\"id\":\"a\",\"category\":\"c\",\"summary\":\"s\",\"details\":[]}]}";
        assertThrows(IllegalArgumentException.class, () -> GuideCatalog.parse(json(noTitle)));
        assertThrows(IllegalArgumentException.class, () -> GuideCatalog.parse(json("{}")));
    }

    @Test
    void optionalFieldsDefaultToEmptyAndFlagsToFalse() throws Exception {
        List<GuideCatalog.Topic> t = GuideCatalog.parse(json(
                "{\"topics\":[{\"id\":\"a\",\"category\":\"c\",\"title\":\"t\",\"summary\":\"s\",\"details\":[\"d\"]}]}"));
        assertEquals("", t.get(0).command());
        assertEquals("", t.get(0).shortcuts());
        assertFalse(t.get(0).adminOnly());
        assertFalse(t.get(0).questingOnly());
        assertEquals("", t.get(0).answer());
    }

    @Test
    void answerIsParsedAndResolved() throws Exception {
        List<GuideCatalog.Topic> t = GuideCatalog.parse(json(
                "{\"topics\":[{\"id\":\"a\",\"category\":\"c\",\"title\":\"t\",\"summary\":\"s\","
                        + "\"answer\":\"Type /bot stop {bot}.\",\"details\":[\"d\"]}]}"));
        assertEquals("Type /bot stop {bot}.", t.get(0).answer());
        assertEquals("Type /bot stop Jake.", t.get(0).resolve("Jake", "]").answer());
    }

    /** Frens speak these in chat: short, plain, no Minecraft formatting codes. */
    @Test
    void everyBundledTopicHasAShortPlainAnswer() {
        for (GuideCatalog.Topic t : GuideCatalog.loadBundled()) {
            String a = t.answer();
            assertFalse(a.isBlank(), t.id() + " has no answer");
            assertFalse(a.contains("\u00A7"), t.id() + " answer has a formatting code");
            assertTrue(a.length() <= 300, t.id() + " answer is " + a.length() + " chars");
            int sentences = a.split("[.!?](\\s|$)").length;
            assertTrue(sentences <= 3, t.id() + " answer has " + sentences + " sentences");
        }
    }
}
