package net.shasankp000.GraphicalUserInterface;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.shasankp000.AIPlayer;
import net.shasankp000.AIPlayerClient;
import net.shasankp000.network.ConfirmRecruitmentPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight immersive recruitment dialogue shown in survival recruitment mode.
 *
 * <p>This is intentionally local/scripted: it provides an RPG-style intro without requiring LLM.
 * The server remains authoritative for eligibility + spawning.</p>
 */
public class RecruitmentDialogueScreen extends Screen {

    private static final int PADDING = 10;
    private static final int GAP = 14;
    private static final int TOPICS_MIN_ROW_HEIGHT = 20;
    private static final int OPTION_ROW_SPACING = 10;
    private static final int OPTION_MAX_LINES = 2;
    private static final int OPTION_LINE_SPACING = 2;
    private static final int OPTION_ROW_PADDING = 8;
    private static final float DIALOGUE_WIDTH_RATIO = 0.62f;
    private static final int DIALOGUE_MIN_WIDTH = 240;
    private static final int TOPICS_MIN_WIDTH = 180;
    private static final int TOPICS_HEADER_OFFSET = 18;
    private static final int TOPICS_BOTTOM_MARGIN = 12;

    private static final int BORDER = 0xFF000000;
    private static final int PANEL_FILL = 0xEE101010;
    private static final int HEADER_FILL = 0xFF161616;

    private record Option(String label, Runnable action) {}

    private final String botAlias;
    private final String villageFlavor;
    private final List<String> dialogue = new ArrayList<>();
    private final List<Option> options = new ArrayList<>();
    private boolean waitingServer;

    public RecruitmentDialogueScreen(String botAlias) {
        this(botAlias, "");
    }

    public RecruitmentDialogueScreen(String botAlias, String villageFlavor) {
        super(Text.literal("Recruit"));
        this.botAlias = (botAlias == null || botAlias.isBlank()) ? "Jake" : botAlias.trim();
        this.villageFlavor = villageFlavor == null ? "" : villageFlavor.trim().toLowerCase();
    }

    @Override
    protected void init() {
        waitingServer = false;
        dialogue.clear();
        options.clear();

        if (isPlayerMadeFlavor()) {
            pushNarration("This place doesn't look like an old village... but it behaves like one.");
            pushNarration("Beds are claimed. Workstations hum with routine. Villagers linger instead of fleeing.");
            pushBot("Hmph. You built a refuge.");
        } else {
            pushNarration("You step into the village. A wary figure watches you from the side of the path.");
            pushBot("...You look lost.");
        }

        setOptions(
                new Option("Say hello", () -> {
                    pushPlayer("Hello.");
                    if (isPlayerMadeFlavor()) {
                        pushBot("Name's " + botAlias + ". I watch for trouble.");
                        pushBot("A place like this... it's rare. Don't waste it.");
                    } else {
                        pushBot("Name's " + botAlias + ". I keep the roads safe. Mostly.");
                    }
                    stageAskIntent();
                }),
                new Option("Back away", () -> {
                    pushPlayer("Nevermind.");
                    pushBot("Suit yourself. Stay out of trouble.");
                    close();
                })
        );
    }

    private boolean isPlayerMadeFlavor() {
        // Expected values: "generated" (seed village), "player" (player-made hub), or "unknown".
        return villageFlavor.contains("player") || villageFlavor.contains("hub") || villageFlavor.contains("settle");
    }

    private void stageAskIntent() {
        setOptions(
                new Option("Ask who they are", () -> {
                    pushPlayer("Who are you, really?");
                    pushBot("Someone who got tired of dying alone.");
                }),
                new Option("Ask for help", () -> {
                    pushPlayer("I could use help surviving out there.");
                    pushBot("If you mean it... I can travel with you.");
                    pushBot("But I won't follow a fool. Keep me fed, keep me moving.");
                    stageRecruitConfirm();
                }),
                new Option("Leave", () -> {
                    pushPlayer("Good luck.");
                    pushBot("And you.");
                    close();
                })
        );
    }

    private void stageRecruitConfirm() {
        setOptions(
                new Option("Recruit " + botAlias, () -> {
                    if (waitingServer) {
                        return;
                    }
                    waitingServer = true;
                    pushNarration(botAlias + " nods once, then steps closer.");
                    ClientPlayNetworking.send(new ConfirmRecruitmentPayload(this.botAlias));
                })
        );
    }

    private void setOptions(Option... opts) {
        options.clear();
        if (opts != null) {
            for (Option o : opts) {
                if (o != null) options.add(o);
            }
        }
    }

    private void pushNarration(String line) {
        if (line == null || line.isBlank()) return;
        dialogue.add(line);
    }

    private void pushPlayer(String line) {
        if (line == null || line.isBlank()) return;
        dialogue.add("You: " + line);
        AIPlayerClient.appendDialogue(this.botAlias, "You: " + line);
    }

    private void pushBot(String line) {
        if (line == null || line.isBlank()) return;
        dialogue.add(botAlias + ": " + line);
        AIPlayerClient.appendDialogue(this.botAlias, botAlias + ": " + line);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(null);
        }
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input != null && input.key() == 256 /* ESC */) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean isInside) {
        // Newer MC versions pass click coordinates via a Click object. On some platforms (notably
        // macOS with Retina / HiDPI), these may be in a different coordinate space than Screen
        // layout (scaled GUI coordinates). If we misinterpret them, hit-testing fails and the
        // screen closes when clicking options.
        final double cx = click.x();
        final double cy = click.y();
        final double scale = (this.client != null) ? this.client.getWindow().getScaleFactor() : 1.0;

        // Prepare a few candidate coordinate mappings and pick the first that lands inside the panel.
        // Candidates:
        //  1) raw click coords
        //  2) click coords divided by window scale factor
        //  3) click coords multiplied by window scale factor
        //  4) live mouse position converted from window -> GUI coords
        int rawX = (int) Math.floor(cx);
        int rawY = (int) Math.floor(cy);
        int divX = rawX;
        int divY = rawY;
        int mulX = rawX;
        int mulY = rawY;
        if (scale > 0.0 && scale != 1.0) {
            divX = (int) Math.floor(cx / scale);
            divY = (int) Math.floor(cy / scale);
            mulX = (int) Math.floor(cx * scale);
            mulY = (int) Math.floor(cy * scale);
        }

        int liveGuiX = rawX;
        int liveGuiY = rawY;
        if (this.client != null) {
            try {
                // mouse.getX/Y are in window pixels; map to current GUI size (this.width/height).
                double winX = this.client.mouse.getX();
                double winY = this.client.mouse.getY();
                int winW = this.client.getWindow().getWidth();
                int winH = this.client.getWindow().getHeight();
                if (winW > 0 && winH > 0) {
                    liveGuiX = (int) Math.floor(winX * (double) this.width / (double) winW);
                    liveGuiY = (int) Math.floor(winY * (double) this.height / (double) winH);
                }
            } catch (Throwable ignored) {
                // If internals change, keep the raw click coords.
            }
        }

        if (click.button() != 0) {
            return super.mouseClicked(click, isInside);
        }

        Rect r = computePanel();
        Rect content = computeContentRect(r);
        Rect topics = computeTopicsRect(content);
        OptionLayout layout = computeOptionLayout(topics);
        int startY = layout.startY;
        int rowW = topics.w - 8;
        int rowHeight = layout.rowHeight;
        int rowSpacing = layout.rowSpacing;
        int rowStep = rowHeight + rowSpacing;

        int[][] candidates = new int[][]{
                new int[]{rawX, rawY},
                new int[]{divX, divY},
                new int[]{mulX, mulY},
                new int[]{liveGuiX, liveGuiY}
        };

        Option selectedOption = null;
        boolean panelHit = false;

        outer:
        for (int[] c : candidates) {
            if (c == null || c.length < 2) continue;
            int candidateX = c[0];
            int candidateY = c[1];
            if (r.contains(candidateX, candidateY)) {
                panelHit = true;
            }
            if (candidateX >= topics.x + 4 && candidateX < topics.x + 4 + rowW) {
                for (int i = 0; i < options.size(); i++) {
                    int rowY = startY + i * rowStep;
                    if (candidateY >= rowY && candidateY < rowY + rowHeight) {
                        selectedOption = options.get(i);
                        panelHit = true;
                        break outer;
                    }
                }
            }
        }

        if (!panelHit) {
            return true;
        }

        if (selectedOption != null && !waitingServer) {
            selectedOption.action().run();
            return true;
        }

        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xB0000000);

        Rect r = computePanel();
        drawPanel(context, r);

        Rect content = computeContentRect(r);
        Rect dialogueRect = computeDialogueRect(content);
        Rect topicsRect = computeTopicsRect(content);

        // Column headers
        context.drawText(this.textRenderer, "Dialogue", dialogueRect.x + 6, dialogueRect.y + 4, 0xFFFFE08A, false);
        context.drawText(this.textRenderer, "Topics", topicsRect.x + 6, topicsRect.y + 4, 0xFFFFE08A, false);

        drawDialogue(context, dialogueRect);
        drawOptions(context, topicsRect, mouseX, mouseY);

        // Footer hint
        String hint = waitingServer ? "Recruiting..." : "Click an option; Esc closes";
        int hintW = this.textRenderer.getWidth(hint);
        context.drawText(this.textRenderer, hint, r.x + (r.w - hintW) / 2, r.bottom() - 16, 0xFFB0B0B0, false);

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawDialogue(DrawContext context, Rect rect) {
        int x = rect.x + 6;
        int y = rect.y + 18;
        int w = rect.w - 12;
        int h = rect.h - 24;

        // Render from the end so the newest lines are visible.
        List<OrderedText> wrapped = new ArrayList<>();
        for (String line : dialogue) {
            if (line == null) continue;
            wrapped.addAll(this.textRenderer.wrapLines(Text.literal(line), w));
        }

        int maxLines = Math.max(1, h / this.textRenderer.fontHeight);
        int start = Math.max(0, wrapped.size() - maxLines);

        int rowY = y;
        for (int i = start; i < wrapped.size(); i++) {
            context.drawText(this.textRenderer, wrapped.get(i), x, rowY, 0xFFEFEFEF, false);
            rowY += this.textRenderer.fontHeight;
            if (rowY >= y + h) break;
        }
    }

    private void drawOptions(DrawContext context, Rect rect, int mouseX, int mouseY) {
        int x0 = rect.x + 4;
        int w = rect.w - 8;
        OptionLayout layout = computeOptionLayout(rect);
        int rowHeight = layout.rowHeight;
        int rowSpacing = layout.rowSpacing;
        int rowStep = rowHeight + rowSpacing;
        int startY = layout.startY;

        for (int i = 0; i < options.size(); i++) {
            Option opt = options.get(i);
            int y = startY + i * rowStep;
            if (y + rowHeight < rect.y) {
                continue;
            }
            if (y >= rect.y + rect.h) {
                break;
            }
            boolean hover = mouseX >= x0 && mouseX < x0 + w && mouseY >= y && mouseY < y + rowHeight;
            int fill = hover ? 0xFF2A2A2A : 0xFF1A1A1A;
            if (waitingServer) {
                fill = 0xFF151515;
            }
            context.fill(x0, y, x0 + w, y + rowHeight, fill);

            String label = opt != null ? opt.label : "";
            int color = waitingServer ? 0xFF6F6F6F : 0xFFEFEFEF;
            int textY = y + 2;
            List<OrderedText> wrapped = this.textRenderer.wrapLines(Text.literal(label), w - 8);
            int lines = Math.min(OPTION_MAX_LINES, wrapped.size());
            for (int lineIndex = 0; lineIndex < lines; lineIndex++) {
                context.drawText(this.textRenderer, wrapped.get(lineIndex), x0 + 4, textY + lineIndex * (this.textRenderer.fontHeight + OPTION_LINE_SPACING), color, false);
            }
        }
    }

    private OptionLayout computeOptionLayout(Rect rect) {
        int rowHeight = getOptionRowHeight();
        int baseSpacing = getOptionRowSpacing();
        int startY = rect.y + TOPICS_HEADER_OFFSET;
        if (options.isEmpty()) {
            return new OptionLayout(startY, rowHeight, baseSpacing);
        }

        int availableHeight = Math.max(0, rect.h - TOPICS_HEADER_OFFSET - TOPICS_BOTTOM_MARGIN);
        int rows = options.size();
        int gaps = Math.max(0, rows - 1);
        int totalRowHeight = rows * rowHeight;
        int rowSpacing = baseSpacing;

        if (gaps > 0 && availableHeight > totalRowHeight + gaps * baseSpacing) {
            int extra = availableHeight - totalRowHeight - gaps * baseSpacing;
            int extraPerGap = extra / gaps;
            rowSpacing = baseSpacing + extraPerGap;
            startY += extraPerGap / 2;
        } else if (rows == 1 && availableHeight > rowHeight) {
            startY += (availableHeight - rowHeight) / 2;
        }

        return new OptionLayout(startY, rowHeight, rowSpacing);
    }

    private void drawPanel(DrawContext context, Rect r) {
        context.fill(r.x, r.y, r.right(), r.bottom(), PANEL_FILL);
        context.fill(r.x, r.y, r.right(), r.y + 1, BORDER);
        context.fill(r.x, r.bottom() - 1, r.right(), r.bottom(), BORDER);
        context.fill(r.x, r.y, r.x + 1, r.bottom(), BORDER);
        context.fill(r.right() - 1, r.y, r.right(), r.bottom(), BORDER);

        int headerH = this.textRenderer.fontHeight + 10;
        context.fill(r.x + 1, r.y + 1, r.right() - 1, r.y + headerH, HEADER_FILL);
        String title = isPlayerMadeFlavor() ? "A refuge with rules" : "A familiar face";
        int titleW = this.textRenderer.getWidth(title);
        context.drawText(this.textRenderer, title, r.x + (r.w - titleW) / 2, r.y + 5, 0xFFEFEFEF, false);
    }

    private int getOptionRowHeight() {
        int fontHeight = this.textRenderer != null ? this.textRenderer.fontHeight : 10;
        int multiLineHeight = OPTION_MAX_LINES * fontHeight + OPTION_LINE_SPACING * (OPTION_MAX_LINES - 1);
        return Math.max(TOPICS_MIN_ROW_HEIGHT, multiLineHeight + OPTION_ROW_PADDING);
    }

    private int getOptionRowSpacing() {
        return OPTION_ROW_SPACING;
    }

    private Rect computePanel() {
        int w = Math.min(560, this.width - 40);
        int h = Math.min(280, this.height - 60);
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;
        return new Rect(x, y, w, h);
    }

    private Rect computeContentRect(Rect panel) {
        int headerH = this.textRenderer.fontHeight + 10;
        int x = panel.x + PADDING;
        int y = panel.y + headerH + 6;
        int w = panel.w - PADDING * 2;
        int h = panel.h - headerH - 6 - 22;
        return new Rect(x, y, w, h);
    }

    private Rect computeDialogueRect(Rect content) {
        int available = Math.max(0, content.w - GAP);
        int ratioWidth = Math.round(available * DIALOGUE_WIDTH_RATIO);
        int desiredWidth = Math.max(DIALOGUE_MIN_WIDTH, ratioWidth);
        desiredWidth = Math.max(0, Math.min(desiredWidth, available));
        return new Rect(content.x, content.y, desiredWidth, content.h);
    }

    private Rect computeTopicsRect(Rect content) {
        Rect left = computeDialogueRect(content);
        int x = left.right() + GAP;
        int available = Math.max(0, content.right() - x);
        int width = Math.max(TOPICS_MIN_WIDTH, available);
        if (left.w + width + GAP > content.w) {
            width = Math.max(0, content.w - GAP - left.w);
        }
        if (width > available) {
            width = available;
        }
        return new Rect(x, content.y, width, content.h);
    }

    private static final class OptionLayout {
        final int startY;
        final int rowHeight;
        final int rowSpacing;

        OptionLayout(int startY, int rowHeight, int rowSpacing) {
            this.startY = startY;
            this.rowHeight = rowHeight;
            this.rowSpacing = rowSpacing;
        }
    }

    private static final class Rect {
        final int x;
        final int y;
        final int w;
        final int h;

        Rect(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        int right() { return x + w; }
        int bottom() { return y + h; }
        boolean contains(int px, int py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }
    }
}
