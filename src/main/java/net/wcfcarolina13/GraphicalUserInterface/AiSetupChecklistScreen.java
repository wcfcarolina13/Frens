package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.wcfcarolina13.network.AiSetupChecklistPolicy;
import net.wcfcarolina13.network.AiSetupChecklistPolicy.State;
import net.wcfcarolina13.network.AiSetupChecklistPolicy.Step;
import net.wcfcarolina13.network.AiSetupChecklistPolicy.StepId;
import net.wcfcarolina13.network.AiSetupNetworkManager;
import net.wcfcarolina13.network.AiSetupStatus;

import java.util.List;
import java.util.function.Consumer;

/**
 * "Set up AI companions": the optional-AI checklist. Frens works fully without it; this screen
 * walks a player from "no Ollama" to "my companion answered me", one step and one button per
 * row. Everything shown comes from the server ({@link AiSetupNetworkManager}); step states are
 * computed by {@link AiSetupChecklistPolicy}. Server-changing buttons are disabled with an
 * "Ask the server operator / host" tooltip for players who can't change server settings.
 *
 * <p>Rendering follows {@link SoulModelManagerScreen}: panel fill, then {@code super.render},
 * no {@code renderBackground}; 0xFFRRGGBB colours only.
 */
public class AiSetupChecklistScreen extends Screen {

    private static final String OLLAMA_DOWNLOAD_URL = "https://ollama.com/download";
    private static final Text ASK_OPERATOR = Text.literal("Ask the server operator / host");

    /** Preferred panel width; narrower windows get {@code width - 16} (320 px scaled → 304). */
    private static final int MAX_POPUP_WIDTH = 440;
    private static final int MIN_ROW_H = 22;
    private static final int MAX_ROW_H = 30;
    private static final int RAM_LINE_H = 10;
    private static final int PAD = 8;
    private static final int MAX_BTN_W = 104;
    private static final int BOT_BTN_W = 60;
    private static final int BOT_ROW_H = 16;
    private static final int MAX_BOT_ROWS = 3;
    private static final int HEADER_H = 26;
    private static final int FOOTER_H = 32;
    private static final long NO_ANSWER_MS = 12_000L;
    private static final long REFRESH_COOLDOWN_MS = 2_500L; // strictly above the server's 2 s throttle

    private static final int COL_OK = 0xFF7FD97F;
    private static final int COL_BAD = 0xFFE07070;
    private static final int COL_OPT = 0xFF8FB8FF;
    private static final int COL_DIM = 0xFFB0B0B0;
    private static final int COL_TXT = 0xFFEFEFEF;

    private final Screen parent;
    private final Consumer<AiSetupStatus> listener = this::onStatus;

    private AiSetupStatus status;
    private AiSetupChecklistPolicy.Checklist checklist;
    private boolean requestOnInit = true;
    private boolean available = true;
    private long requestedAtMs;
    private long statusAtMs;
    /** First bot row shown under step 4; the list scrolls when not every bot fits. */
    private int botScroll;
    private ButtonWidget refreshButton;

    public AiSetupChecklistScreen(Screen parent) {
        super(Text.literal("§bSet up AI companions"));
        this.parent = parent;
        // Normally already registered at client init (FrensClient); a no-op then.
        AiSetupNetworkManager.Client.registerOnce();
        this.status = AiSetupNetworkManager.Client.latestStatus();
        this.statusAtMs = AiSetupNetworkManager.Client.latestStatusAtMs();
        this.checklist = AiSetupChecklistPolicy.evaluate(status);
    }

    private void onStatus(AiSetupStatus fresh) {
        status = fresh;
        statusAtMs = Util.getMeasuringTimeMs();
        checklist = AiSetupChecklistPolicy.evaluate(fresh);
        clearAndInit();
    }

    private void request() {
        requestedAtMs = Util.getMeasuringTimeMs();
        available = AiSetupNetworkManager.Client.requestStatus();
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    private int popupWidth() {
        return Math.max(160, Math.min(MAX_POPUP_WIDTH, this.width - 16));
    }

    private int btnW() {
        return Math.min(MAX_BTN_W, popupWidth() / 3);
    }

    private int availableHeight() {
        return this.height - 8;
    }

    /** Height of everything except the bot rows, at a given step-row height. */
    private int fixedHeight(int rowH) {
        return HEADER_H + StepId.values().length * rowH + RAM_LINE_H + FOOTER_H;
    }

    /**
     * Bot rows that fit with the step rows at their minimum height (at most {@link #MAX_BOT_ROWS});
     * the rest scroll. At 320x240 that is two rows.
     */
    private int botRowsShown() {
        int fit = Math.max(0, (availableHeight() - fixedHeight(MIN_ROW_H)) / BOT_ROW_H);
        return Math.min(checklist.bots().size(), Math.min(MAX_BOT_ROWS, fit));
    }

    private int maxBotScroll() {
        return Math.max(0, checklist.bots().size() - botRowsShown());
    }

    /** Row height shrinks on short windows so the whole panel, footer included, stays on screen. */
    private int rowHeight() {
        int spare = availableHeight() - HEADER_H - RAM_LINE_H - FOOTER_H - botRowsShown() * BOT_ROW_H;
        return Math.max(MIN_ROW_H, Math.min(MAX_ROW_H, spare / StepId.values().length));
    }

    private int popupHeight() {
        return fixedHeight(rowHeight()) + botRowsShown() * BOT_ROW_H;
    }

    private int left() {
        return (this.width - popupWidth()) / 2;
    }

    private int top() {
        return Math.max(4, (this.height - popupHeight()) / 2);
    }

    /** Y of a step row; rows below step 2 shift by the RAM line, below step 4 by the bot rows. */
    private int stepY(StepId id) {
        int y = top() + HEADER_H + id.ordinal() * rowHeight();
        if (id.ordinal() > StepId.MODEL.ordinal()) {
            y += RAM_LINE_H;
        }
        if (id.ordinal() > StepId.BOTS.ordinal()) {
            y += botRowsShown() * BOT_ROW_H;
        }
        return y;
    }

    @Override
    protected void init() {
        AiSetupNetworkManager.Client.setListener(listener);
        // A status pushed while a child screen was open (e.g. after choosing a model) arrived
        // with no listener attached — adopt it.
        if (AiSetupNetworkManager.Client.latestStatusAtMs() > statusAtMs
                && AiSetupNetworkManager.Client.latestStatus() != null) {
            status = AiSetupNetworkManager.Client.latestStatus();
            statusAtMs = AiSetupNetworkManager.Client.latestStatusAtMs();
            checklist = AiSetupChecklistPolicy.evaluate(status);
        }
        if (requestOnInit) {
            requestOnInit = false;
            // First open always asks; a quick return from a child screen would only be dropped
            // by the server's 2 s throttle and leave "Refreshing…" hanging.
            if (requestedAtMs == 0L || Util.getMeasuringTimeMs() - requestedAtMs >= REFRESH_COOLDOWN_MS) {
                request();
            }
        }
        botScroll = Math.max(0, Math.min(botScroll, maxBotScroll()));
        int right = left() + popupWidth() - PAD;

        addStepButton(StepId.OLLAMA, "Get Ollama", b -> ConfirmLinkScreen.open(this, OLLAMA_DOWNLOAD_URL, true));
        addStepButton(StepId.MODEL, "Choose model…", b -> {
            if (this.client != null) this.client.setScreen(new SoulModelManagerScreen(this));
        });
        Step souls = checklist.step(StepId.SOULS_ON);
        addStepButton(StepId.SOULS_ON, souls.state() == State.DONE ? "On ✔" : "Turn on",
                b -> AiSetupNetworkManager.Client.setSoulsEnabled(true));
        addStepButton(StepId.TEST_CHAT, "Say hi", b -> {
            String bot = checklist.testBotName();
            if (this.client != null && !bot.isEmpty()) {
                // Pre-filled only: the player reads it and presses Enter themselves.
                this.client.setScreen(new ChatScreen(AiSetupChecklistPolicy.greeting(bot), false));
            }
        });
        addStepButton(StepId.VOICE, "Voice…", b -> {
            if (this.client != null) this.client.setScreen(new SoulVoiceEngineScreen(this));
        });

        // One Enable button per listed bot, under step 4.
        List<AiSetupChecklistPolicy.BotRow> rows = checklist.bots();
        int rowY = stepY(StepId.BOTS) + rowHeight();
        for (int i = 0; i < botRowsShown(); i++) {
            AiSetupChecklistPolicy.BotRow row = rows.get(botScroll + i);
            ButtonWidget enable = ButtonWidget.builder(
                            Text.literal(row.state() == State.DONE ? "On ✔" : "Enable"),
                            b -> AiSetupNetworkManager.Client.enableBot(row.uuid()))
                    .dimensions(right - BOT_BTN_W, rowY + i * BOT_ROW_H - 2, BOT_BTN_W, 14)
                    .build();
            enable.active = row.enableButton() && available;
            addDrawableChild(enable);
        }

        refreshButton = ButtonWidget.builder(Text.literal("Refresh"), b -> request())
                .dimensions(left() + PAD, top() + popupHeight() - 26, 80, 20).build();
        addDrawableChild(refreshButton);
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(right - 80, top() + popupHeight() - 26, 80, 20).build());
    }

    private void addStepButton(StepId id, String label, ButtonWidget.PressAction action) {
        Step step = checklist.step(id);
        ButtonWidget button = ButtonWidget.builder(Text.literal(label), action)
                .dimensions(left() + popupWidth() - PAD - btnW(), stepY(id) + 3, btnW(), 18)
                .build();
        button.active = step.buttonEnabled() && available;
        if (step.needsOperator()) {
            button.setTooltip(Tooltip.of(ASK_OPERATOR));
        } else if (id == StepId.MODEL && status != null && status.ollama() != null) {
            button.setTooltip(Tooltip.of(Text.literal(
                    AiSetupChecklistPolicy.ramGuidance(status.ollama().hostRamGb()))));
        }
        addDrawableChild(button);
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private static int colour(State state) {
        return switch (state) {
            case DONE -> COL_OK;
            case TODO -> COL_BAD;
            case OPTIONAL -> COL_OPT;
            case UNKNOWN -> COL_DIM;
        };
    }

    private String title(StepId id) {
        String where = checklist.onServerMachine() ? " (on the server machine)" : "";
        return switch (id) {
            case OLLAMA -> "1. Ollama installed" + where;
            case MODEL -> "2. AI model downloaded" + where;
            case SOULS_ON -> "3. Soul Chat turned on";
            case BOTS -> "4. Companion enabled" + (maxBotScroll() > 0
                    ? "  §7(" + (botScroll + 1) + "–" + (botScroll + botRowsShown()) + " of "
                            + checklist.bots().size() + ", scroll)" : "");
            case TEST_CHAT -> "5. Test chat";
            case VOICE -> "6. Voice (optional)" + where;
        };
    }

    private String headerLine(long now) {
        if (!available) {
            return "This server doesn't offer AI setup (it needs Frens 1.1.225 or newer).";
        }
        boolean waiting = statusAtMs < requestedAtMs;
        if (waiting && now - requestedAtMs > NO_ANSWER_MS) {
            return "No answer from the server yet — press Refresh.";
        }
        if (waiting) {
            return status == null ? "Checking the server…" : "Refreshing…";
        }
        return "Optional: Frens works fully without AI chat.";
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        long now = Util.getMeasuringTimeMs();
        if (refreshButton != null) {
            refreshButton.active = now - requestedAtMs >= REFRESH_COOLDOWN_MS;
        }
        int cx = left();
        int cy = top();
        int h = popupHeight();
        int w = popupWidth();
        context.fill(cx - 1, cy - 1, cx + w + 1, cy + h + 1, 0xFF00CCCC);
        context.fill(cx, cy, cx + w, cy + h, 0xE0181818);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, cy + 5, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, elide(headerLine(now), w - PAD * 2),
                this.width / 2, cy + 15, COL_DIM);

        int textW = w - PAD * 2 - btnW() - 16;
        for (Step step : checklist.steps()) {
            int y = stepY(step.id());
            context.drawTextWithShadow(this.textRenderer, step.state().icon(), cx + PAD, y + 3, colour(step.state()));
            context.drawTextWithShadow(this.textRenderer, elide(title(step.id()), textW), cx + PAD + 12, y + 3, COL_TXT);
            context.drawTextWithShadow(this.textRenderer, elide(step.detail(), textW), cx + PAD + 12, y + 13, COL_DIM);
            if (step.id() == StepId.MODEL) {
                double ram = status != null && status.ollama() != null ? status.ollama().hostRamGb() : -1;
                context.drawTextWithShadow(this.textRenderer,
                        elide(AiSetupChecklistPolicy.ramGuidance(ram), w - PAD * 2 - 12),
                        cx + PAD + 12, y + rowHeight(), 0xFF8A8A8A);
            }
        }

        List<AiSetupChecklistPolicy.BotRow> rows = checklist.bots();
        int rowY = stepY(StepId.BOTS) + rowHeight();
        for (int i = 0; i < botRowsShown(); i++) {
            AiSetupChecklistPolicy.BotRow row = rows.get(botScroll + i);
            context.drawTextWithShadow(this.textRenderer, row.state().icon(), cx + PAD + 12, rowY + i * BOT_ROW_H,
                    colour(row.state()));
            context.drawTextWithShadow(this.textRenderer, elide(row.name(), w - PAD * 2 - BOT_BTN_W - 32), cx + PAD + 24,
                    rowY + i * BOT_ROW_H, COL_TXT);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int rowsTop = stepY(StepId.BOTS);
        int rowsBottom = stepY(StepId.BOTS) + rowHeight() + botRowsShown() * BOT_ROW_H;
        if (maxBotScroll() > 0 && verticalAmount != 0 && mouseY >= rowsTop && mouseY < rowsBottom
                && mouseX >= left() && mouseX < left() + popupWidth()) {
            int next = Math.max(0, Math.min(maxBotScroll(), botScroll + (verticalAmount > 0 ? -1 : 1)));
            if (next != botScroll) {
                botScroll = next;
                clearAndInit();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private String elide(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        return this.textRenderer.trimToWidth(text, Math.max(0, maxWidth - this.textRenderer.getWidth("…"))) + "…";
    }

    @Override
    public void removed() {
        AiSetupNetworkManager.Client.clearListener(listener);
        // Coming back from a child screen (model / voice / chat) asks for a fresh status.
        requestOnInit = true;
        super.removed();
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        } else {
            super.close();
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
