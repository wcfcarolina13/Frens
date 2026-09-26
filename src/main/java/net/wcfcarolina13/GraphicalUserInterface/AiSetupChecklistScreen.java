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

    private static final int POPUP_WIDTH = 440;
    private static final int PAD = 8;
    private static final int BTN_W = 104;
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
    private ButtonWidget refreshButton;

    public AiSetupChecklistScreen(Screen parent) {
        super(Text.literal("§bSet up AI companions"));
        this.parent = parent;
        // Receiver first, so the reply to init()'s request can't arrive before it exists.
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

    private int botRowsShown() {
        return Math.min(MAX_BOT_ROWS, checklist.bots().size());
    }

    /** Row height shrinks on short windows so the whole list stays on screen. */
    private int rowHeight() {
        int fixed = HEADER_H + FOOTER_H + botRowsShown() * BOT_ROW_H + 10 /* RAM line */ + 12;
        int fit = (this.height - 16 - fixed) / StepId.values().length;
        return Math.max(22, Math.min(30, fit));
    }

    private int popupHeight() {
        return HEADER_H + StepId.values().length * rowHeight() + botRowsShown() * BOT_ROW_H + 10 + FOOTER_H;
    }

    private int left() {
        return (this.width - POPUP_WIDTH) / 2;
    }

    private int top() {
        return Math.max(4, (this.height - popupHeight()) / 2);
    }

    /** Y of a step row; rows below step 2 shift by the RAM line, below step 4 by the bot rows. */
    private int stepY(StepId id) {
        int y = top() + HEADER_H + id.ordinal() * rowHeight();
        if (id.ordinal() > StepId.MODEL.ordinal()) {
            y += 10;
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
        int bx = left() + POPUP_WIDTH - PAD - BTN_W;

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
            AiSetupChecklistPolicy.BotRow row = rows.get(i);
            ButtonWidget enable = ButtonWidget.builder(
                            Text.literal(row.state() == State.DONE ? "On ✔" : "Enable"),
                            b -> AiSetupNetworkManager.Client.enableBot(row.uuid()))
                    .dimensions(left() + POPUP_WIDTH - PAD - BOT_BTN_W, rowY + i * BOT_ROW_H - 2, BOT_BTN_W, 14)
                    .build();
            enable.active = row.enableButton() && available;
            addDrawableChild(enable);
        }

        refreshButton = ButtonWidget.builder(Text.literal("Refresh"), b -> request())
                .dimensions(left() + PAD, top() + popupHeight() - 26, 80, 20).build();
        addDrawableChild(refreshButton);
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(bx + BTN_W - 80, top() + popupHeight() - 26, 80, 20).build());
    }

    private void addStepButton(StepId id, String label, ButtonWidget.PressAction action) {
        Step step = checklist.step(id);
        ButtonWidget button = ButtonWidget.builder(Text.literal(label), action)
                .dimensions(left() + POPUP_WIDTH - PAD - BTN_W, stepY(id) + 3, BTN_W, 18)
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
            case BOTS -> "4. Companion enabled" + (checklist.bots().size() > MAX_BOT_ROWS
                    ? "  §7(+" + (checklist.bots().size() - MAX_BOT_ROWS) + " more: /bot soul enable <bot>)" : "");
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
        context.fill(cx - 1, cy - 1, cx + POPUP_WIDTH + 1, cy + h + 1, 0xFF00CCCC);
        context.fill(cx, cy, cx + POPUP_WIDTH, cy + h, 0xE0181818);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, cy + 5, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, headerLine(now), this.width / 2, cy + 15, COL_DIM);

        int textW = POPUP_WIDTH - PAD * 2 - BTN_W - 16;
        for (Step step : checklist.steps()) {
            int y = stepY(step.id());
            context.drawTextWithShadow(this.textRenderer, step.state().icon(), cx + PAD, y + 3, colour(step.state()));
            context.drawTextWithShadow(this.textRenderer, elide(title(step.id()), textW), cx + PAD + 12, y + 3, COL_TXT);
            context.drawTextWithShadow(this.textRenderer, elide(step.detail(), textW), cx + PAD + 12, y + 13, COL_DIM);
            if (step.id() == StepId.MODEL) {
                double ram = status != null && status.ollama() != null ? status.ollama().hostRamGb() : -1;
                context.drawTextWithShadow(this.textRenderer,
                        elide(AiSetupChecklistPolicy.ramGuidance(ram), POPUP_WIDTH - PAD * 2 - 12),
                        cx + PAD + 12, y + rowHeight(), 0xFF8A8A8A);
            }
        }

        List<AiSetupChecklistPolicy.BotRow> rows = checklist.bots();
        int rowY = stepY(StepId.BOTS) + rowHeight();
        for (int i = 0; i < botRowsShown(); i++) {
            AiSetupChecklistPolicy.BotRow row = rows.get(i);
            context.drawTextWithShadow(this.textRenderer, row.state().icon(), cx + PAD + 12, rowY + i * BOT_ROW_H,
                    colour(row.state()));
            context.drawTextWithShadow(this.textRenderer, elide(row.name(), textW - 24), cx + PAD + 24,
                    rowY + i * BOT_ROW_H, COL_TXT);
        }

        super.render(context, mouseX, mouseY, delta);
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
