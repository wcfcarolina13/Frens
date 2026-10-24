package net.wcfcarolina13.GraphicalUserInterface;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SoulSetupScreenPolicyTest {
    @Test
    void modeUsesIntegratedServerAndAuthoritativePermission() {
        for (boolean available : new boolean[] {false, true}) {
            for (boolean editor : new boolean[] {false, true}) {
                assertEquals(SoulSetupScreenPolicy.Mode.LOCAL,
                        SoulSetupScreenPolicy.mode(true, available, available, editor, 5_000));
            }
        }
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_VIEWER,
                SoulSetupScreenPolicy.mode(false, true, true, false, 5_000));
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_EDITOR,
                SoulSetupScreenPolicy.mode(false, true, true, true, 5_000));
    }

    @Test
    void unsupportedSurfaceTakesPrecedenceOverCachedStatusAndPermission() {
        for (boolean status : new boolean[] {false, true}) {
            for (boolean editor : new boolean[] {false, true}) {
                assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_UNSUPPORTED,
                        SoulSetupScreenPolicy.mode(false, false, status, editor, 10_000));
            }
        }
    }

    @Test
    void waitingTimesOutAtFiveSecondsAndRefreshRestartsWaiting() {
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_WAITING,
                SoulSetupScreenPolicy.mode(false, true, false, false, 4_999));
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_TIMEOUT,
                SoulSetupScreenPolicy.mode(false, true, false, false, 5_000));
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_TIMEOUT,
                SoulSetupScreenPolicy.mode(false, true, false, true, 10_000));
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_WAITING,
                SoulSetupScreenPolicy.mode(false, true, false, true, 0));
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_EDITOR,
                SoulSetupScreenPolicy.mode(false, true, true, true, 10_000));
    }

    @Test
    void remoteStatesExplainWhyControlsAreDisabled() {
        assertEquals("This server's Frens version can't be set up from here. Ask the host to update Frens.",
                SoulSetupScreenPolicy.statusMessage(SoulSetupScreenPolicy.Mode.REMOTE_UNSUPPORTED));
        assertEquals("Asking the server…",
                SoulSetupScreenPolicy.statusMessage(SoulSetupScreenPolicy.Mode.REMOTE_WAITING));
        assertEquals("The server didn't answer. Try Refresh.",
                SoulSetupScreenPolicy.statusMessage(SoulSetupScreenPolicy.Mode.REMOTE_TIMEOUT));
        assertEquals("Only the server operator or host can change this.",
                SoulSetupScreenPolicy.statusMessage(SoulSetupScreenPolicy.Mode.REMOTE_VIEWER));
        assertEquals("", SoulSetupScreenPolicy.statusMessage(SoulSetupScreenPolicy.Mode.LOCAL));
        assertEquals("", SoulSetupScreenPolicy.statusMessage(SoulSetupScreenPolicy.Mode.REMOTE_EDITOR));
    }

    @Test
    void modelButtonCoversEveryModeAndBooleanCombination() {
        for (SoulSetupScreenPolicy.Mode mode : SoulSetupScreenPolicy.Mode.values()) {
            for (boolean installed : new boolean[] {false, true}) {
                for (boolean selected : new boolean[] {false, true}) {
                    for (boolean downloading : new boolean[] {false, true}) {
                        var button = SoulSetupScreenPolicy.modelButton(mode, installed, selected, downloading);
                        assertEquals(!installed ? SoulSetupScreenPolicy.ModelLabel.DOWNLOAD
                                : selected ? SoulSetupScreenPolicy.ModelLabel.SELECTED
                                : SoulSetupScreenPolicy.ModelLabel.USE, button.label());
                        assertEquals(!downloading && !(installed && selected)
                                && (mode == SoulSetupScreenPolicy.Mode.LOCAL
                                || mode == SoulSetupScreenPolicy.Mode.REMOTE_EDITOR && installed), button.enabled());
                    }
                }
            }
        }
        assertTrue(SoulSetupScreenPolicy.modelButton(SoulSetupScreenPolicy.Mode.LOCAL,
                false, true, false).enabled());
        assertFalse(SoulSetupScreenPolicy.modelButton(SoulSetupScreenPolicy.Mode.LOCAL,
                false, true, true).enabled());
    }

    // Voice Engine popup: PAD 8, button 130 wide at the right edge, rows 50 tall, blurb from y+14.
    private static final int PAD = 8, BTN_W = 130, ROW_H = 50, BLURB_TOP = 14, LINE_STEP = 10, LINE_H = 9;

    @Test
    void engineTextStopsBeforeTheButtonAtEveryPopupWidth() {
        assertEquals(248, SoulSetupScreenPolicy.engineTextWidth(400, PAD, BTN_W));
        assertEquals(174, SoulSetupScreenPolicy.engineTextWidth(326, PAD, BTN_W));
        assertEquals(152, SoulSetupScreenPolicy.engineTextWidth(304, PAD, BTN_W));
        for (int popupW : new int[] {304, 326, 400}) {
            int textRight = PAD + SoulSetupScreenPolicy.engineTextWidth(popupW, PAD, BTN_W);
            int buttonLeft = popupW - PAD - BTN_W;
            assertTrue(textRight < buttonLeft, "text reaches the button at popup width " + popupW);
        }
        assertEquals(1, SoulSetupScreenPolicy.engineTextWidth(100, PAD, BTN_W));
    }

    @Test
    void engineBlurbsWrapInsideTheirRow() {
        // Worst-case 6 px per character (the default font's widest common advance).
        java.util.function.ToIntFunction<String> w = t -> t.length() * 6;
        String[] blurbs = {
                "Best quality. Uses the GPU. Not set up on this machine.",
                "macOS only. Qwen3-TTS server runs on Apple silicon.",
                "Kyutai, 100M params, 21 presets. One-time ~1 GB install.",
                "Fast, CPU-only, frees the GPU. One-time ~80 MB download.",
        };
        int lastLineBottom = BLURB_TOP + (SoulSetupScreenPolicy.ENGINE_BLURB_MAX_LINES - 1) * LINE_STEP + LINE_H;
        assertTrue(lastLineBottom <= ROW_H, "third blurb line spills into the next row");
        for (int popupW : new int[] {304, 326, 400}) {
            int textW = SoulSetupScreenPolicy.engineTextWidth(popupW, PAD, BTN_W);
            for (String blurb : blurbs) {
                var lines = UiFitPolicy.wrap(blurb, textW, w, SoulSetupScreenPolicy.ENGINE_BLURB_MAX_LINES);
                assertTrue(lines.size() <= SoulSetupScreenPolicy.ENGINE_BLURB_MAX_LINES);
                assertFalse(lines.get(lines.size() - 1).endsWith(UiFitPolicy.ELLIPSIS),
                        "blurb cut at popup width " + popupW + ": " + blurb);
                for (String line : lines) {
                    assertTrue(w.applyAsInt(line) <= textW, "line too wide: " + line);
                }
            }
        }
    }

    @Test
    void modelHeaderLinesFitTheNarrowestPopup() {
        // Worst-case 6 px per character against the text width at the 320 floor (304 - 16).
        java.util.function.ToIntFunction<String> w = t -> t.length() * 6;
        var lines = SoulSetupScreenPolicy.modelHeaderLines("128.0 GB", "1024.0 GB free");
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("System: 128.0 GB RAM"));
        assertFalse(lines.get(0).contains("registry"), "the registry note has its own line");
        assertEquals("Models download from registry.ollama.ai.", lines.get(1));
        for (String line : lines) {
            assertTrue(w.applyAsInt(line) <= 288, "cut at the narrow popup: " + line);
            assertFalse(line.contains("—") || line.contains(";"), line);
        }
    }
    /** Vanilla-ish advances: space 4, '.', ',' and ':' 2, the rest 6, colour codes 0. */
    private static int fontWidth(String text) {
        String shown = text.replaceAll("§.", "");
        int w = 0;
        for (char c : shown.toCharArray()) {
            w += c == ' ' ? 4 : (c == '.' || c == ',' || c == ':' ? 2 : 6);
        }
        return w;
    }

    private static final String LLAMA_8B_BLURB = "§7Largest option. Best replies, slowest, heaviest.";

    @Test
    void modelBlurbStopsBeforeTheButtonAtEveryPopupWidth() {
        for (int popup = 326; popup <= 430; popup++) {
            int maxWidth = SoulSetupScreenPolicy.engineTextWidth(popup, 8, 96);
            java.util.List<String> lines = SoulSetupScreenPolicy.modelBlurbLines(
                    LLAMA_8B_BLURB + " §c(recommends ≥12 GB RAM)", popup, 8, 96, SoulSetupScreenPolicyTest::fontWidth);
            assertTrue(lines.size() <= SoulSetupScreenPolicy.MODEL_BLURB_MAX_LINES, popup + ": " + lines);
            for (String line : lines) {
                assertTrue(fontWidth(line) <= maxWidth, popup + ": " + line);
            }
        }
    }

    @Test
    void llamaBlurbWrapsWholeWordsAtScale5() {
        java.util.List<String> lines = SoulSetupScreenPolicy.modelBlurbLines(
                LLAMA_8B_BLURB, 326, 8, 96, SoulSetupScreenPolicyTest::fontWidth);
        assertEquals(2, lines.size(), lines.toString());
        assertEquals("Largest option. Best replies, slowest, heaviest.",
                String.join(" ", lines).replaceAll("§.", ""));
        for (String line : lines) {
            assertFalse(line.endsWith("…"), line);
            assertTrue(line.startsWith("§7"), "colour carried: " + line);
        }
    }

    @Test
    void llamaBlurbStaysOnOneLineAtTheFullPopup() {
        assertEquals(java.util.List.of(LLAMA_8B_BLURB),
                SoulSetupScreenPolicy.modelBlurbLines(LLAMA_8B_BLURB, 430, 8, 96, SoulSetupScreenPolicyTest::fontWidth));
    }

    @Test
    void ramWarningColourCarriesOntoTheNextLine() {
        java.util.List<String> lines = SoulSetupScreenPolicy.modelBlurbLines(
                "§7Tiny. §c(recommends at least twelve GB of RAM for this one)", 200, 8, 96,
                SoulSetupScreenPolicyTest::fontWidth);
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(1).startsWith("§c"), lines.toString());
    }
}
