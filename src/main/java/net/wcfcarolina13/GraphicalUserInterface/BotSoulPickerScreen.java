package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.wcfcarolina13.network.AiSetupNetworkManager;
import net.wcfcarolina13.network.AiSetupStatus;
import net.wcfcarolina13.network.BotSoulPickerPolicy;
import net.wcfcarolina13.network.BotSoulPickerPolicy.Option;

import java.util.List;
import java.util.function.Consumer;

/**
 * One bot's persona and voice preset, opened from the "Pick…" button on a bot row of
 * {@link AiSetupChecklistScreen}. Two columns of buttons, both from the server's status:
 * personas (owner, op or host; the bot must be enabled once) and the voice engine's presets
 * (op or host). Each button sends one action; the server replies in chat and pushes a fresh
 * status, which redraws the ✔ marks. Option rows and notes come from {@link BotSoulPickerPolicy}.
 *
 * <p>Rendering follows {@link AiSetupChecklistScreen}: panel fill, then {@code super.render},
 * no {@code renderBackground}; 0xFFRRGGBB colours only.
 */
public class BotSoulPickerScreen extends Screen {

    private static final int MAX_POPUP_WIDTH = 360;
    private static final int PAD = 8;
    private static final int GAP = 8;
    private static final int HEADER_H = 36;
    private static final int FOOTER_H = 32;
    private static final int BTN_H = 16;
    private static final int ROW_H = 18;
    private static final int MAX_ROWS = 10;

    private static final int COL_DIM = 0xFFB0B0B0;
    private static final int COL_TXT = 0xFFEFEFEF;

    private final Screen parent;
    private final String botUuid;
    private final Consumer<AiSetupStatus> listener = fresh -> {
        status = fresh;
        clearAndInit();
    };
    private AiSetupStatus status;
    private int personaScroll;
    private int voiceScroll;

    public BotSoulPickerScreen(Screen parent, String botUuid) {
        super(Text.literal("§bPersona and voice"));
        this.parent = parent;
        this.botUuid = botUuid == null ? "" : botUuid;
        AiSetupNetworkManager.Client.registerOnce();
        this.status = AiSetupNetworkManager.Client.latestStatus();
    }

    // ── Data ────────────────────────────────────────────────────────────────

    private AiSetupStatus.Bot bot() {
        return status == null ? null : status.bot(botUuid);
    }

    private String botName() {
        AiSetupStatus.Bot bot = bot();
        return bot == null ? "This bot" : bot.name();
    }

    private String currentProfile() {
        AiSetupStatus.Bot bot = bot();
        return bot == null ? "" : bot.profileId();
    }

    private boolean canAssignVoice() {
        return status != null && status.full();
    }

    private List<Option> personaOptions() {
        return status == null || bot() == null ? List.of()
                : BotSoulPickerPolicy.personaOptions(status.personas(), currentProfile());
    }

    private List<Option> voiceOptions() {
        AiSetupStatus.Bot bot = bot();
        return status == null || bot == null ? List.of()
                : BotSoulPickerPolicy.voiceOptions(status.voicePresets(), bot.voice(), canAssignVoice());
    }

    private String personaNote() {
        if (bot() == null) {
            return "This bot isn't listed any more. Close and refresh.";
        }
        return BotSoulPickerPolicy.personaNote(status.offersPickers(), currentProfile());
    }

    private String voiceNote() {
        if (bot() == null) {
            return "";
        }
        return BotSoulPickerPolicy.voiceNote(canAssignVoice(), status.voicePresets().size());
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    private int popupWidth() {
        return Math.max(200, Math.min(MAX_POPUP_WIDTH, this.width - 16));
    }

    private int columnWidth() {
        return (popupWidth() - PAD * 2 - GAP) / 2;
    }

    /** Button rows that fit on screen, at most {@link #MAX_ROWS}; longer lists scroll. */
    private int rowsShown() {
        int fit = Math.max(1, (this.height - 8 - HEADER_H - 22 - FOOTER_H) / ROW_H);
        int wanted = Math.max(1, Math.max(personaOptions().size(), voiceOptions().size()));
        return Math.min(MAX_ROWS, Math.min(fit, wanted));
    }

    private int popupHeight() {
        return HEADER_H + 22 + rowsShown() * ROW_H + FOOTER_H;
    }

    private int left() {
        return (this.width - popupWidth()) / 2;
    }

    private int top() {
        return Math.max(4, (this.height - popupHeight()) / 2);
    }

    /** Top of the first button row: under the column heading and its note line. */
    private int rowsTop() {
        return top() + HEADER_H + 22;
    }

    private int columnX(boolean voiceColumn) {
        return left() + PAD + (voiceColumn ? columnWidth() + GAP : 0);
    }

    @Override
    protected void init() {
        AiSetupNetworkManager.Client.setListener(listener);
        // A status pushed while another screen held the listener — adopt the newest.
        AiSetupStatus latest = AiSetupNetworkManager.Client.latestStatus();
        if (latest != null) {
            status = latest;
        }
        addColumn(personaOptions(), false);
        addColumn(voiceOptions(), true);
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(left() + popupWidth() - PAD - 80, top() + popupHeight() - 26, 80, 20).build());
    }

    private void addColumn(List<Option> options, boolean voiceColumn) {
        int shown = rowsShown();
        int maxScroll = Math.max(0, options.size() - shown);
        int scroll = Math.max(0, Math.min(voiceColumn ? voiceScroll : personaScroll, maxScroll));
        if (voiceColumn) {
            voiceScroll = scroll;
        } else {
            personaScroll = scroll;
        }
        int x = columnX(voiceColumn);
        int w = columnWidth();
        for (int i = 0; i < shown && scroll + i < options.size(); i++) {
            Option option = options.get(scroll + i);
            String label = option.current() ? "§a" + elide(option.label() + " ✔", w - 8) : elide(option.label(), w - 8);
            ButtonWidget button = ButtonWidget.builder(Text.literal(label), b -> {
                        if (voiceColumn) {
                            AiSetupNetworkManager.Client.setBotVoice(botUuid, option.value());
                        } else {
                            AiSetupNetworkManager.Client.setBotPersona(botUuid, option.value());
                        }
                    })
                    .dimensions(x, rowsTop() + i * ROW_H, w, BTN_H)
                    .build();
            button.active = option.enabled();
            addDrawableChild(button);
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int cx = left();
        int cy = top();
        int w = popupWidth();
        int h = popupHeight();
        context.fill(cx - 1, cy - 1, cx + w + 1, cy + h + 1, 0xFF00CCCC);
        context.fill(cx, cy, cx + w, cy + h, 0xE0181818);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, cy + 5, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, elide(botName(), w - PAD * 2),
                this.width / 2, cy + 17, COL_DIM);

        int colW = columnWidth();
        drawHeading(context, columnX(false), colW, "Persona", personaNote(), personaOptions().size(), personaScroll);
        drawHeading(context, columnX(true), colW, "Voice", voiceNote(), voiceOptions().size(), voiceScroll);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawHeading(DrawContext context, int x, int w, String heading, String note, int count, int scroll) {
        int y = top() + HEADER_H;
        String title = count > rowsShown()
                ? heading + " (" + (scroll + 1) + "–" + Math.min(count, scroll + rowsShown()) + " of " + count + ")"
                : heading;
        context.drawTextWithShadow(this.textRenderer, elide(title, w), x, y, COL_TXT);
        if (!note.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, elide(note, w), x, y + 11, COL_DIM);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount != 0 && mouseY >= rowsTop() && mouseY < rowsTop() + rowsShown() * ROW_H) {
            int step = verticalAmount > 0 ? -1 : 1;
            if (mouseX >= columnX(true)) {
                voiceScroll += step;
            } else {
                personaScroll += step;
            }
            clearAndInit();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private String elide(String text, int maxWidth) {
        return UiFitPolicy.elide(text == null ? "" : text, maxWidth, this.textRenderer::getWidth);
    }

    @Override
    public void removed() {
        AiSetupNetworkManager.Client.clearListener(listener);
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
