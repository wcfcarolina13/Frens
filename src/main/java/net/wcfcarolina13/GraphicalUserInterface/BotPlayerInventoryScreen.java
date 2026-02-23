package net.wcfcarolina13.GraphicalUserInterface;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.items.ModItems;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.network.CompanionQuestStateRequestPayload;
import net.wcfcarolina13.network.CompanionQuestTopicPayload;
import net.wcfcarolina13.network.RecruitmentAdminActionPayload;
import net.wcfcarolina13.network.RequestRecruitmentReplayPayload;
import net.wcfcarolina13.network.RequestRecruitmentDialoguePayload;
import net.wcfcarolina13.ui.BotPlayerInventoryScreenHandler;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class BotPlayerInventoryScreen extends HandledScreen<BotPlayerInventoryScreenHandler> {
    private static final Identifier BACKGROUND_TEXTURE = Identifier.of("minecraft", "textures/gui/container/inventory.png");
    private static final int SECTION_WIDTH = 176;
    private static final int SECTION_HEIGHT = 166;
    private static final int BLOCK_GAP = 12;
    private static final int STATS_AREA_HEIGHT = 48;
    private static final int TOPIC_PADDING = 6;
    // Row height must comfortably fit the font + padding; 10px is too tight and causes visual overlap.
    private static final int TOPIC_ROW_HEIGHT = 12;
    private static final int TOPIC_CONTROL_GAP = 2;

    // Collapsed (shared inventory) quick-actions grid.
    private static final String TOPIC_PANEL_TITLE = "Actions";
    private static final int QUICK_TOPIC_COLS = 3;
    private static final int QUICK_TOPIC_MAX_ROWS = 2;
    private static final int QUICK_TOPIC_GAP = 2;
    private static final int QUICK_TOPIC_MIN_BUTTON_H = 12;
    private static final float QUICK_TOPIC_TEXT_SCALE = 0.85f;
    private static final double FOLLOW_DISTANCE_STEP = 1.0D;
    private static final double FOLLOW_DISTANCE_MIN = 1.0D;
    private static final double FOLLOW_DISTANCE_MAX = 64.0D;
    private static final double FOLLOW_DISTANCE_DEFAULT = 4.0D;
    private static final int WOODCUT_TREE_COUNT_MIN = 1;
    private static final int WOODCUT_TREE_COUNT_MAX = 64;
    private static final int WOODCUT_TREE_COUNT_DEFAULT = 4;
    private static final int SKILL_COUNT_UNSET = 0;
    private static final int SKILL_FISH_COUNT_MIN = 1;
    private static final int SKILL_FISH_COUNT_MAX = 128;
    private static final int SKILL_FISH_COUNT_DEFAULT = 8;
    private static final int SKILL_WOOL_COUNT_MIN = 1;
    private static final int SKILL_WOOL_COUNT_MAX = 256;
    private static final int SKILL_WOOL_COUNT_DEFAULT = 16;
    private static final int SKILL_STRIPMINE_COUNT_MIN = 1;
    private static final int SKILL_STRIPMINE_COUNT_MAX = 128;
    private static final int SKILL_STRIPMINE_COUNT_DEFAULT = 8;
    private static final int SKILL_ASCENT_COUNT_MIN = 1;
    private static final int SKILL_ASCENT_COUNT_MAX = 128;
    private static final int SKILL_ASCENT_COUNT_DEFAULT = 5;
    private static final int SKILL_DESCENT_COUNT_MIN = 1;
    private static final int SKILL_DESCENT_COUNT_MAX = 128;
    private static final int SKILL_DESCENT_COUNT_DEFAULT = 5;
    private static final int TOPICS_OVERLAY_SEARCH_H = 14;
    private static final int TOPICS_OVERLAY_SEARCH_GAP = 4;
    private static final int TOPICS_OVERLAY_SCROLLBAR_W = 10;
    private static final int TOPICS_OVERLAY_SCROLLBAR_MIN_THUMB_H = 18;
    private OtherClientPlayerEntity fallbackBot;
    private final String botAlias;
    private float lastMouseX;
    private float lastMouseY;
    private boolean topicsExpanded;

    // Overlay column split (0..1) for Dialogue vs Topics list.
    private double overlaySplitRatio = 0.56;
    private boolean overlayDraggingSplit = false;
    private boolean overlayDraggingListScroll = false;
    private int overlayListScrollGrabOffsetY = 0;

    // Hover tooltip (delayed) for entries in the expanded overlay.
    private TopicEntry overlayHoveredEntry = null;
    private long overlayHoverStartedAtMs = 0L;
    private static final long OVERLAY_HOVER_TOOLTIP_DELAY_MS = 1000L;

    // Skills list scroll (always used for the small panel; also used for overlay when Skills tab is selected).
    private int skillScrollIndex;
    // Dialogue list scroll (only used for overlay when Dialogue tab is selected).
    private int dialogueScrollIndex;
    // Admin list scroll (only used for overlay when Admin tab is selected).
    private int adminScrollIndex;
    private TopicCategory overlayCategory = TopicCategory.SKILL;
    private int woodcutTreeCount = WOODCUT_TREE_COUNT_DEFAULT;
    private int fishTargetCount = SKILL_COUNT_UNSET;
    private int woolTargetCount = SKILL_COUNT_UNSET;
    private int stripmineLength = SKILL_COUNT_UNSET;
    private int ascentBlocks = SKILL_COUNT_UNSET;
    private int descentBlocks = SKILL_COUNT_UNSET;
    private boolean ascentSurfaceMode = false;
    private String topicSearchQuery = "";
    private boolean topicSearchFocused = false;

    // Best-effort: request server stage/permanent snapshot once per overlay open (used for stage-gated dialogue topics).
    private boolean companionQuestStateRequested = false;

    // Simple safety guard for destructive admin actions.
    private boolean adminResetConfirmArmed = false;
    private long adminResetConfirmArmedAtMs = 0L;

    private static final int TOPICS_OVERLAY_MAX_WIDTH = 440;
    // Give the expanded overlay more vertical real estate so rows are not cramped.
    private static final int TOPICS_OVERLAY_MAX_HEIGHT = 320;
    private static final int TOPICS_OVERLAY_MIN_WIDTH = 320;
    private static final int TOPICS_OVERLAY_MIN_HEIGHT = 160;
    private static final int TOPICS_OVERLAY_PADDING = 10;
    private static final int TOPICS_OVERLAY_HEADER_PAD = 6;
    private static final int TOPICS_OVERLAY_FOOTER_PAD = 6;
    private static final int TOPICS_OVERLAY_COLUMN_GAP = 10;
    private static final int TOPICS_OVERLAY_LIST_HEADER_H = 18; // tab bar height for right column

    // Palette (Skyrim/Morrowind-ish: warmer parchment, less harsh whites).
    private static final int COLOR_TEXT_PARCHMENT = 0xFFD8C7A0;
    private static final int COLOR_TEXT_PLAYER = 0xFFFFE08A;
    private static final int COLOR_TEXT_SYSTEM = 0xFFB0B0B0;
    private static final int COLOR_TEXT_DISABLED = 0xFF6F6F6F;
    private static final int COLOR_TEXT_SUBTLE = 0xFF8E8E8E;
    // Morrowind-inspired accents.
    private static final int COLOR_TEXT_TOPIC = 0xFF5BA6FF;
    private static final int COLOR_TEXT_RESPONSE = 0xFFCC4B4B;

    // Last selected dialogue topic (for a topic-header style dialogue column).
    private String lastDialogueTopicLabel = "";
    private String lastDialogueTopicKey = "";

    private record DialogueResponseHitbox(Rect rect, TopicEntry entry) {}
    private final java.util.List<DialogueResponseHitbox> dialogueResponseHitboxes = new java.util.ArrayList<>();

    private record Rect(int x, int y, int w, int h) {
        int right() { return x + w; }
        int bottom() { return y + h; }
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private record OverlayColumns(int contentX, int contentY, int contentW, int contentH,
                                  int dialogueX, int dialogueW,
                                  int dividerX, int dividerW,
                                  int listX, int listW,
                                  Rect dividerRect) {}

    private enum TopicAction {
        COMPANION_COME,
        COMPANION_SUMMON,
        COMPANION_HOME,
        OPEN_SPELLS,
        OPEN_GUIDE,
        STOP,
        RESUME,
        FOLLOW,
        GUARD,
        PATROL,
        RETURN_HOME,
        SLEEP,
        AUTO_RETURN_SUNSET,
        AUTO_RETURN_SUNSET_GUARD_PATROL,
        IDLE_HOBBIES,
        AUTO_HUNT_STARVING,
        GAMEPLAY_TIPS,
        IDLE_HOBBIES_ANYWHERE,
        BARITONE_PATHFINDER,
        UNLEASH_TETHERED,
        LEASH_ON_DISMOUNT,
        DROP_SWEEP,
        BASES,
        CRAFTING,
        COOKING,
        HUNTING,
        SKILL_FISH,
        SKILL_WOODCUT,
        SKILL_WOODCUT_CLEANUP,
        SKILL_WOOL,
        CONSTRUCTION,
        SKILL_HOVEL,
        SKILL_BURROW,
        SKILL_FARM,
        SKILL_COLLECT_DIRT,
        SKILL_MINING,
        SKILL_STRIPMINE,
        SKILL_ASCENT,
        SKILL_DESCENT,
        OPEN_BOT_CONTROLS
    }

    private enum TopicCategory {
        SKILL,
        DIALOGUE,
        ADMIN
    }

    private static final class TopicEntry {
        private final String label;
        private final TopicCategory category;
        private final TopicAction action;
        private final boolean toggle;
        private final int indent;

        // For dialogue topics, identifies which scripted response to use.
        private final String dialogueKey;

        private TopicEntry(String label, TopicCategory category, TopicAction action, boolean toggle, int indent, String dialogueKey) {
            this.label = label;
            this.category = category;
            this.action = action;
            this.toggle = toggle;
            this.indent = indent;
            this.dialogueKey = dialogueKey;
        }

        private static TopicEntry skill(String label, TopicAction action, boolean toggle, int indent) {
            return new TopicEntry(label, TopicCategory.SKILL, action, toggle, indent, null);
        }

        private static TopicEntry dialogue(String label, String dialogueKey) {
            return new TopicEntry(label, TopicCategory.DIALOGUE, null, false, 0, dialogueKey);
        }

        private static TopicEntry admin(String label, String adminKey) {
            // Reuse dialogueKey field to carry the admin action key.
            return new TopicEntry(label, TopicCategory.ADMIN, null, false, 0, adminKey);
        }
    }

    private static final List<TopicEntry> SKILL_TOPIC_ENTRIES = List.of(
            TopicEntry.skill("📘 Guide", TopicAction.OPEN_GUIDE, false, 0),
            TopicEntry.skill("🛑 Stop", TopicAction.STOP, false, 0),
            TopicEntry.skill("▶ Resume", TopicAction.RESUME, false, 0),
            TopicEntry.skill("👣 Follow", TopicAction.FOLLOW, true, 0),
            TopicEntry.skill("🛡 Guard", TopicAction.GUARD, true, 0),
            TopicEntry.skill("Patrol", TopicAction.PATROL, true, 0),
            TopicEntry.skill("🏠 Return Home", TopicAction.RETURN_HOME, true, 0),
            TopicEntry.skill("🛌 Sleep", TopicAction.SLEEP, false, 0),
            TopicEntry.skill("Auto Home @ Sunset", TopicAction.AUTO_RETURN_SUNSET, true, 0),
            TopicEntry.skill("Guard/Patrol eligible", TopicAction.AUTO_RETURN_SUNSET_GUARD_PATROL, true, 1),
            TopicEntry.skill("Idle Hobbies", TopicAction.IDLE_HOBBIES, true, 0),
            TopicEntry.skill("Auto Hunt (Starving)", TopicAction.AUTO_HUNT_STARVING, true, 1),
            TopicEntry.skill("Unleash Tethered", TopicAction.UNLEASH_TETHERED, true, 0),
            TopicEntry.skill("Leash on Dismount", TopicAction.LEASH_ON_DISMOUNT, true, 0),
            TopicEntry.skill("🧹 Cleanup", TopicAction.DROP_SWEEP, false, 0),
            TopicEntry.skill("Bases >", TopicAction.BASES, false, 0),
            TopicEntry.skill("Crafting >", TopicAction.CRAFTING, false, 0),
            TopicEntry.skill("Construction >", TopicAction.CONSTRUCTION, false, 0),
            TopicEntry.skill("Cooking >", TopicAction.COOKING, false, 0),
            TopicEntry.skill("Hunting >", TopicAction.HUNTING, false, 0),
            TopicEntry.skill("Fishing", TopicAction.SKILL_FISH, false, 0),
            TopicEntry.skill("Woodcut", TopicAction.SKILL_WOODCUT, false, 0),
            TopicEntry.skill("Woodcut Cleanup", TopicAction.SKILL_WOODCUT_CLEANUP, false, 1),
            TopicEntry.skill("Wool", TopicAction.SKILL_WOOL, false, 0),
            TopicEntry.skill("Farming", TopicAction.SKILL_FARM, false, 0),
            TopicEntry.skill("Collect Dirt", TopicAction.SKILL_COLLECT_DIRT, false, 1),
            TopicEntry.skill("Mining", TopicAction.SKILL_MINING, false, 0),
            TopicEntry.skill("Stripmine", TopicAction.SKILL_STRIPMINE, false, 1),
            TopicEntry.skill("Ascent", TopicAction.SKILL_ASCENT, false, 1),
            TopicEntry.skill("Descent", TopicAction.SKILL_DESCENT, false, 1)
    );

            // Curated, non-scroll quick actions for the collapsed panel.
            // (These are intentionally short labels; the expanded overlay still shows the full list.)
            private static final List<TopicEntry> QUICK_TOPIC_ENTRIES = List.of(
                TopicEntry.skill("🛑 Stop", TopicAction.STOP, false, 0),
                TopicEntry.skill("👣 Follow", TopicAction.FOLLOW, true, 0),
                TopicEntry.skill("🏠 Home", TopicAction.RETURN_HOME, true, 0),
                TopicEntry.skill("🛌 Sleep", TopicAction.SLEEP, false, 0),
                TopicEntry.skill("🛡 Guard", TopicAction.GUARD, true, 0),
                TopicEntry.skill("🧹 Cleanup", TopicAction.DROP_SWEEP, false, 0)
            );

    // Dialogue/quest topics are intentionally local/scripted: they feed the dialogue panel without
    // triggering bot skills or commands.
    private static final List<TopicEntry> DIALOGUE_TOPIC_ENTRIES = List.of(
            TopicEntry.dialogue("Start a talk", "recruit_contact"),
            TopicEntry.dialogue("Replay intro", "recruit_replay"),
            TopicEntry.dialogue("How are we?", "companion_status"),
            TopicEntry.dialogue("Check progress", "companion_check"),
            TopicEntry.dialogue("Ask: Biomes", "batch3_biomes"),
            TopicEntry.dialogue("Ask: Structures", "batch3_structures"),
            TopicEntry.dialogue("Ask: Dimensions", "batch3_dimensions"),
            TopicEntry.dialogue("Ask: Traders & Mounts", "batch3_traders_mounts"),
            TopicEntry.dialogue("Ask: Travel", "batch3_travel"),
            TopicEntry.dialogue("Make this home", "companion_anchor_set"),
            TopicEntry.dialogue("About the village", "village_about"),
            TopicEntry.dialogue("Why stay?", "stay_conditions"),
            TopicEntry.dialogue("What's missing?", "village_missing"),
            TopicEntry.dialogue("Next projects", "village_projects"),
            TopicEntry.dialogue("Your past", "bot_past"),
            TopicEntry.dialogue("A promise", "promise"),
            TopicEntry.dialogue("Goodbye", "goodbye")
    );

            // Operator-only admin tools for survival recruitment mode.
            private static final List<TopicEntry> ADMIN_TOPIC_ENTRIES = List.of(
                // ── Screens ──
                new TopicEntry("Bot Controls >", TopicCategory.ADMIN, TopicAction.OPEN_BOT_CONTROLS, false, 0, null),
                new TopicEntry("Spells >", TopicCategory.ADMIN, TopicAction.OPEN_SPELLS, false, 0, null),
                // ── Toggles ──
                new TopicEntry("Gameplay Tips", TopicCategory.ADMIN, TopicAction.GAMEPLAY_TIPS, true, 0, null),
                new TopicEntry("Idle Hobbies Anywhere", TopicCategory.ADMIN, TopicAction.IDLE_HOBBIES_ANYWHERE, true, 0, null),
                new TopicEntry("Baritone Pathfinder", TopicCategory.ADMIN, TopicAction.BARITONE_PATHFINDER, true, 0, null),
                // ── Items ──
                TopicEntry.admin("Give Wizard's Tome", "give_wizard_tome"),
                // ── Recruitment ──
                TopicEntry.admin("Recruit status", "recruit_status"),
                TopicEntry.admin("Enable recruit", "recruit_enable"),
                TopicEntry.admin("Disable recruit", "recruit_disable"),
                TopicEntry.admin("Reset recruit", "recruit_reset"),
                // ── Village ──
                TopicEntry.admin("Set village anchor", "anchor_set"),
                TopicEntry.admin("Clear village anchor", "anchor_clear"),
                // ── Debug: quest stages ──
                TopicEntry.admin("Set stage 0", "setstage:0"),
                TopicEntry.admin("Set stage 1", "setstage:1"),
                TopicEntry.admin("Set stage 2", "setstage:2"),
                TopicEntry.admin("Set stage 3", "setstage:3"),
                TopicEntry.admin("Set stage 4", "setstage:4")
            );

    public BotPlayerInventoryScreen(BotPlayerInventoryScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = SECTION_WIDTH * 2 + BLOCK_GAP;
        this.backgroundHeight = SECTION_HEIGHT + STATS_AREA_HEIGHT;
        this.titleX = 8;
        this.titleY = 6;
        this.playerInventoryTitleX = SECTION_WIDTH + BLOCK_GAP + 8;
        this.playerInventoryTitleY = 6;
        this.botAlias = extractAlias(title.getString());
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = (this.width - this.backgroundWidth) / 2;
        int y = (this.height - this.backgroundHeight) / 2;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, BACKGROUND_TEXTURE, x, y, 0f, 0f,
                SECTION_WIDTH, SECTION_HEIGHT, 256, 256);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, BACKGROUND_TEXTURE, x + SECTION_WIDTH + BLOCK_GAP, y, 0f, 0f,
                SECTION_WIDTH, SECTION_HEIGHT, 256, 256);

        int statsTop = y + SECTION_HEIGHT + 2;
        context.fill(x, statsTop, x + SECTION_WIDTH, statsTop + STATS_AREA_HEIGHT - 4, 0xC0101010);
        drawBotStats(context, x + 6, statsTop + 6);
        // When the expanded overlay is open, let it consume this space (don't render the small panel behind it).
        if (!topicsExpanded) {
            drawTopicPanel(context, x + SECTION_WIDTH + BLOCK_GAP, statsTop, mouseX, mouseY);
        }
    }

    private void drawBotStats(DrawContext context, int x, int y) {
        BotPlayerInventoryScreenHandler handler = this.handler;
        float health = handler.getBotHealth();
        float maxHealth = handler.getBotMaxHealth();
        int hunger = handler.getBotHunger();
        int level = handler.getBotLevel();
        float xpProgress = handler.getBotXpProgress();

        String healthLabel = String.format("Health: %.1f / %.1f", health, maxHealth);
        context.drawText(this.textRenderer, healthLabel, x, y, 0xFFEFEFEF, false);
        drawBar(context, x, y + 10, 120, 6, health / maxHealth, 0xFFB83E3E);
        String hungerLabel = "Hunger: " + hunger;
        context.drawText(this.textRenderer, hungerLabel, x, y + 20, 0xFFEFEFEF, false);
        drawBar(context, x, y + 30, 120, 6, MathHelper.clamp(hunger / 20f, 0.0f, 1.0f), 0xFFE3C05C);

        int xpAreaX = x + 130;
        int xpAreaW = Math.max(40, SECTION_WIDTH - 136);
        String xpLabel = "XP L" + level;
        context.drawText(this.textRenderer, xpLabel, xpAreaX, y, 0xFFEFEFEF, false);
        drawBar(context, xpAreaX, y + 10, xpAreaW, 6, xpProgress, 0xFF4FA3E3);
    }

    private void drawBar(DrawContext context, int x, int y, int width, int height, float value, int color) {
        int border = 0xFF000000;
        context.fill(x, y, x + width, y + height, 0xFF1A1A1A);
        context.fill(x, y, x + width, y + 1, border);
        context.fill(x, y + height - 1, x + width, y + height, border);
        context.fill(x, y, x + 1, y + height, border);
        context.fill(x + width - 1, y, x + width, y + height, border);
        int filled = Math.round((width - 2) * MathHelper.clamp(value, 0.0f, 1.0f));
        context.fill(x + 1, y + 1, x + 1 + filled, y + height - 1, color);
    }

    private void drawTopicPanel(DrawContext context, int panelX, int panelY, int mouseX, int mouseY) {
        int panelWidth = SECTION_WIDTH;
        int panelHeight = STATS_AREA_HEIGHT - 4;
        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xC0101010);

        boolean headerHover = isMouseOverTopicsHeader(mouseX, mouseY);
        int headerColor = headerHover || topicsExpanded ? 0xFFFFE08A : 0xFFE6D7A3;
        context.drawText(this.textRenderer, TOPIC_PANEL_TITLE, panelX + TOPIC_PADDING, panelY + 2, headerColor, false);
        String openLabel = "Open";
        int openX = panelX + panelWidth - TOPIC_PADDING - this.textRenderer.getWidth(openLabel);
        context.drawText(this.textRenderer, openLabel, openX, panelY + 2, headerColor, false);
        int rowX = panelX + TOPIC_PADDING;
        int rowY = panelY + 2 + this.textRenderer.fontHeight + 1;
        int rowW = panelWidth - TOPIC_PADDING * 2;
        int listHeight = panelHeight - (rowY - panelY);
        drawQuickTopicGrid(context, rowX, rowY, rowW, listHeight, mouseX, mouseY);
    }

    private record QuickGridLayout(int cols, int rows, int buttonW, int buttonH, int gap) {}

    private QuickGridLayout computeQuickGridLayout(int w, int h) {
        int cols = Math.max(1, QUICK_TOPIC_COLS);
        int gap = Math.max(0, QUICK_TOPIC_GAP);
        int maxRows = Math.max(1, (int) Math.ceil((double) QUICK_TOPIC_ENTRIES.size() / (double) cols));

        int rows = Math.min(maxRows, Math.max(1, QUICK_TOPIC_MAX_ROWS));
        int buttonW = (w - gap * (cols - 1)) / cols;
        int buttonH = (h - gap * (rows - 1)) / rows;
        if (rows > 1 && buttonH < QUICK_TOPIC_MIN_BUTTON_H) {
            rows = 1;
            buttonH = h;
        }
        return new QuickGridLayout(cols, rows, Math.max(1, buttonW), Math.max(1, buttonH), gap);
    }

    private void drawQuickTopicGrid(DrawContext context, int x, int y, int w, int h, int mouseX, int mouseY) {
        if (QUICK_TOPIC_ENTRIES == null || QUICK_TOPIC_ENTRIES.isEmpty() || w <= 0 || h <= 0) {
            return;
        }

        QuickGridLayout layout = computeQuickGridLayout(w, h);
        int cols = layout.cols;
        int rows = layout.rows;
        int bw = layout.buttonW;
        int bh = layout.buttonH;
        int gap = layout.gap;

        int maxButtons = Math.min(QUICK_TOPIC_ENTRIES.size(), cols * rows);
        for (int i = 0; i < maxButtons; i++) {
            int r = i / cols;
            int c = i % cols;
            int bx = x + c * (bw + gap);
            int by = y + r * (bh + gap);
            TopicEntry entry = QUICK_TOPIC_ENTRIES.get(i);
            boolean active = entry.action != null && isEntryActive(entry.action);
            drawQuickTopicButton(context, bx, by, bw, bh, entry, active, mouseX, mouseY);
        }
    }

    private void drawQuickTopicButton(DrawContext context, int x, int y, int w, int h, TopicEntry entry,
                                      boolean active, int mouseX, int mouseY) {
        if (entry == null) {
            return;
        }
        boolean enabled = entry.action == null || isEntryEnabled(entry.action);
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;

        int fillBase = active ? 0xFF3A2C14 : 0xFF1A1A1A;
        int fill = hover ? (active ? 0xFF4A3720 : 0xFF2A2A2A) : fillBase;
        if (!enabled) {
            fill = 0xFF151515;
        }

        int border = 0xFF000000;
        context.fill(x, y, x + w, y + h, fill);
        context.fill(x, y, x + w, y + 1, border);
        context.fill(x, y + h - 1, x + w, y + h, border);
        context.fill(x, y, x + 1, y + h, border);
        context.fill(x + w - 1, y, x + w, y + h, border);

        String label = entry.label != null ? entry.label : "";
        int maxTextW = Math.max(1, (int) ((w - 6) / QUICK_TOPIC_TEXT_SCALE));
        String drawn = elideToWidth(label, maxTextW);
        int textColor = enabled ? COLOR_TEXT_PARCHMENT : COLOR_TEXT_DISABLED;
        int textW = this.textRenderer.getWidth(drawn);
        int drawX = x + Math.max(2, (w - Math.round(textW * QUICK_TOPIC_TEXT_SCALE)) / 2);
        int drawY = y + Math.max(1, (h - Math.round(this.textRenderer.fontHeight * QUICK_TOPIC_TEXT_SCALE)) / 2);
        drawScaledText(context, drawn, drawX, drawY, textColor, false, QUICK_TOPIC_TEXT_SCALE);
    }

    private void drawScaledText(DrawContext context, String text, int x, int y, int color, boolean shadow, float scale) {
        if (text == null || text.isBlank()) {
            return;
        }
        float s = Math.max(0.1f, scale);
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.scale(s, s);
        int sx = (int) Math.floor(x / (double) s);
        int sy = (int) Math.floor(y / (double) s);
        context.drawText(this.textRenderer, text, sx, sy, color, shadow);
        matrices.popMatrix();
    }

    private Rect computeTopicsOverlayRect() {
        int desiredW = getTopicPanelWidth() + 260; // room for dialogue + topics columns
        int w = MathHelper.clamp(desiredW, TOPICS_OVERLAY_MIN_WIDTH, TOPICS_OVERLAY_MAX_WIDTH);
        w = Math.min(w, Math.max(TOPICS_OVERLAY_MIN_WIDTH, this.width - 24));
        int h = MathHelper.clamp(SECTION_HEIGHT + 14, TOPICS_OVERLAY_MIN_HEIGHT, TOPICS_OVERLAY_MAX_HEIGHT);
        h = Math.min(h, Math.max(TOPICS_OVERLAY_MIN_HEIGHT, this.height - 24));

        // Anchor to the existing Topics panel (bottom-right of the shared inventory), but align the overlay
        // so it actually covers that panel + stats area (i.e. it can "gain" that real estate).
        int anchorRight = getTopicPanelX() + getTopicPanelWidth();
        int x = anchorRight - w;
        int anchorBottom = getTopicPanelY() + getTopicPanelHeight();
        int y = anchorBottom - h;

        // Clamp within screen.
        x = MathHelper.clamp(x, 12, Math.max(12, this.width - w - 12));
        y = MathHelper.clamp(y, 12, Math.max(12, this.height - h - 12));
        return new Rect(x, y, w, h);
    }

    private OverlayColumns computeOverlayColumns(Rect r) {
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;
        int footerY = r.bottom() - footerH;

        int contentX = r.x + TOPICS_OVERLAY_PADDING;
        int contentY = r.y + headerH + 2;
        int contentW = r.w - TOPICS_OVERLAY_PADDING * 2;
        int contentH = (footerY - 2) - contentY;

        int dividerW = TOPICS_OVERLAY_COLUMN_GAP;

        // Width available for both columns, excluding the divider gap.
        int availableW = Math.max(1, contentW - dividerW);
        int minDialogueW = 160;
        int minListW = 140;

        double minRatio = MathHelper.clamp((double) minDialogueW / (double) availableW, 0.0, 1.0);
        double maxRatio = MathHelper.clamp((double) (availableW - minListW) / (double) availableW, 0.0, 1.0);
        if (maxRatio < minRatio) {
            maxRatio = minRatio;
        }
        overlaySplitRatio = MathHelper.clamp(overlaySplitRatio, minRatio, maxRatio);

        int dialogueW = (int) Math.round(availableW * overlaySplitRatio);
        dialogueW = MathHelper.clamp(dialogueW, minDialogueW, Math.max(minDialogueW, availableW - minListW));

        int dialogueX = contentX;
        int dividerX = contentX + dialogueW;
        int listX = dividerX + dividerW;
        int listW = Math.max(minListW, contentX + contentW - listX);

        Rect dividerRect = new Rect(dividerX, contentY, dividerW, Math.max(1, contentH));
        return new OverlayColumns(contentX, contentY, contentW, contentH, dialogueX, dialogueW, dividerX, dividerW, listX, listW, dividerRect);
    }

    private boolean isMouseOverTopicsHeader(double mouseX, double mouseY) {
        int panelX = getTopicPanelX();
        int panelY = getTopicPanelY();
        int panelW = getTopicPanelWidth();
        int headerH = this.textRenderer.fontHeight + 4;
        return mouseX >= panelX && mouseX < panelX + panelW
                && mouseY >= panelY && mouseY < panelY + headerH;
    }

    private boolean isMouseOverTopicsOverlay(double mouseX, double mouseY) {
        if (!topicsExpanded) return false;
        return computeTopicsOverlayRect().contains(mouseX, mouseY);
    }

    private int getOverlayRowsStartY(int listY) {
        return listY + TOPICS_OVERLAY_LIST_HEADER_H + TOPICS_OVERLAY_SEARCH_H + TOPICS_OVERLAY_SEARCH_GAP;
    }

    private int getOverlayRowsHeight(int listH) {
        return Math.max(1, listH - TOPICS_OVERLAY_LIST_HEADER_H - TOPICS_OVERLAY_SEARCH_H - TOPICS_OVERLAY_SEARCH_GAP);
    }

    private Rect computeOverlaySearchRect(int listX, int listY, int listW) {
        int searchX = listX + 4;
        int searchY = listY + TOPICS_OVERLAY_LIST_HEADER_H + 1;
        int searchW = Math.max(40, listW - 8);
        return new Rect(searchX, searchY, searchW, TOPICS_OVERLAY_SEARCH_H);
    }

    private int getOverlayListContentWidth(int listW) {
        return Math.max(60, listW - TOPICS_OVERLAY_SCROLLBAR_W - 2);
    }

    private Rect computeOverlayListScrollbarTrack(int listX, int listStartY, int listW, int rowsHeight) {
        int rowW = getOverlayListContentWidth(listW);
        int trackX = listX + rowW + 1;
        return new Rect(trackX, listStartY, TOPICS_OVERLAY_SCROLLBAR_W, Math.max(1, rowsHeight));
    }

    private Rect computeOverlayListScrollbarThumb(Rect track, int totalRows, int visibleRows) {
        if (track == null || totalRows <= visibleRows || visibleRows <= 0) {
            return null;
        }
        int maxScroll = Math.max(1, totalRows - visibleRows);
        int thumbH = Math.max(TOPICS_OVERLAY_SCROLLBAR_MIN_THUMB_H, (track.h * visibleRows) / Math.max(1, totalRows));
        thumbH = Math.min(track.h, thumbH);
        int range = Math.max(0, track.h - thumbH);
        int scroll = MathHelper.clamp(getOverlayScrollIndex(), 0, maxScroll);
        int thumbY = track.y + (range <= 0 ? 0 : (int) Math.round((double) range * ((double) scroll / (double) maxScroll)));
        return new Rect(track.x, thumbY, track.w, thumbH);
    }

    private void updateOverlayScrollFromThumb(net.minecraft.client.gui.Click click, Rect track, Rect thumb, int totalRows, int visibleRows) {
        if (click == null || track == null || thumb == null || totalRows <= visibleRows || visibleRows <= 0) {
            return;
        }
        int maxScroll = Math.max(0, totalRows - visibleRows);
        if (maxScroll == 0) {
            setOverlayScrollIndex(0);
            return;
        }
        int minY = track.y;
        int maxY = track.bottom() - thumb.h;
        if (maxY <= minY) {
            setOverlayScrollIndex(0);
            return;
        }
        int desiredY = (int) click.y() - overlayListScrollGrabOffsetY;
        desiredY = MathHelper.clamp(desiredY, minY, maxY);
        double ratio = (double) (desiredY - minY) / (double) (maxY - minY);
        int nextScroll = (int) Math.round(ratio * maxScroll);
        setOverlayScrollIndex(MathHelper.clamp(nextScroll, 0, maxScroll));
    }

    private void drawTopicsOverlay(DrawContext context, int mouseX, int mouseY) {
        Rect r = computeTopicsOverlayRect();
        int border = 0xFF000000;
        // Opaque fill so underlying inventory/stats don't visually compete with the overlay.
        int fill = 0xFF101010;

        // Panel background + border.
        context.fill(r.x, r.y, r.right(), r.bottom(), fill);
        context.fill(r.x, r.y, r.right(), r.y + 1, border);
        context.fill(r.x, r.bottom() - 1, r.right(), r.bottom(), border);
        context.fill(r.x, r.y, r.x + 1, r.bottom(), border);
        context.fill(r.right() - 1, r.y, r.right(), r.bottom(), border);

        // Header.
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        context.fill(r.x + 1, r.y + 1, r.right() - 1, r.y + headerH, 0xFF161616);
        context.drawText(this.textRenderer, "Conversation", r.x + TOPICS_OVERLAY_PADDING, r.y + TOPICS_OVERLAY_HEADER_PAD + 1, 0xFFFFE08A, false);

        // Close box (top-right).
        String closeLabel = "X";
        int closeSize = 12;
        int closeX = r.right() - TOPICS_OVERLAY_PADDING - closeSize;
        int closeY = r.y + (headerH - closeSize) / 2;
        boolean closeHover = mouseX >= closeX && mouseX < closeX + closeSize && mouseY >= closeY && mouseY < closeY + closeSize;
        context.fill(closeX, closeY, closeX + closeSize, closeY + closeSize, closeHover ? 0xFF2A2A2A : 0xFF1A1A1A);
        int closeTextX = closeX + (closeSize - this.textRenderer.getWidth(closeLabel)) / 2;
        context.drawText(this.textRenderer, closeLabel, closeTextX, closeY + 2, 0xFFEFEFEF, false);

        // Footer hint.
        String hint = "Scroll or drag scrollbar; click a topic; Esc closes";
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;
        int footerY = r.bottom() - footerH;
        context.fill(r.x + 1, footerY, r.right() - 1, r.bottom() - 1, 0xFF161616);
        context.drawText(this.textRenderer, hint, r.x + TOPICS_OVERLAY_PADDING, footerY + TOPICS_OVERLAY_FOOTER_PAD, 0xFFB0B0B0, false);

        // Content region (two columns).
        OverlayColumns cols = computeOverlayColumns(r);

        // Dialogue column.
        drawDialogueColumn(context, cols.dialogueX, cols.contentY, cols.dialogueW, cols.contentH);

        // Divider (draggable).
        boolean dividerHover = cols.dividerRect.contains(mouseX, mouseY);
        int dividerFill = dividerHover || overlayDraggingSplit ? 0xFF141414 : 0xFF101010;
        context.fill(cols.dividerX, cols.contentY, cols.dividerX + cols.dividerW, cols.contentY + cols.contentH, dividerFill);
        int dividerLineX = cols.dividerX + cols.dividerW / 2;
        int dividerLineColor = dividerHover || overlayDraggingSplit ? 0xFFB08C40 : 0xFF303030;
        context.fill(dividerLineX, cols.contentY + 2, dividerLineX + 1, cols.contentY + cols.contentH - 2, dividerLineColor);

        // Topics list region.
        int listX = cols.listX;
        int listY = cols.contentY;
        int listW = cols.listW;
        int listH = cols.contentH;

        List<TopicEntry> entries = getFilteredOverlayEntries(getOverlayEntries());
        int rowsHeight = getOverlayRowsHeight(listH);
        int visibleRows = Math.max(1, rowsHeight / TOPIC_ROW_HEIGHT);
        clampOverlayScroll(visibleRows);
        int listContentW = getOverlayListContentWidth(listW);
        int listStartY = getOverlayRowsStartY(listY);
        Rect scrollTrack = computeOverlayListScrollbarTrack(listX, listStartY, listW, rowsHeight);
        Rect scrollThumb = computeOverlayListScrollbarThumb(scrollTrack, entries.size(), visibleRows);

        // Tabs (Skills / Dialogue / Admin)
        int tabY = listY + 2;
        int tabH = TOPICS_OVERLAY_LIST_HEADER_H - 4;
        int tabW = Math.max(44, (listW - 8) / 3);
        int tabGap = 2;
        int skillsTabX = listX + 2;
        int dialogueTabX = skillsTabX + tabW + tabGap;
        int adminTabX = dialogueTabX + tabW + tabGap;
        drawOverlayTab(context, skillsTabX, tabY, tabW, tabH, TOPIC_PANEL_TITLE, overlayCategory == TopicCategory.SKILL, true);
        drawOverlayTab(context, dialogueTabX, tabY, tabW, tabH, "Dialogue", overlayCategory == TopicCategory.DIALOGUE, true);
        drawOverlayTab(context, adminTabX, tabY, tabW, tabH, "Admin", overlayCategory == TopicCategory.ADMIN, isAdminTabEnabled());

        Rect searchRect = computeOverlaySearchRect(listX, listY, listW);
        drawOverlaySearchBox(context, searchRect, mouseX, mouseY);

        // Clip to list area.
        context.enableScissor(listX, listStartY, listX + listContentW, listStartY + rowsHeight);
        for (int i = 0; i < visibleRows; i++) {
            int entryIndex = getOverlayScrollIndex() + i;
            if (entryIndex >= entries.size()) break;
            TopicEntry entry = entries.get(entryIndex);
            int rowY = listStartY + i * TOPIC_ROW_HEIGHT;
            if (overlayCategory == TopicCategory.SKILL && entry.action == TopicAction.FOLLOW) {
                drawFollowRow(context, listX, rowY, listContentW, mouseX, mouseY);
            } else if (overlayCategory == TopicCategory.SKILL && entry.action == TopicAction.SKILL_WOODCUT) {
                drawWoodcutRow(context, listX, rowY, listContentW, mouseX, mouseY);
            } else if (overlayCategory == TopicCategory.SKILL && isAdjustableSkillAction(entry.action)) {
                drawAdjustableSkillRow(context, listX, rowY, listContentW, entry, mouseX, mouseY);
            } else {
                boolean active = entry.action != null && isEntryActive(entry.action);
                drawTopicRow(context, listX, rowY, listContentW, entry, active, mouseX, mouseY);
            }
        }
        context.disableScissor();

        if (scrollTrack != null) {
            context.fill(scrollTrack.x, scrollTrack.y, scrollTrack.right(), scrollTrack.bottom(), 0xFF161616);
            context.fill(scrollTrack.x, scrollTrack.y, scrollTrack.right(), scrollTrack.y + 1, 0xFF2C2C2C);
            context.fill(scrollTrack.x, scrollTrack.bottom() - 1, scrollTrack.right(), scrollTrack.bottom(), 0xFF2C2C2C);
            context.fill(scrollTrack.x, scrollTrack.y, scrollTrack.x + 1, scrollTrack.bottom(), 0xFF2C2C2C);
            context.fill(scrollTrack.right() - 1, scrollTrack.y, scrollTrack.right(), scrollTrack.bottom(), 0xFF2C2C2C);
        }
        if (scrollThumb != null) {
            boolean thumbHover = scrollThumb.contains(mouseX, mouseY);
            int thumbFill = (thumbHover || overlayDraggingListScroll) ? 0xFFB08C40 : 0xFF7A6240;
            context.fill(scrollThumb.x + 1, scrollThumb.y + 1, scrollThumb.right() - 1, scrollThumb.bottom() - 1, thumbFill);
        }

        // Delayed hover tooltip (after scissor, so it can draw over the list cleanly).
        drawOverlayHoverTooltip(context, mouseX, mouseY);
    }

    private void drawOverlaySearchBox(DrawContext context, Rect searchRect, int mouseX, int mouseY) {
        if (searchRect == null) {
            return;
        }
        boolean hover = searchRect.contains(mouseX, mouseY);
        int fill = topicSearchFocused ? 0xFF262015 : (hover ? 0xFF1F1F1F : 0xFF171717);
        int border = topicSearchFocused ? 0xFFB08C40 : 0xFF2A2A2A;
        context.fill(searchRect.x, searchRect.y, searchRect.right(), searchRect.bottom(), fill);
        context.fill(searchRect.x, searchRect.y, searchRect.right(), searchRect.y + 1, border);
        context.fill(searchRect.x, searchRect.bottom() - 1, searchRect.right(), searchRect.bottom(), border);
        context.fill(searchRect.x, searchRect.y, searchRect.x + 1, searchRect.bottom(), border);
        context.fill(searchRect.right() - 1, searchRect.y, searchRect.right(), searchRect.bottom(), border);

        String query = topicSearchQuery != null ? topicSearchQuery : "";
        boolean showCursor = topicSearchFocused && ((System.currentTimeMillis() / 500L) % 2L == 0L);
        String shown = query.isBlank() ? "Search topics..." : query;
        if (showCursor) {
            shown = shown + "|";
        }
        shown = elideToWidth(shown, Math.max(1, searchRect.w - 8));
        int textColor = query.isBlank() ? COLOR_TEXT_SUBTLE : COLOR_TEXT_PARCHMENT;
        int textY = searchRect.y + Math.max(1, (searchRect.h - this.textRenderer.fontHeight) / 2);
        context.drawText(this.textRenderer, shown, searchRect.x + 4, textY, textColor, false);
    }

    private void drawOverlayTab(DrawContext context, int x, int y, int w, int h, String label, boolean active, boolean enabled) {
        int fill;
        if (!enabled) {
            fill = 0xFF141414;
        } else {
            fill = active ? 0xFF3A2C14 : 0xFF1A1A1A;
        }
        int border = 0xFF000000;

        context.fill(x, y, x + w, y + h, fill);
        context.fill(x, y, x + w, y + 1, border);
        context.fill(x, y + h - 1, x + w, y + h, border);
        context.fill(x, y, x + 1, y + h, border);
        context.fill(x + w - 1, y, x + w, y + h, border);

        int color;
        if (!enabled) {
            color = 0xFF6F6F6F;
        } else {
            color = active ? 0xFFFFE08A : 0xFFE6D7A3;
        }
        int textX = x + (w - this.textRenderer.getWidth(label)) / 2;
        context.drawText(this.textRenderer, label, textX, y + 2, color, false);
    }

    private boolean isAdminUser() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return false;
        }

        // Best-effort: some mappings expose a permission-level helper on the client player.
        // Avoid hard-linking to a method that may not exist in all environments.
        try {
            java.lang.reflect.Method m = client.player.getClass().getMethod("hasPermissionLevel", int.class);
            Object r = m.invoke(client.player, 4);
            if (r instanceof Boolean b) {
                return b;
            }
        } catch (Throwable ignored) {
            // Fall through.
        }

        // Fallback: keep the UI available; server will validate operator status.
        return true;
    }

    private void drawDialogueColumn(DrawContext context, int x, int y, int w, int h) {
        // Updated each frame while the overlay is open; used for click hit-testing.
        dialogueResponseHitboxes.clear();

        // Background.
        context.fill(x, y, x + w, y + h, 0x55101010);
        context.fill(x, y, x + w, y + 1, 0xFF000000);
        context.fill(x, y + h - 1, x + w, y + h, 0xFF000000);
        context.fill(x, y, x + 1, y + h, 0xFF000000);
        context.fill(x + w - 1, y, x + w, y + h, 0xFF000000);

        // Topic header (Morrowind-style).
        String topicHeader = (lastDialogueTopicLabel != null && !lastDialogueTopicLabel.isBlank())
            ? lastDialogueTopicLabel
            : "Select a topic";
        context.drawText(this.textRenderer, topicHeader, x + 4, y + 2, COLOR_TEXT_TOPIC, false);

        int textX = x + 4;
        int textY = y + 2 + this.textRenderer.fontHeight + 4;
        int textW = Math.max(40, w - 8);

        // Player response prompts (in red), pinned to the bottom of the dialogue column.
        // Only show responses once a dialogue topic is active; selecting a topic itself is not a player line.
        boolean topicActive = lastDialogueTopicKey != null
            && !lastDialogueTopicKey.isBlank()
            && !lastDialogueTopicKey.trim().equalsIgnoreCase("goodbye");
        java.util.List<TopicEntry> responses = (overlayCategory == TopicCategory.DIALOGUE && topicActive)
            ? getDialogueResponseEntries(6)
            : java.util.List.of();
        int respLineH = this.textRenderer.fontHeight;
        int respCount = responses.size();
        int respPad = 4;
        int respAreaH = respCount > 0 ? (respCount * respLineH + respPad) : 0;
        int respStartY = y + h - respAreaH;

        // Leave room for the response area.
        int textH = Math.max(20, (respAreaH > 0 ? (respStartY - 2) : (y + h)) - textY);

        java.util.List<String> lines = net.wcfcarolina13.FrensClient.getDialogueLines(botAlias, 18);
        java.util.List<StyledLine> wrapped = new java.util.ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            int color = getDialogueLineColor(line);
            String display = formatDialogueLineForDisplay(line);
            if (display == null || display.isBlank()) continue;
            var segments = this.textRenderer.wrapLines(net.minecraft.text.Text.literal(display), textW);
            for (var seg : segments) {
                wrapped.add(new StyledLine(seg, color));
            }
        }

        int maxLines = Math.max(1, textH / this.textRenderer.fontHeight);
        int start = Math.max(0, wrapped.size() - maxLines);
        int rowY = textY;
        for (int i = start; i < wrapped.size(); i++) {
            StyledLine line = wrapped.get(i);
            context.drawText(this.textRenderer, line.text(), textX, rowY, line.color(), false);
            rowY += this.textRenderer.fontHeight;
            if (rowY >= textY + textH) break;
        }

        // Draw response prompts at the bottom (red) and record hitboxes.
        if (respCount > 0) {
            // subtle divider
            int divY = respStartY - 2;
            if (divY > y + 2) {
                context.fill(x + 2, divY, x + w - 2, divY + 1, 0xFF222222);
            }

            int ry = respStartY + 2;
            for (TopicEntry e : responses) {
                if (e == null) continue;
                String prompt = getDialoguePrompt(e.dialogueKey, e.label);
                String text = prompt != null ? prompt : (e.label != null ? e.label : "");
                text = elideToWidth(text, textW);
                context.drawText(this.textRenderer, text, textX, ry, COLOR_TEXT_RESPONSE, false);
                int tw = this.textRenderer.getWidth(text);
                dialogueResponseHitboxes.add(new DialogueResponseHitbox(new Rect(textX, ry, Math.max(1, tw), respLineH), e));
                ry += respLineH;
            }
        }
    }

    private record StyledLine(net.minecraft.text.OrderedText text, int color) {}

    private int getDialogueLineColor(String line) {
        if (line == null) {
            return COLOR_TEXT_PARCHMENT;
        }
        String s = line.trim();
        if (s.isEmpty()) {
            return COLOR_TEXT_PARCHMENT;
        }
        String botPrefix = (botAlias != null && !botAlias.isBlank()) ? (botAlias + ":") : null;
        if (s.startsWith("You:")) {
            return COLOR_TEXT_RESPONSE;
        }
        if (s.startsWith("You (")) {
            return COLOR_TEXT_SYSTEM;
        }
        if (s.startsWith("Admin:")) {
            return COLOR_TEXT_SYSTEM;
        }
        if (botPrefix != null && s.startsWith(botPrefix)) {
            return COLOR_TEXT_PARCHMENT;
        }
        if (s.startsWith("...")) {
            return COLOR_TEXT_SUBTLE;
        }
        return COLOR_TEXT_PARCHMENT;
    }

    private void toggleTopicsExpanded(boolean open) {
        this.topicsExpanded = open;
        if (!open) {
            overlayDraggingSplit = false;
            overlayDraggingListScroll = false;
        }
        if (!open) {
            topicSearchFocused = false;
            companionQuestStateRequested = false;
            adminResetConfirmArmed = false;
            adminResetConfirmArmedAtMs = 0L;
            return;
        }

        if (!companionQuestStateRequested) {
            requestCompanionQuestState();
        }

        // Ensure the scroll is valid for the (larger) overlay view.
        Rect r = computeTopicsOverlayRect();
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;
        int contentY = r.y + headerH + 2;
        int footerY = r.bottom() - footerH;
        int contentH = (footerY - 2) - contentY;
        int listH = Math.max(1, contentH);
        int visibleRows = Math.max(1, getOverlayRowsHeight(listH) / TOPIC_ROW_HEIGHT);
        clampOverlayScroll(visibleRows);
    }

    private boolean clickTopicsOverlay(net.minecraft.client.gui.Click click) {
        double mouseX = click.x();
        double mouseY = click.y();
        Rect r = computeTopicsOverlayRect();
        if (!r.contains(mouseX, mouseY)) {
            toggleTopicsExpanded(false);
            return true;
        }

        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int closeSize = 12;
        int closeX = r.right() - TOPICS_OVERLAY_PADDING - closeSize;
        int closeY = r.y + (headerH - closeSize) / 2;
        if (mouseX >= closeX && mouseX < closeX + closeSize && mouseY >= closeY && mouseY < closeY + closeSize) {
            toggleTopicsExpanded(false);
            return true;
        }

        OverlayColumns cols = computeOverlayColumns(r);

        // Divider drag start.
        if (click.button() == 0 && cols.dividerRect.contains(mouseX, mouseY)) {
            overlayDraggingSplit = true;
            updateOverlaySplitFromMouse(mouseX);
            return true;
        }

        // Tabs (Skills / Dialogue / Admin).
        int listX = cols.listX;
        int listY = cols.contentY;
        int listW = cols.listW;

        int tabY = listY + 2;
        int tabH = TOPICS_OVERLAY_LIST_HEADER_H - 4;
        int tabW = Math.max(44, (listW - 8) / 3);
        int tabGap = 2;
        int skillsTabX = listX + 2;
        int dialogueTabX = skillsTabX + tabW + tabGap;
        int adminTabX = dialogueTabX + tabW + tabGap;

        if (mouseY >= tabY && mouseY < tabY + tabH) {
            if (mouseX >= skillsTabX && mouseX < skillsTabX + tabW) {
                overlayCategory = TopicCategory.SKILL;
                return true;
            }
            if (mouseX >= dialogueTabX && mouseX < dialogueTabX + tabW) {
                overlayCategory = TopicCategory.DIALOGUE;
                if (!companionQuestStateRequested) {
                    requestCompanionQuestState();
                }
                return true;
            }
            if (mouseX >= adminTabX && mouseX < adminTabX + tabW) {
                if (isAdminTabEnabled()) {
                    overlayCategory = TopicCategory.ADMIN;
                    return true;
                }
                return false;
            }
        }

        Rect searchRect = computeOverlaySearchRect(listX, listY, listW);
        if (searchRect.contains(mouseX, mouseY)) {
            topicSearchFocused = true;
            return true;
        }
        topicSearchFocused = false;

        int rowsHeight = getOverlayRowsHeight(cols.contentH);
        int visibleRows = Math.max(1, rowsHeight / TOPIC_ROW_HEIGHT);
        List<TopicEntry> entries = getFilteredOverlayEntries(getOverlayEntries());
        Rect scrollTrack = computeOverlayListScrollbarTrack(listX, getOverlayRowsStartY(listY), listW, rowsHeight);
        Rect scrollThumb = computeOverlayListScrollbarThumb(scrollTrack, entries.size(), visibleRows);
        if (scrollTrack != null && scrollTrack.contains(mouseX, mouseY) && click.button() == 0) {
            if (scrollThumb != null && scrollThumb.contains(mouseX, mouseY)) {
                overlayDraggingListScroll = true;
                overlayListScrollGrabOffsetY = (int) mouseY - scrollThumb.y;
                return true;
            }
            if (scrollThumb != null) {
                int page = Math.max(1, visibleRows - 1);
                int delta = mouseY < scrollThumb.y ? -page : page;
                setOverlayScrollIndex(MathHelper.clamp(getOverlayScrollIndex() + delta, 0, Math.max(0, entries.size() - visibleRows)));
                return true;
            }
            return true;
        }

        // Follow +/- controls.
        if (overlayCategory == TopicCategory.SKILL) {
            int adjust = getFollowAdjustDirectionInOverlay(mouseX, mouseY);
            if (adjust != 0) {
                adjustFollowDistance(adjust);
                return true;
            }
            int woodcutAdjust = getWoodcutAdjustDirectionInOverlay(mouseX, mouseY);
            if (woodcutAdjust != 0) {
                adjustWoodcutTreeCount(woodcutAdjust);
                return true;
            }
            SkillAdjustHit skillAdjust = getAdjustableSkillHitInOverlay(mouseX, mouseY);
            if (skillAdjust != null) {
                applySkillAdjust(skillAdjust);
                return true;
            }
        }

        // Dialogue response prompts (left column). Only active when the Dialogue tab is selected.
        if (overlayCategory == TopicCategory.DIALOGUE) {
            TopicEntry response = getDialogueResponseEntryAtOverlay(mouseX, mouseY);
            if (response != null) {
                // Red responses are explicit player dialogue lines.
                handleDialogueTopicEntry(response, true);
                return true;
            }
        }

        TopicEntry entry = getTopicEntryAtOverlay(mouseX, mouseY);
        if (entry != null) {
            boolean enabled;
            if (entry.category == TopicCategory.DIALOGUE) {
                enabled = isDialogueEntryEnabled(entry);
            } else if (entry.category == TopicCategory.ADMIN) {
                enabled = isAdminEntryEnabled(entry);
            } else {
                enabled = (entry.action == null || isEntryEnabled(entry.action));
            }
            if (enabled) {
                handleTopicEntry(entry);
                return true;
            }
            return false;
        }
        return true;
    }

    private void updateOverlaySplitFromMouse(double mouseX) {
        Rect r = computeTopicsOverlayRect();
        OverlayColumns cols = computeOverlayColumns(r);

        int dividerW = cols.dividerW;
        int availableW = Math.max(1, cols.contentW - dividerW);
        int minDialogueW = 160;
        int minListW = 140;
        int minDialogue = Math.min(minDialogueW, availableW);
        int maxDialogue = Math.max(minDialogue, availableW - minListW);

        double desiredDialogue = mouseX - cols.contentX - (dividerW / 2.0);
        int dialogueW = (int) Math.round(desiredDialogue);
        dialogueW = MathHelper.clamp(dialogueW, minDialogue, maxDialogue);
        overlaySplitRatio = MathHelper.clamp((double) dialogueW / (double) availableW, 0.0, 1.0);
    }

    private TopicEntry getTopicEntryAtOverlay(double mouseX, double mouseY) {
        Rect r = computeTopicsOverlayRect();
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;

        int footerY = r.bottom() - footerH;
        int contentY = r.y + headerH + 2;
        int contentH = (footerY - 2) - contentY;

        OverlayColumns cols = computeOverlayColumns(r);
        int listX = cols.listX;
        int listY = getOverlayRowsStartY(cols.contentY);
        int listW = getOverlayListContentWidth(cols.listW);
        int listH = getOverlayRowsHeight(contentH);
        int visibleRows = Math.max(1, listH / TOPIC_ROW_HEIGHT);
        clampOverlayScroll(visibleRows);
        List<TopicEntry> entries = getFilteredOverlayEntries(getOverlayEntries());

        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH) {
            return null;
        }
        int rowIndex = (int) ((mouseY - listY) / TOPIC_ROW_HEIGHT);
        if (rowIndex < 0 || rowIndex >= visibleRows) {
            return null;
        }
        int entryIndex = getOverlayScrollIndex() + rowIndex;
        if (entryIndex < 0 || entryIndex >= entries.size()) {
            return null;
        }
        return entries.get(entryIndex);
    }

    private enum SkillAdjustControl {
        INCREMENT,
        DECREMENT,
        TOGGLE_MODE
    }

    private record SkillAdjustHit(TopicAction action, SkillAdjustControl control) {}

    private int getFollowAdjustDirectionInOverlay(double mouseX, double mouseY) {
        if (overlayCategory != TopicCategory.SKILL) {
            return 0;
        }
        Rect r = computeTopicsOverlayRect();
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;

        int footerY = r.bottom() - footerH;
        int contentY = r.y + headerH + 2;
        int contentH = (footerY - 2) - contentY;

        OverlayColumns cols = computeOverlayColumns(r);
        int listX = cols.listX;
        int listY = getOverlayRowsStartY(cols.contentY);
        int listW = getOverlayListContentWidth(cols.listW);
        int listH = getOverlayRowsHeight(contentH);
        int visibleRows = Math.max(1, listH / TOPIC_ROW_HEIGHT);
        clampOverlayScroll(visibleRows);

        List<TopicEntry> entries = getFilteredOverlayEntries(SKILL_TOPIC_ENTRIES);
        int followIndex = getSkillEntryIndex(entries, TopicAction.FOLLOW);
        int visibleStart = skillScrollIndex;
        int visibleEnd = skillScrollIndex + visibleRows;
        if (followIndex < visibleStart || followIndex >= visibleEnd) {
            return 0;
        }

        int rowY = listY + (followIndex - visibleStart) * TOPIC_ROW_HEIGHT;
        int controlSize = TOPIC_ROW_HEIGHT - 2;
        int controlY = rowY + 1;
        int plusX = listX + listW - controlSize;
        int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;

        if (mouseY >= controlY && mouseY < controlY + controlSize) {
            if (mouseX >= plusX && mouseX < plusX + controlSize) {
                return 1;
            }
            if (mouseX >= minusX && mouseX < minusX + controlSize) {
                return -1;
            }
        }
        return 0;
    }

    private int getWoodcutAdjustDirectionInOverlay(double mouseX, double mouseY) {
        if (overlayCategory != TopicCategory.SKILL) {
            return 0;
        }
        Rect r = computeTopicsOverlayRect();
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;

        int footerY = r.bottom() - footerH;
        int contentY = r.y + headerH + 2;
        int contentH = (footerY - 2) - contentY;

        OverlayColumns cols = computeOverlayColumns(r);
        int listX = cols.listX;
        int listY = getOverlayRowsStartY(cols.contentY);
        int listW = getOverlayListContentWidth(cols.listW);
        int listH = getOverlayRowsHeight(contentH);
        int visibleRows = Math.max(1, listH / TOPIC_ROW_HEIGHT);
        clampOverlayScroll(visibleRows);

        List<TopicEntry> entries = getFilteredOverlayEntries(SKILL_TOPIC_ENTRIES);
        int woodcutIndex = getSkillEntryIndex(entries, TopicAction.SKILL_WOODCUT);
        if (woodcutIndex < 0) {
            return 0;
        }
        int visibleStart = skillScrollIndex;
        int visibleEnd = skillScrollIndex + visibleRows;
        if (woodcutIndex < visibleStart || woodcutIndex >= visibleEnd) {
            return 0;
        }

        int rowY = listY + (woodcutIndex - visibleStart) * TOPIC_ROW_HEIGHT;
        int controlSize = TOPIC_ROW_HEIGHT - 2;
        int controlY = rowY + 1;
        int plusX = listX + listW - controlSize;
        int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;

        if (mouseY >= controlY && mouseY < controlY + controlSize) {
            if (mouseX >= plusX && mouseX < plusX + controlSize) {
                return 1;
            }
            if (mouseX >= minusX && mouseX < minusX + controlSize) {
                return -1;
            }
        }
        return 0;
    }

    private SkillAdjustHit getAdjustableSkillHitInOverlay(double mouseX, double mouseY) {
        if (overlayCategory != TopicCategory.SKILL) {
            return null;
        }
        Rect r = computeTopicsOverlayRect();
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;
        int footerY = r.bottom() - footerH;
        int contentY = r.y + headerH + 2;
        int contentH = (footerY - 2) - contentY;

        OverlayColumns cols = computeOverlayColumns(r);
        int listX = cols.listX;
        int listY = getOverlayRowsStartY(cols.contentY);
        int listW = getOverlayListContentWidth(cols.listW);
        int listH = getOverlayRowsHeight(contentH);
        int visibleRows = Math.max(1, listH / TOPIC_ROW_HEIGHT);
        clampOverlayScroll(visibleRows);

        List<TopicEntry> entries = getFilteredOverlayEntries(SKILL_TOPIC_ENTRIES);
        int visibleStart = skillScrollIndex;
        int visibleEnd = skillScrollIndex + visibleRows;
        int controlSize = TOPIC_ROW_HEIGHT - 2;

        for (TopicAction action : getAdjustableSkillActions()) {
            int entryIndex = getSkillEntryIndex(entries, action);
            if (entryIndex < visibleStart || entryIndex >= visibleEnd) {
                continue;
            }
            int rowY = listY + (entryIndex - visibleStart) * TOPIC_ROW_HEIGHT;
            int controlY = rowY + 1;
            int plusX = listX + listW - controlSize;
            int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;
            if (mouseY < controlY || mouseY >= controlY + controlSize) {
                continue;
            }
            if (mouseX >= plusX && mouseX < plusX + controlSize) {
                return new SkillAdjustHit(action, SkillAdjustControl.INCREMENT);
            }
            if (mouseX >= minusX && mouseX < minusX + controlSize) {
                return new SkillAdjustHit(action, SkillAdjustControl.DECREMENT);
            }
            if (action == TopicAction.SKILL_ASCENT) {
                int modeX = minusX - TOPIC_CONTROL_GAP - controlSize;
                if (mouseX >= modeX && mouseX < modeX + controlSize) {
                    return new SkillAdjustHit(action, SkillAdjustControl.TOGGLE_MODE);
                }
            }
        }
        return null;
    }

    private void drawFollowRow(DrawContext context, int rowX, int rowY, int rowW, int mouseX, int mouseY) {
        boolean active = isFollowActive();
        boolean hover = mouseX >= rowX && mouseX < rowX + rowW
                && mouseY >= rowY && mouseY < rowY + TOPIC_ROW_HEIGHT;
        int baseRow = active ? 0xFF3A2C14 : 0xFF1A1A1A;
        int rowColor = hover ? (active ? 0xFF4A3720 : 0xFF2A2A2A) : baseRow;
        context.fill(rowX, rowY, rowX + rowW, rowY + TOPIC_ROW_HEIGHT, rowColor);

        int controlSize = TOPIC_ROW_HEIGHT - 2;
        int controlY = rowY + 1;
        int plusX = rowX + rowW - controlSize;
        int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;

        String status = active ? "ON" : "OFF";
        int statusX = minusX - TOPIC_CONTROL_GAP - this.textRenderer.getWidth(status);
        String distanceLabel = formatFollowDistance();
        int distX = statusX - TOPIC_CONTROL_GAP - this.textRenderer.getWidth(distanceLabel);
        int labelX = rowX + 4;

        if (distX < labelX + 40) {
            distX = labelX + 40;
        }

        int textY = rowY + Math.max(1, (TOPIC_ROW_HEIGHT - this.textRenderer.fontHeight) / 2);
        context.drawText(this.textRenderer, "Follow", labelX, textY, COLOR_TEXT_PARCHMENT, false);
        context.drawText(this.textRenderer, distanceLabel, distX, textY, 0xFFE6D7A3, false);
        context.drawText(this.textRenderer, status, statusX, textY, active ? 0xFFE6D7A3 : 0xFFB0B0B0, false);

        drawControlBox(context, minusX, controlY, controlSize, "-", mouseX, mouseY);
        drawControlBox(context, plusX, controlY, controlSize, "+", mouseX, mouseY);
    }

    private void drawWoodcutRow(DrawContext context, int rowX, int rowY, int rowW, int mouseX, int mouseY) {
        boolean hover = mouseX >= rowX && mouseX < rowX + rowW
                && mouseY >= rowY && mouseY < rowY + TOPIC_ROW_HEIGHT;
        int rowColor = hover ? 0xFF2A2A2A : 0xFF1A1A1A;
        context.fill(rowX, rowY, rowX + rowW, rowY + TOPIC_ROW_HEIGHT, rowColor);

        int controlSize = TOPIC_ROW_HEIGHT - 2;
        int controlY = rowY + 1;
        int plusX = rowX + rowW - controlSize;
        int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;

        String countLabel = "Trees " + woodcutTreeCount;
        int countX = minusX - TOPIC_CONTROL_GAP - this.textRenderer.getWidth(countLabel);
        int labelX = rowX + 4;
        if (countX < labelX + 44) {
            countX = labelX + 44;
        }

        int textY = rowY + Math.max(1, (TOPIC_ROW_HEIGHT - this.textRenderer.fontHeight) / 2);
        context.drawText(this.textRenderer, "Woodcut", labelX, textY, COLOR_TEXT_PARCHMENT, false);
        context.drawText(this.textRenderer, countLabel, countX, textY, 0xFFE6D7A3, false);

        drawControlBox(context, minusX, controlY, controlSize, "-", mouseX, mouseY);
        drawControlBox(context, plusX, controlY, controlSize, "+", mouseX, mouseY);
    }

    private boolean isAdjustableSkillAction(TopicAction action) {
        if (action == null) {
            return false;
        }
        return action == TopicAction.SKILL_FISH
                || action == TopicAction.SKILL_WOOL
                || action == TopicAction.SKILL_STRIPMINE
                || action == TopicAction.SKILL_ASCENT
                || action == TopicAction.SKILL_DESCENT;
    }

    private TopicAction[] getAdjustableSkillActions() {
        return new TopicAction[] {
                TopicAction.SKILL_FISH,
                TopicAction.SKILL_WOOL,
                TopicAction.SKILL_STRIPMINE,
                TopicAction.SKILL_ASCENT,
                TopicAction.SKILL_DESCENT
        };
    }

    private int getSkillEntryIndex(List<TopicEntry> entries, TopicAction action) {
        if (entries == null || action == null) {
            return -1;
        }
        for (int i = 0; i < entries.size(); i++) {
            TopicEntry entry = entries.get(i);
            if (entry != null && entry.action == action) {
                return i;
            }
        }
        return -1;
    }

    private void drawAdjustableSkillRow(DrawContext context, int rowX, int rowY, int rowW, TopicEntry entry, int mouseX, int mouseY) {
        if (entry == null || entry.action == null) {
            return;
        }
        boolean hover = mouseX >= rowX && mouseX < rowX + rowW
                && mouseY >= rowY && mouseY < rowY + TOPIC_ROW_HEIGHT;
        int rowColor = hover ? 0xFF2A2A2A : 0xFF1A1A1A;
        context.fill(rowX, rowY, rowX + rowW, rowY + TOPIC_ROW_HEIGHT, rowColor);

        int controlSize = TOPIC_ROW_HEIGHT - 2;
        int controlY = rowY + 1;
        int plusX = rowX + rowW - controlSize;
        int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;

        String label = entry.label;
        String valueLabel = getAdjustableSkillValueLabel(entry.action);
        int valueX = minusX - TOPIC_CONTROL_GAP - this.textRenderer.getWidth(valueLabel);
        if (entry.action == TopicAction.SKILL_ASCENT) {
            int modeX = minusX - TOPIC_CONTROL_GAP - controlSize;
            valueX = modeX - TOPIC_CONTROL_GAP - this.textRenderer.getWidth(valueLabel);
            drawControlBox(context, modeX, controlY, controlSize, "S", mouseX, mouseY, ascentSurfaceMode);
        }

        int labelX = rowX + 4 + entry.indent * 8;
        if (valueX < labelX + 42) {
            valueX = labelX + 42;
        }
        int textY = rowY + Math.max(1, (TOPIC_ROW_HEIGHT - this.textRenderer.fontHeight) / 2);
        context.drawText(this.textRenderer, label, labelX, textY, COLOR_TEXT_PARCHMENT, false);
        context.drawText(this.textRenderer, valueLabel, valueX, textY, 0xFFE6D7A3, false);

        drawControlBox(context, minusX, controlY, controlSize, "-", mouseX, mouseY);
        drawControlBox(context, plusX, controlY, controlSize, "+", mouseX, mouseY);
    }

    private String getAdjustableSkillValueLabel(TopicAction action) {
        return switch (action) {
            case SKILL_FISH -> fishTargetCount > 0 ? "Catches " + fishTargetCount : "Until sunset";
            case SKILL_WOOL -> woolTargetCount > 0 ? "Wool " + woolTargetCount : "Default";
            case SKILL_STRIPMINE -> stripmineLength > 0 ? "Length " + stripmineLength : "Default";
            case SKILL_ASCENT -> ascentSurfaceMode
                    ? "Surface"
                    : (ascentBlocks > 0 ? "Blocks " + ascentBlocks : "Default");
            case SKILL_DESCENT -> descentBlocks > 0 ? "Blocks " + descentBlocks : "Default";
            default -> "";
        };
    }

    private void drawTopicRow(DrawContext context, int rowX, int rowY, int rowW, TopicEntry entry,
                              boolean active, int mouseX, int mouseY) {
        boolean enabled;
        if (entry.category == TopicCategory.DIALOGUE) {
            enabled = isDialogueEntryEnabled(entry);
        } else if (entry.category == TopicCategory.ADMIN) {
            enabled = isAdminEntryEnabled(entry);
        } else {
            enabled = entry.action == null || isEntryEnabled(entry.action);
        }
        boolean hover = mouseX >= rowX && mouseX < rowX + rowW
                && mouseY >= rowY && mouseY < rowY + TOPIC_ROW_HEIGHT;
        int baseRow = active ? 0xFF3A2C14 : 0xFF1A1A1A;
        int rowColor = hover ? (active ? 0xFF4A3720 : 0xFF2A2A2A) : baseRow;
        if (!enabled) {
            rowColor = 0xFF151515;
        }
        context.fill(rowX, rowY, rowX + rowW, rowY + TOPIC_ROW_HEIGHT, rowColor);

        int textY = rowY + Math.max(1, (TOPIC_ROW_HEIGHT - this.textRenderer.fontHeight) / 2);
        int labelX = rowX + 4 + entry.indent * 8;
        String label = entry.label;

        // UI declutter: only show state for toggles; actions are implicit via clicking the row.
        String status = entry.toggle ? (active ? "ON" : "OFF") : null;
        int statusX = status == null ? -1 : (rowX + rowW - 4 - this.textRenderer.getWidth(status));
        int statusColor = active ? 0xFFE6D7A3 : 0xFFB0B0B0;
        if (!enabled) {
            statusColor = 0xFF6F6F6F;
        }

        int labelMaxW = status == null
                ? Math.max(0, (rowX + rowW - 4) - labelX)
                : Math.max(0, (statusX - TOPIC_CONTROL_GAP) - labelX);
        String drawnLabel = elideToWidth(label, labelMaxW);
        int labelColor = enabled ? COLOR_TEXT_PARCHMENT : COLOR_TEXT_DISABLED;
        context.drawText(this.textRenderer, drawnLabel, labelX, textY, labelColor, false);

        if (status != null) {
            context.drawText(this.textRenderer, status, statusX, textY, statusColor, false);
        }
    }

    private void drawOverlayHoverTooltip(DrawContext context, int mouseX, int mouseY) {
        if (!topicsExpanded || overlayDraggingSplit) {
            overlayHoveredEntry = null;
            return;
        }

        // Only consider entries in the list area (below tabs).
        TopicEntry hovered = getTopicEntryAtOverlay(mouseX, mouseY);
        if (hovered != overlayHoveredEntry) {
            overlayHoveredEntry = hovered;
            overlayHoverStartedAtMs = System.currentTimeMillis();
        }

        if (overlayHoveredEntry == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - overlayHoverStartedAtMs < OVERLAY_HOVER_TOOLTIP_DELAY_MS) {
            return;
        }

        java.util.List<String> lines = getOverlayTooltipLines(overlayHoveredEntry);
        if (lines == null || lines.isEmpty()) {
            return;
        }

        drawTooltipBox(context, mouseX, mouseY, lines);
    }

    private java.util.List<String> getOverlayTooltipLines(TopicEntry entry) {
        if (entry == null) {
            return java.util.List.of();
        }

        // Prefer richer tooltips for Admin entries (these are the ones that get truncated / ambiguous).
        if (entry.category == TopicCategory.ADMIN) {
            if (entry.action == TopicAction.OPEN_SPELLS) {
                MinecraftClient client = MinecraftClient.getInstance();
                boolean nearTable = isNearEnchantingTable(client, 4);
                boolean hasBook = hasSpellbookToken(client);
                boolean hasEye = hasEyeOfEnderToken(client);

                if (nearTable || hasBook) {
                    return java.util.List.of(
                            "Spells",
                            "Full access: Enchanting Table (4 blocks) or Wizard's Tome.",
                            "Eye of Ender can also open (Summon-only; cooldown)."
                    );
                }

                if (hasEye) {
                    return java.util.List.of(
                            "Spells",
                            "Eye of Ender: limited access (Summon-only; cooldown).",
                        "Full access: Enchanting Table nearby (4 blocks) or Wizard's Tome."
                    );
                }
                return java.util.List.of(
                        "Spells",
                        "Requires: Enchanting Table nearby (4 blocks)",
                    "or a Wizard's Tome.",
                        "(Also: Eye of Ender for limited Summon-only access; cooldown applies.)"
                );
            }

            String k = entry.dialogueKey == null ? "" : entry.dialogueKey.trim().toLowerCase(Locale.ROOT);
            if (k.equals("give_wizard_tome")) {
                return java.util.List.of(
                        "Give Wizard's Tome",
                        "Operator-only: grants you the Wizard's Tome quest item.",
                        "Equivalent to: /bot wizard_tome"
                );
            }
            if (k.equals("recruit_status")) {
                return java.util.List.of("Recruitment status", "Shows current survival recruitment state.");
            }
            if (k.equals("recruit_reset")) {
                return java.util.List.of(
                        "Reset recruitment (this world)",
                        "Clears recruitment progress/state for testing.",
                        "UI safety: click twice within 5s to confirm."
                );
            }
            if (k.equals("recruit_enable")) {
                return java.util.List.of("Enable survival recruitment mode", "Turns ON survival recruitment system.");
            }
            if (k.equals("recruit_disable")) {
                return java.util.List.of("Disable survival recruitment mode", "Turns OFF survival recruitment system.");
            }
            if (k.equals("anchor_set")) {
                return java.util.List.of("Set village anchor here", "Sets the village/settlement anchor at your position.");
            }
            if (k.equals("anchor_clear")) {
                return java.util.List.of("Clear village anchor", "Clears the saved village/settlement anchor.");
            }
            if (k.startsWith("setstage:")) {
                String n = k.substring("setstage:".length()).trim();
                return java.util.List.of("Set companion stage: " + n, "Advances/rewinds companion quest stage.");
            }

            // Fallback: show the label.
            return java.util.List.of(entry.label);
        }

        // Dialogue topics: show the full prompt (the transcript also uses this).
        if (entry.category == TopicCategory.DIALOGUE) {
            String prompt = getDialoguePrompt(entry.dialogueKey, entry.label);
            if (prompt != null && !prompt.isBlank()) {
                return java.util.List.of(prompt);
            }
        }

        if (entry.category == TopicCategory.SKILL && entry.action == TopicAction.DROP_SWEEP) {
            return java.util.List.of(
                    "Cleanup",
                    "Runs cleanup (drop sweep) to collect nearby dropped items."
            );
        }

        return java.util.List.of(entry.label);
    }

    private void drawTooltipBox(DrawContext context, int mouseX, int mouseY, java.util.List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }

        int pad = 6;
        int lineH = this.textRenderer.fontHeight;
        int maxW = 0;
        for (String s : lines) {
            if (s == null) continue;
            maxW = Math.max(maxW, this.textRenderer.getWidth(s));
        }
        int boxW = maxW + pad * 2;
        int boxH = lines.size() * lineH + pad * 2;

        int x = mouseX + 12;
        int y = mouseY + 10;
        // Keep inside screen bounds.
        if (x + boxW > this.width - 8) {
            x = mouseX - 12 - boxW;
        }
        if (y + boxH > this.height - 8) {
            y = this.height - 8 - boxH;
        }
        x = MathHelper.clamp(x, 8, Math.max(8, this.width - 8 - boxW));
        y = MathHelper.clamp(y, 8, Math.max(8, this.height - 8 - boxH));

        int bg = 0xEE101010;
        int border = 0xFF000000;
        context.fill(x, y, x + boxW, y + boxH, bg);
        context.fill(x, y, x + boxW, y + 1, border);
        context.fill(x, y + boxH - 1, x + boxW, y + boxH, border);
        context.fill(x, y, x + 1, y + boxH, border);
        context.fill(x + boxW - 1, y, x + boxW, y + boxH, border);

        int ty = y + pad;
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            if (s == null) continue;
            int color = (i == 0) ? COLOR_TEXT_PLAYER : COLOR_TEXT_PARCHMENT;
            context.drawText(this.textRenderer, s, x + pad, ty, color, false);
            ty += lineH;
        }
    }

    private String elideToWidth(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (maxWidth <= 0) {
            return "";
        }
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int ellipsisW = this.textRenderer.getWidth(ellipsis);
        if (ellipsisW >= maxWidth) {
            return ellipsis;
        }
        // Binary search the longest prefix that fits.
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            String candidate = text.substring(0, mid) + ellipsis;
            if (this.textRenderer.getWidth(candidate) <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + ellipsis;
    }

    private void requestCompanionQuestState() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null) {
            return;
        }
        ClientPlayNetworking.send(new CompanionQuestStateRequestPayload(botAlias));
        companionQuestStateRequested = true;
    }

    private boolean isDialogueEntryEnabled(TopicEntry entry) {
        if (entry == null || entry.category != TopicCategory.DIALOGUE) {
            return false;
        }
        String key = entry.dialogueKey;
        if (key == null || key.isBlank()) {
            return true;
        }

        // Server snapshot (defaults to stage 0 until we hear back).
        int stage = Math.max(0, net.wcfcarolina13.FrensClient.getCompanionQuestStage(botAlias));
        boolean permanent = net.wcfcarolina13.FrensClient.isCompanionPermanent(botAlias);

        String k = key.trim().toLowerCase(Locale.ROOT);

        // Recruitment entry: available only while survival recruitment is active and not completed.
        if (k.equals("recruit_contact")) {
            return net.wcfcarolina13.FrensClient.isSurvivalRecruitmentEnabled()
                && !net.wcfcarolina13.FrensClient.isSurvivalRecruitmentCompleted();
        }

        // Recruitment replay/reset: useful for testing; server will enforce authorization.
        if (k.equals("recruit_replay")) {
            return net.wcfcarolina13.FrensClient.isSurvivalRecruitmentEnabled();
        }

        // Always-available talk options.
        if (k.equals("village_about") || k.equals("stay_conditions") || k.equals("goodbye")) {
            return true;
        }

        // Core quest topics are only meaningful after recruitment.
        if (k.equals("companion_status") || k.equals("companion_check") || k.equals("companion_anchor_set")
                || k.equals("village_missing") || k.equals("village_projects")
                || k.equals("batch3_biomes") || k.equals("batch3_structures")
                || k.equals("batch3_dimensions") || k.equals("batch3_traders_mounts")
                || k.equals("batch3_travel")) {
            return net.wcfcarolina13.FrensClient.isSurvivalRecruitmentCompleted();
        }

        // Relationship / story beats unlock as the settlement improves.
        return switch (k) {
            case "bot_past" -> stage >= 2 || permanent;
            case "promise" -> stage >= 3 || permanent;
            default -> stage >= 1 || permanent;
        };
    }

    private void drawControlBox(DrawContext context, int x, int y, int size, String label, int mouseX, int mouseY) {
        drawControlBox(context, x, y, size, label, mouseX, mouseY, false);
    }

    private void drawControlBox(DrawContext context, int x, int y, int size, String label, int mouseX, int mouseY, boolean active) {
        boolean hover = mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;
        int fill = active ? 0xFF3A2C14 : 0xFF1A1A1A;
        if (hover) {
            fill = active ? 0xFF4A3720 : 0xFF2F2F2F;
        }
        context.fill(x, y, x + size, y + size, fill);
        int textX = x + (size - this.textRenderer.getWidth(label)) / 2;
        int textY = y + Math.max(1, (size - this.textRenderer.fontHeight) / 2);
        context.drawText(this.textRenderer, label, textX, textY, COLOR_TEXT_PARCHMENT, false);
    }

    private String formatFollowDistance() {
        double value = getFollowDistance();
        if (value <= 0.0D) {
            return "Distance --";
        }
        return "Distance " + String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        context.drawText(this.textRenderer, this.botAlias, this.titleX, this.titleY, 0x404040, false);
        context.drawText(this.textRenderer, "Level: " + this.handler.getBotLevel(), this.titleX + 90, this.titleY, 0x404040, false);
        context.drawText(this.textRenderer, this.playerInventoryTitle, this.playerInventoryTitleX, this.playerInventoryTitleY, 0x404040, false);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean isInside) {
        if (topicsExpanded) {
            return clickTopicsOverlay(click);
        }

        if (isMouseOverTopicsHeader(click.x(), click.y())) {
            toggleTopicsExpanded(true);
            return true;
        }
        TopicEntry entry = getTopicEntryAt(click.x(), click.y());
        if (entry != null) {
            boolean enabled = entry.category == TopicCategory.DIALOGUE
                    ? isDialogueEntryEnabled(entry)
                    : (entry.action == null || isEntryEnabled(entry.action));
            if (enabled) {
                handleTopicEntry(entry);
                return true;
            }
            return false;
        }
        return super.mouseClicked(click, isInside);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double deltaX, double deltaY) {
        if (topicsExpanded && overlayDraggingSplit && click.button() == 0) {
            updateOverlaySplitFromMouse(click.x());
            return true;
        }
        if (topicsExpanded && overlayDraggingListScroll && click.button() == 0) {
            Rect r = computeTopicsOverlayRect();
            OverlayColumns cols = computeOverlayColumns(r);
            int rowsHeight = getOverlayRowsHeight(cols.contentH);
            int visibleRows = Math.max(1, rowsHeight / TOPIC_ROW_HEIGHT);
            List<TopicEntry> entries = getFilteredOverlayEntries(getOverlayEntries());
            Rect track = computeOverlayListScrollbarTrack(cols.listX, getOverlayRowsStartY(cols.contentY), cols.listW, rowsHeight);
            Rect thumb = computeOverlayListScrollbarThumb(track, entries.size(), visibleRows);
            updateOverlayScrollFromThumb(click, track, thumb, entries.size(), visibleRows);
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        if (overlayDraggingSplit) {
            overlayDraggingSplit = false;
            return true;
        }
        if (overlayDraggingListScroll) {
            overlayDraggingListScroll = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (topicsExpanded && isMouseOverTopicsOverlay(mouseX, mouseY)) {
            Rect r = computeTopicsOverlayRect();
            int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
            int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;
            int listY = r.y + headerH + 2;
            int listH = (r.bottom() - footerH - 2) - listY;
            List<TopicEntry> entries = getFilteredOverlayEntries(getOverlayEntries());
            int visibleRows = Math.max(1, getOverlayRowsHeight(listH) / TOPIC_ROW_HEIGHT);
            int maxScroll = Math.max(0, entries.size() - visibleRows);
            if (maxScroll == 0) {
                return true;
            }
            int delta = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
            if (delta != 0) {
                setOverlayScrollIndex(MathHelper.clamp(getOverlayScrollIndex() + delta, 0, maxScroll));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input != null && topicsExpanded) {
            int key = input.key();
            if (topicSearchFocused) {
                if (key == 259 /* BACKSPACE */) {
                    if (!topicSearchQuery.isEmpty()) {
                        setTopicSearchQuery(topicSearchQuery.substring(0, topicSearchQuery.length() - 1));
                    }
                    return true;
                }
                char typed = keyToSearchCharacter(input);
                if (typed != 0) {
                    setTopicSearchQuery(topicSearchQuery + typed);
                    return true;
                }
                if (key == 257 || key == 335) { // ENTER
                    topicSearchFocused = false;
                    return true;
                }
                if (key == 256 /* ESC */) {
                    if (!topicSearchQuery.isEmpty()) {
                        setTopicSearchQuery("");
                    } else {
                        topicSearchFocused = false;
                    }
                    return true;
                }
            }
            if (key == 256 /* ESC */) {
                toggleTopicsExpanded(false);
                return true;
            }
        }
        return super.keyPressed(input);
    }

    private char keyToSearchCharacter(KeyInput input) {
        if (input == null) {
            return 0;
        }
        int key = input.key();
        if (key >= 65 && key <= 90) {
            return (char) ('a' + (key - 65));
        }
        if (key >= 48 && key <= 57) {
            return (char) ('0' + (key - 48));
        }
        return switch (key) {
            case 32 -> ' ';
            case 44 -> ',';
            case 45 -> '-';
            case 46 -> '.';
            case 47 -> '/';
            case 95 -> '_';
            default -> 0;
        };
    }

    private TopicEntry getTopicEntryAt(double mouseX, double mouseY) {
        if (!isMouseOverTopicPanel(mouseX, mouseY)) {
            return null;
        }
        int gridX = getTopicRowX();
        int gridY = getTopicListTop();
        int gridW = getTopicRowWidth();
        int gridH = getTopicListHeight();
        if (mouseX < gridX || mouseX >= gridX + gridW || mouseY < gridY || mouseY >= gridY + gridH) {
            return null;
        }

        QuickGridLayout layout = computeQuickGridLayout(gridW, gridH);
        int cols = layout.cols;
        int rows = layout.rows;
        int bw = layout.buttonW;
        int bh = layout.buttonH;
        int gap = layout.gap;

        int col = (int) ((mouseX - gridX) / (bw + gap));
        int row = (int) ((mouseY - gridY) / (bh + gap));
        if (col < 0 || col >= cols || row < 0 || row >= rows) {
            return null;
        }
        int cellX = gridX + col * (bw + gap);
        int cellY = gridY + row * (bh + gap);
        if (mouseX >= cellX + bw || mouseY >= cellY + bh) {
            // Click landed in the gap between buttons.
            return null;
        }
        int index = row * cols + col;
        int maxButtons = Math.min(QUICK_TOPIC_ENTRIES.size(), cols * rows);
        if (index < 0 || index >= maxButtons) {
            return null;
        }
        return QUICK_TOPIC_ENTRIES.get(index);
    }


    private int getFollowEntryIndex(List<TopicEntry> entries) {
        return getSkillEntryIndex(entries, TopicAction.FOLLOW);
    }

    private int getWoodcutEntryIndex(List<TopicEntry> entries) {
        return getSkillEntryIndex(entries, TopicAction.SKILL_WOODCUT);
    }

    private void handleTopicEntry(TopicEntry entry) {
        if (entry == null) {
            return;
        }

        // Admin topics include both operator-only utilities and gated companion commands.
        if (entry.category == TopicCategory.ADMIN) {
            if (!isAdminEntryEnabled(entry)) {
                return;
            }
            if (entry.label != null && !entry.label.isBlank()) {
                net.wcfcarolina13.FrensClient.appendDialogue(botAlias,
                        entry.action != null ? "You (Companion): " + entry.label : "You (Admin): " + entry.label);
            }

            if (entry.action != null) {
                handleTopicAction(entry.action);
            } else {
                handleAdminTopic(entry.dialogueKey);
            }
            return;
        }

        // Dialogue topics are lore/quest conversation and should not trigger bot skills.
        if (entry.category == TopicCategory.DIALOGUE) {
            // Selecting a topic from the list should NOT auto-append a player line.
            // Player lines are reserved for explicit red responses.
            handleDialogueTopicEntry(entry, false);
            return;
        }

        if (entry.action == null) {
            return;
        }

        handleTopicAction(entry.action);
    }

    private void handleDialogueTopicEntry(TopicEntry entry, boolean appendPlayerResponse) {
        if (entry == null) {
            return;
        }
        if (entry.category != TopicCategory.DIALOGUE) {
            return;
        }
        if (!isDialogueEntryEnabled(entry)) {
            return;
        }

        // Morrowind-style: Goodbye simply closes the dialogue overlay.
        String key = entry.dialogueKey != null ? entry.dialogueKey.trim().toLowerCase(Locale.ROOT) : "";
        if (key.equals("goodbye")) {
            toggleTopicsExpanded(false);
            return;
        }

        // Remember selected topic for the topic-header style dialogue column.
        lastDialogueTopicLabel = entry.label != null ? entry.label : "";
        lastDialogueTopicKey = entry.dialogueKey != null ? entry.dialogueKey : "";

        // Only append player dialogue when the player explicitly clicks a red response.
        if (appendPlayerResponse) {
            String prompt = getDialoguePrompt(entry.dialogueKey, entry.label);
            if (prompt != null && !prompt.isBlank()) {
                net.wcfcarolina13.FrensClient.appendDialogue(botAlias, "You: " + prompt);
            }
        }

        handleDialogueTopic(entry.dialogueKey);
    }

    private void handleTopicAction(TopicAction action) {
        if (action == null) {
            return;
        }
        switch (action) {
            case COMPANION_COME -> runCompanionCome();
            case COMPANION_SUMMON -> runCompanionSummon();
            case COMPANION_HOME -> runCompanionHome();
            case OPEN_SPELLS -> openSpellsMenu();
            case OPEN_GUIDE -> openGuideMenu();
            case OPEN_BOT_CONTROLS -> openBotControls();
            case STOP -> runStop();
            case RESUME -> runResume();
            case FOLLOW -> toggleFollow();
            case GUARD -> toggleGuard();
            case PATROL -> togglePatrol();
            case RETURN_HOME -> runReturnHome();
            case SLEEP -> runSleep();
            case AUTO_RETURN_SUNSET -> toggleAutoReturnSunset();
            case AUTO_RETURN_SUNSET_GUARD_PATROL -> toggleAutoReturnSunsetGuardPatrol();
            case IDLE_HOBBIES -> toggleIdleHobbies();
            case AUTO_HUNT_STARVING -> toggleAutoHuntStarving();
            case GAMEPLAY_TIPS -> toggleGameplayTips();
            case IDLE_HOBBIES_ANYWHERE -> toggleIdleHobbiesAnywhere();
            case BARITONE_PATHFINDER -> toggleBaritonePathfinder();
            case UNLEASH_TETHERED -> toggleUnleashTethered();
            case LEASH_ON_DISMOUNT -> toggleLeashOnDismount();
            case DROP_SWEEP -> runSkillCommand("drop_sweep", null);
            case BASES -> openBasesManager();
            case CRAFTING -> openCraftingHistory();
            case COOKING -> openCookingMenu();
            case HUNTING -> openHuntingMenu();
            case CONSTRUCTION -> openConstructionMenu();
            case SKILL_FISH -> runFishSkillCommand();
            case SKILL_WOODCUT -> runWoodcutSkillCommand();
            case SKILL_WOODCUT_CLEANUP -> runSkillCommand("woodcut_cleanup", null);
            case SKILL_WOOL -> runWoolSkillCommand();
            case SKILL_HOVEL -> runShelterWithLook("hovel");
            case SKILL_BURROW -> runShelterWithLook("burrow");
            case SKILL_FARM -> runSkillCommand("farm", null);
            case SKILL_COLLECT_DIRT -> runSkillCommand("collect_dirt", null);
            case SKILL_MINING -> runSkillCommand("mining", null);
            case SKILL_STRIPMINE -> runStripmineSkillCommand();
            case SKILL_ASCENT -> runAscentSkillCommand();
            case SKILL_DESCENT -> runDescentSkillCommand();
        }
    }

    private static String getDialoguePrompt(String key, String fallbackLabel) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "stay_conditions" -> "What would make you stay?";
            case "village_missing" -> "What's missing here?";
            case "village_projects" -> "What projects should we build next?";
            case "companion_status" -> "How are we doing here?";
            case "companion_check" -> "Check our progress.";
            case "companion_anchor_set" -> "Let's make this our home.";
            case "village_about" -> "Tell me about the village.";
            case "batch3_biomes" -> "Tell me about these biomes.";
            case "batch3_structures" -> "Tell me about the structures we've seen.";
            case "batch3_dimensions" -> "Tell me about the dimensions.";
            case "batch3_traders_mounts" -> "Tell me about traders and mounts.";
            case "batch3_travel" -> "Tell me about travel.";
            case "bot_past" -> "Tell me about your past.";
            case "promise" -> "I have a promise to make.";
            case "goodbye" -> "Goodbye.";
            case "recruit_contact" -> "Can we talk?";
            case "recruit_replay" -> "Let's start over.";
            default -> fallbackLabel;
        };
    }

    private void handleDialogueTopic(String key) {
        String k = key == null ? "" : key;
        String normalized = k.trim().toLowerCase(Locale.ROOT);

        // Morrowind-style: Goodbye just closes the dialogue.
        if (normalized.equals("goodbye")) {
            toggleTopicsExpanded(false);
            return;
        }

        // Recruitment: ask the server to open the recruitment dialogue (server validates village proximity).
        if (normalized.equals("recruit_contact")) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getNetworkHandler() != null) {
                ClientPlayNetworking.send(new RequestRecruitmentDialoguePayload());
            } else {
                net.wcfcarolina13.FrensClient.appendDialogue(botAlias, "... (not connected)");
            }
            return;
        }

        // Recruitment replay/reset: asks server to despawn/reset recruitment state (server validates auth).
        if (normalized.equals("recruit_replay")) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getNetworkHandler() != null) {
                String alias = net.wcfcarolina13.FrensClient.getRecruitmentBotAlias();
                ClientPlayNetworking.send(new RequestRecruitmentReplayPayload(alias));
            } else {
                net.wcfcarolina13.FrensClient.appendDialogue(botAlias, "... (not connected)");
            }
            return;
        }

        java.util.function.Consumer<String> bot = (line) -> {
            if (line == null || line.isBlank()) return;
            net.wcfcarolina13.FrensClient.appendDialogue(botAlias, botAlias + ": " + line);
        };

        // Server-authoritative companion quest topics (real checks + persistent stage changes).
        if (normalized.equals("companion_status")
                || normalized.equals("companion_check")
                || normalized.equals("companion_anchor_set")
                || normalized.equals("village_missing")
                || normalized.equals("village_projects")
                || normalized.equals("batch3_biomes")
                || normalized.equals("batch3_structures")
                || normalized.equals("batch3_dimensions")
                || normalized.equals("batch3_traders_mounts")
                || normalized.equals("batch3_travel")) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getNetworkHandler() != null) {
                ClientPlayNetworking.send(new CompanionQuestTopicPayload(botAlias, normalized));
            } else {
                bot.accept("...");
            }
            return;
        }

        switch (normalized) {
            case "village_about" -> {
                bot.accept("Villages are fragile.");
                bot.accept("But they're the closest thing this world has to a promise.");
                bot.accept("If you want me to stay… make it safe. Make it real.");
            }
            case "stay_conditions" -> {
                bot.accept("I don't pledge myself to wandering.");
                bot.accept("Give me a bed that stays mine. Food that doesn't run out. A place I can defend.");
                bot.accept("And proof that when trouble comes… you don't run.");
            }
            case "bot_past" -> {
                bot.accept("I used to think I could do everything alone.");
                bot.accept("Turns out the world doesn't reward pride.");
                bot.accept("If you want a companion, build a home worth returning to.");
            }
            case "promise" -> {
                bot.accept("Words are cheap.");
                bot.accept("Bring me results. Then we'll see what you get in return.");
            }
            default -> bot.accept("...");
        }
    }

    private TopicEntry getDialogueResponseEntryAtOverlay(double mouseX, double mouseY) {
        if (dialogueResponseHitboxes.isEmpty()) {
            return null;
        }
        for (int i = 0; i < dialogueResponseHitboxes.size(); i++) {
            DialogueResponseHitbox h = dialogueResponseHitboxes.get(i);
            if (h == null) continue;
            Rect r = h.rect;
            if (r != null && r.contains(mouseX, mouseY)) {
                return h.entry;
            }
        }
        return null;
    }

    private java.util.List<TopicEntry> getDialogueResponseEntries(int max) {
        int limit = Math.max(0, max);
        String current = lastDialogueTopicKey != null ? lastDialogueTopicKey.trim().toLowerCase(Locale.ROOT) : "";
        java.util.ArrayList<TopicEntry> out = new java.util.ArrayList<>();
        for (TopicEntry e : DIALOGUE_TOPIC_ENTRIES) {
            if (e == null) continue;
            String k = e.dialogueKey != null ? e.dialogueKey.trim().toLowerCase(Locale.ROOT) : "";
            if (k.equals("goodbye")) continue;
            if (!current.isEmpty() && k.equals(current)) continue;
            if (!isDialogueEntryEnabled(e)) continue;
            out.add(e);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private String formatDialogueLineForDisplay(String line) {
        if (line == null) return "";
        String s = line.trim();
        if (s.isEmpty()) return "";

        String botPrefix = (botAlias != null && !botAlias.isBlank()) ? (botAlias + ":") : null;
        if (botPrefix != null && s.startsWith(botPrefix)) {
            s = s.substring(botPrefix.length()).trim();
            return s;
        }
        if (s.startsWith("You:")) {
            return s.substring("You:".length()).trim();
        }
        // Keep system/admin prefixes for clarity.
        return s;
    }

    private boolean isAdminEntryEnabled(TopicEntry entry) {
        if (entry == null || entry.category != TopicCategory.ADMIN) {
            return false;
        }
        // Spells are always accessible from this tab; the spell screen itself explains requirements
        // and the server remains authoritative for what actually works.
        if (entry.action == TopicAction.OPEN_SPELLS) {
            return true;
        }

        // Other recruitment/admin utilities remain operator-only.
        if (entry.action != null) {
            return isAdminUser();
        }
        // Recruitment/admin utilities remain operator-only.
        return isAdminUser();
    }

    private boolean isAdminTabEnabled() {
        // Always enabled because the Spells entry lives here.
        return true;
    }

    private boolean canOpenSpellsMenu() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return false;
        }
        // Spells are normally cast at an Enchanting Table. A later-stage quest reward (Wizard's Tome)
        // can allow access anywhere. An Eye of Ender can grant limited, cooldown-gated access.
        return isNearEnchantingTable(client, 4) || hasSpellbookToken(client) || hasEyeOfEnderToken(client);
    }

    private boolean hasEyeOfEnderToken(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        var player = client.player;
        if (player == null) {
            return false;
        }
        var inv = player.getInventory();
        int n = inv.size();
        for (int i = 0; i < n; i++) {
            var stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (stack.isOf(Items.ENDER_EYE)) {
                return true;
            }
        }
        return false;
    }

    private boolean isNearEnchantingTable(MinecraftClient client, int radius) {
        if (client == null || client.player == null || client.world == null) {
            return false;
        }
        BlockPos origin = client.player.getBlockPos();
        int r = Math.max(1, radius);
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -2, -r), origin.add(r, 2, r))) {
            if (!client.world.isChunkLoaded(pos)) {
                continue;
            }
            var state = client.world.getBlockState(pos);
            if (state.isOf(Blocks.ENCHANTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSpellbookToken(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        // Preferred token: the dedicated quest item Wizard's Tome.
        // Back-compat: older builds used a renamed book token containing "spellbook".
        var inv = client.player.getInventory();
        int n = inv.size();
        for (int i = 0; i < n; i++) {
            var stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (stack.isOf(ModItems.WIZARD_TOME)) {
                return true;
            }
            if (!(stack.isOf(Items.WRITTEN_BOOK) || stack.isOf(Items.ENCHANTED_BOOK))) {
                continue;
            }
            String name = stack.getName() != null ? stack.getName().getString() : "";
            String lower = name != null ? name.toLowerCase(Locale.ROOT) : "";
            if (lower.contains("spellbook")) {
                return true;
            }
            if (lower.contains("wizard") && lower.contains("tome")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasItem(MinecraftClient client, net.minecraft.item.Item item) {
        if (client == null || client.player == null || item == null) {
            return false;
        }
        var inv = client.player.getInventory();
        int n = inv.size();
        for (int i = 0; i < n; i++) {
            if (inv.getStack(i).isOf(item)) {
                return true;
            }
        }
        return false;
    }

    private void handleAdminTopic(String key) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null) {
            net.wcfcarolina13.FrensClient.appendDialogue(botAlias, "Admin: not connected.");
            return;
        }

        // Non-recruitment admin convenience actions.
        if (k.equals("give_wizard_tome")) {
            ClientPlayNetworking.send(new RecruitmentAdminActionPayload(botAlias, "give_wizard_tome", 1, false));
            return;
        }

        // Confirm reset: require a second click within a short window.
        if (k.equals("recruit_reset")) {
            long now = System.currentTimeMillis();
            long windowMs = 5000L;
            boolean within = adminResetConfirmArmed && (now - adminResetConfirmArmedAtMs) <= windowMs;
            if (!within) {
                adminResetConfirmArmed = true;
                adminResetConfirmArmedAtMs = now;
                net.wcfcarolina13.FrensClient.appendDialogue(botAlias, "Admin: Click again within 5s to CONFIRM reset.");
                return;
            }
            // Proceed and disarm.
            adminResetConfirmArmed = false;
            adminResetConfirmArmedAtMs = 0L;
        } else {
            // Any other admin action disarms the reset confirmation.
            adminResetConfirmArmed = false;
            adminResetConfirmArmedAtMs = 0L;
        }

        String action;
        int intArg = 0;
        boolean boolArg = false;

        if (k.equals("recruit_status")) {
            action = "status";
        } else if (k.equals("recruit_reset")) {
            action = "reset";
        } else if (k.equals("recruit_enable")) {
            action = "enable";
        } else if (k.equals("recruit_disable")) {
            action = "disable";
        } else if (k.equals("anchor_set")) {
            action = "setanchor_here";
        } else if (k.equals("anchor_clear")) {
            action = "clearanchor";
        } else if (k.startsWith("setstage:")) {
            action = "setstage";
            try {
                intArg = Integer.parseInt(k.substring("setstage:".length()).trim());
            } catch (NumberFormatException ignored) {
                intArg = 0;
            }
        } else {
            action = k;
        }

        ClientPlayNetworking.send(new RecruitmentAdminActionPayload(botAlias, action, intArg, boolArg));
    }

    private boolean isEntryActive(TopicAction action) {
        return switch (action) {
            case FOLLOW -> isFollowActive();
            case GUARD -> isGuardActive();
            case PATROL -> isPatrolActive();
            case RETURN_HOME -> isReturningToBase();
            case AUTO_RETURN_SUNSET -> isAutoReturnAtSunsetActive();
            case AUTO_RETURN_SUNSET_GUARD_PATROL -> isAutoReturnGuardPatrolEligibleActive();
            case IDLE_HOBBIES -> isIdleHobbiesActive();
            case AUTO_HUNT_STARVING -> isAutoHuntStarvingActive();
            case GAMEPLAY_TIPS -> isGameplayTipsActive();
            case IDLE_HOBBIES_ANYWHERE -> isIdleHobbiesAnywhereActive();
            case BARITONE_PATHFINDER -> isBaritonePathfinderActive();
            case UNLEASH_TETHERED -> isUnleashTetheredActive();
            case LEASH_ON_DISMOUNT -> isLeashOnDismountActive();
            default -> false;
        };
    }

    private boolean isEntryEnabled(TopicAction action) {
        return switch (action) {
            // Stop should always be available (e.g., cancel return-to-base, follow, guard/patrol, etc.).
            case STOP -> true;
            case RESUME -> this.handler != null && this.handler.isBotTaskPaused();
            default -> true;
        };
    }

    private boolean isAutoReturnAtSunsetActive() {
        return this.handler != null && this.handler.isBotAutoReturnAtSunset();
    }

    private boolean isIdleHobbiesActive() {
        return this.handler != null && this.handler.isBotIdleHobbiesEnabled();
    }

    private boolean isAutoHuntStarvingActive() {
        return this.handler != null && this.handler.isBotAutoHuntStarvingEnabled();
    }

    private boolean isAutoReturnGuardPatrolEligibleActive() {
        return this.handler != null && this.handler.isBotAutoReturnGuardPatrolEligible();
    }

    private String buildSkillCommand(String skillName, String action) {
        String botTarget = formatBotTarget();
        String args = action != null && !action.isBlank()
                ? action + " " + botTarget
                : botTarget;
        return "bot skill " + skillName + " " + args;
    }

    private void runSkillCommand(String skillName, String action) {
        String command = buildSkillCommand(skillName, action);
        sendChatCommand(command);
    }

    private void queueDirectionalMiningCommand(String actionLabel, String skillName, String action) {
        String command = buildSkillCommand(skillName, action);
        net.wcfcarolina13.FrensClient.setPendingDirectionalMining(actionLabel, command);
        this.close();
    }

    private void runWoodcutSkillCommand() {
        runSkillCommand("woodcut", Integer.toString(woodcutTreeCount));
    }

    private void runFishSkillCommand() {
        String arg = fishTargetCount > 0 ? Integer.toString(fishTargetCount) : null;
        runSkillCommand("fish", arg);
    }

    private void runWoolSkillCommand() {
        String arg = woolTargetCount > 0 ? Integer.toString(woolTargetCount) : null;
        runSkillCommand("wool", arg);
    }

    private void runStripmineSkillCommand() {
        String arg = stripmineLength > 0 ? Integer.toString(stripmineLength) : null;
        queueDirectionalMiningCommand("stripmine", "stripmine", arg);
    }

    private void runAscentSkillCommand() {
        String arg;
        if (ascentSurfaceMode) {
            arg = "ascent surface";
        } else if (ascentBlocks > 0) {
            arg = "ascent " + ascentBlocks;
        } else {
            arg = "ascent";
        }
        queueDirectionalMiningCommand("ascent", "mining", arg);
    }

    private void runDescentSkillCommand() {
        String arg = descentBlocks > 0 ? ("descent " + descentBlocks) : "descent";
        queueDirectionalMiningCommand("descent", "mining", arg);
    }

    private boolean isMouseOverTopicPanel(double mouseX, double mouseY) {
        int panelX = getTopicPanelX();
        int panelY = getTopicPanelY();
        int panelW = getTopicPanelWidth();
        int panelH = getTopicPanelHeight();
        return mouseX >= panelX && mouseX < panelX + panelW
                && mouseY >= panelY && mouseY < panelY + panelH;
    }

    private int getTopicPanelX() {
        return this.x + SECTION_WIDTH + BLOCK_GAP;
    }

    private int getTopicPanelY() {
        return this.y + SECTION_HEIGHT + 2;
    }

    private int getTopicPanelWidth() {
        return SECTION_WIDTH;
    }

    private int getTopicPanelHeight() {
        return STATS_AREA_HEIGHT - 4;
    }

    private int getTopicRowX() {
        return getTopicPanelX() + TOPIC_PADDING;
    }

    private int getTopicRowWidth() {
        return getTopicPanelWidth() - TOPIC_PADDING * 2;
    }

    private int getTopicListTop() {
        return getTopicPanelY() + 2 + this.textRenderer.fontHeight + 1;
    }

    private int getTopicListHeight() {
        return getTopicPanelHeight() - (getTopicListTop() - getTopicPanelY());
    }


    private List<TopicEntry> getOverlayEntries() {
        return switch (overlayCategory) {
            case DIALOGUE -> DIALOGUE_TOPIC_ENTRIES;
            case ADMIN -> ADMIN_TOPIC_ENTRIES;
            default -> SKILL_TOPIC_ENTRIES;
        };
    }

    private void setTopicSearchQuery(String raw) {
        String next = raw != null ? raw : "";
        if (next.length() > 64) {
            next = next.substring(0, 64);
        }
        if (next.equals(topicSearchQuery)) {
            return;
        }
        topicSearchQuery = next;
        skillScrollIndex = 0;
        dialogueScrollIndex = 0;
        adminScrollIndex = 0;
    }

    private List<TopicEntry> getFilteredOverlayEntries(List<TopicEntry> base) {
        if (base == null || base.isEmpty()) {
            return List.of();
        }
        String q = topicSearchQuery != null ? topicSearchQuery.trim().toLowerCase(Locale.ROOT) : "";
        if (q.isBlank()) {
            return base;
        }
        java.util.ArrayList<TopicEntry> out = new java.util.ArrayList<>();
        for (TopicEntry entry : base) {
            if (entry == null) {
                continue;
            }
            String label = entry.label != null ? entry.label.toLowerCase(Locale.ROOT) : "";
            String aux = "";
            if (entry.category == TopicCategory.DIALOGUE) {
                String prompt = getDialoguePrompt(entry.dialogueKey, entry.label);
                aux = prompt != null ? prompt.toLowerCase(Locale.ROOT) : "";
            } else if (entry.category == TopicCategory.ADMIN) {
                aux = entry.dialogueKey != null ? entry.dialogueKey.toLowerCase(Locale.ROOT) : "";
            }
            if (label.contains(q) || aux.contains(q)) {
                out.add(entry);
            }
        }
        return out;
    }

    private int getOverlayScrollIndex() {
        return switch (overlayCategory) {
            case DIALOGUE -> dialogueScrollIndex;
            case ADMIN -> adminScrollIndex;
            default -> skillScrollIndex;
        };
    }

    private void setOverlayScrollIndex(int value) {
        switch (overlayCategory) {
            case DIALOGUE -> dialogueScrollIndex = value;
            case ADMIN -> adminScrollIndex = value;
            default -> skillScrollIndex = value;
        }
    }

    private void clampOverlayScroll(int visibleRows) {
        List<TopicEntry> entries = getFilteredOverlayEntries(getOverlayEntries());
        int maxScroll = Math.max(0, (entries != null ? entries.size() : 0) - visibleRows);
        setOverlayScrollIndex(MathHelper.clamp(getOverlayScrollIndex(), 0, maxScroll));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private boolean isFollowActive() {
        return this.handler != null && this.handler.isBotFollowing();
    }

    private boolean isGuardActive() {
        return this.handler != null && this.handler.isBotGuarding();
    }

    private boolean isPatrolActive() {
        return this.handler != null && this.handler.isBotPatrolling();
    }

    private double getFollowDistance() {
        return this.handler != null ? this.handler.getBotFollowDistance() : 0.0D;
    }

    private String formatBotTarget() {
        if (botAlias.contains(" ")) {
            return "\"" + botAlias + "\"";
        }
        return botAlias;
    }

    private void toggleFollow() {
        // Don't toggle off if bot is returning to base (uses FOLLOW mode internally)
        if (isReturningToBase()) {
            // Bot is returning home, starting follow would interrupt that
            // For now, just start following the player anyway (interrupts return-to-base)
        }
        String botTarget = formatBotTarget();
        String command = isFollowActive() ? "bot follow stop " + botTarget : "bot follow " + botTarget;
        sendChatCommand(command);
    }

    private boolean isReturningToBase() {
        return this.handler != null && this.handler.isBotReturningToBase();
    }

    private void adjustFollowDistance(int direction) {
        double current = getFollowDistance();
        double base = current > 0.0D ? current : FOLLOW_DISTANCE_DEFAULT;
        double next = base + (FOLLOW_DISTANCE_STEP * direction);
        next = MathHelper.clamp(next, FOLLOW_DISTANCE_MIN, FOLLOW_DISTANCE_MAX);
        String botTarget = formatBotTarget();
        String command = "bot follow-distance " + String.format(Locale.ROOT, "%.1f", next) + " " + botTarget;
        sendChatCommand(command);
    }

    private void adjustWoodcutTreeCount(int direction) {
        woodcutTreeCount = MathHelper.clamp(
                woodcutTreeCount + direction,
                WOODCUT_TREE_COUNT_MIN,
                WOODCUT_TREE_COUNT_MAX
        );
    }

    private void applySkillAdjust(SkillAdjustHit hit) {
        if (hit == null || hit.action() == null || hit.control() == null) {
            return;
        }
        if (hit.action() == TopicAction.SKILL_ASCENT && hit.control() == SkillAdjustControl.TOGGLE_MODE) {
            ascentSurfaceMode = !ascentSurfaceMode;
            if (ascentSurfaceMode) {
                ascentBlocks = SKILL_COUNT_UNSET;
            }
            return;
        }
        int delta = hit.control() == SkillAdjustControl.INCREMENT ? 1 : -1;
        switch (hit.action()) {
            case SKILL_FISH -> fishTargetCount = adjustOptionalCount(fishTargetCount, delta,
                    SKILL_FISH_COUNT_DEFAULT, SKILL_FISH_COUNT_MIN, SKILL_FISH_COUNT_MAX);
            case SKILL_WOOL -> woolTargetCount = adjustOptionalCount(woolTargetCount, delta,
                    SKILL_WOOL_COUNT_DEFAULT, SKILL_WOOL_COUNT_MIN, SKILL_WOOL_COUNT_MAX);
            case SKILL_STRIPMINE -> stripmineLength = adjustOptionalCount(stripmineLength, delta,
                    SKILL_STRIPMINE_COUNT_DEFAULT, SKILL_STRIPMINE_COUNT_MIN, SKILL_STRIPMINE_COUNT_MAX);
            case SKILL_ASCENT -> {
                if (ascentSurfaceMode) {
                    ascentSurfaceMode = false;
                }
                ascentBlocks = adjustOptionalCount(ascentBlocks, delta,
                        SKILL_ASCENT_COUNT_DEFAULT, SKILL_ASCENT_COUNT_MIN, SKILL_ASCENT_COUNT_MAX);
            }
            case SKILL_DESCENT -> descentBlocks = adjustOptionalCount(descentBlocks, delta,
                    SKILL_DESCENT_COUNT_DEFAULT, SKILL_DESCENT_COUNT_MIN, SKILL_DESCENT_COUNT_MAX);
            default -> {
            }
        }
    }

    private int adjustOptionalCount(int current, int direction, int defaultValue, int min, int max) {
        if (direction == 0) {
            return MathHelper.clamp(current, SKILL_COUNT_UNSET, max);
        }
        if (current <= SKILL_COUNT_UNSET) {
            return direction > 0 ? MathHelper.clamp(defaultValue, min, max) : SKILL_COUNT_UNSET;
        }
        int next = current + direction;
        if (next < min) {
            return SKILL_COUNT_UNSET;
        }
        return MathHelper.clamp(next, min, max);
    }

    private void toggleGuard() {
        String botTarget = formatBotTarget();
        String command = isGuardActive() ? "bot stop " + botTarget : "bot guard " + botTarget;
        sendChatCommand(command);
    }

    private void togglePatrol() {
        String botTarget = formatBotTarget();
        String command = isPatrolActive() ? "bot stop " + botTarget : "bot patrol " + botTarget;
        sendChatCommand(command);
    }

    private void runStop() {
        String botTarget = formatBotTarget();
        String command = "bot stop " + botTarget;
        sendChatCommand(command);
        
        // Also clear any pending shelter placement
        net.wcfcarolina13.FrensClient.clearPendingShelter();
        net.wcfcarolina13.FrensClient.clearPendingDirectionalMining();
    }

    private void runResume() {
        String botTarget = formatBotTarget();
        String command = "bot resume " + botTarget;
        sendChatCommand(command);
    }

    private void runReturnHome() {
        String botTarget = formatBotTarget();
        // Toggle: if already returning, stop; otherwise start return-to-base
        String command = isReturningToBase() ? "bot stop " + botTarget : "bot return " + botTarget;
        sendChatCommand(command);
    }

    private void runSleep() {
        String botTarget = formatBotTarget();
        String command = "bot sleep " + botTarget;
        sendChatCommand(command);
    }

    private void runCompanionCome() {
        sendChatCommand("bot companion come");
    }

    private void runCompanionSummon() {
        sendChatCommand("bot companion summon");
    }

    private void runCompanionHome() {
        sendChatCommand("bot companion home");
    }

    private void toggleAutoReturnSunset() {
        String botTarget = formatBotTarget();
        String command = "bot auto_return_sunset toggle " + botTarget;
        sendChatCommand(command);
    }

    private void toggleAutoReturnSunsetGuardPatrol() {
        String botTarget = formatBotTarget();
        String command = "bot auto_return_sunset_guard_patrol toggle " + botTarget;
        sendChatCommand(command);
    }

    private void toggleIdleHobbies() {
        String botTarget = formatBotTarget();
        String command = "bot idle_hobbies toggle " + botTarget;
        sendChatCommand(command);
    }

    private void toggleAutoHuntStarving() {
        String botTarget = formatBotTarget();
        String command = "bot auto_hunt_starving toggle " + botTarget;
        sendChatCommand(command);
    }

    private boolean isGameplayTipsActive() {
        return Frens.CONFIG == null || Frens.CONFIG.isGameplayTipsEnabled();
    }

    private void toggleGameplayTips() {
        if (Frens.CONFIG == null) {
            return;
        }
        Frens.CONFIG.setGameplayTipsEnabled(!Frens.CONFIG.isGameplayTipsEnabled());
        Frens.CONFIG.save();
    }

    private boolean isIdleHobbiesAnywhereActive() {
        return Frens.CONFIG != null && Frens.CONFIG.isIdleHobbiesAnywhereEnabled();
    }

    private void toggleIdleHobbiesAnywhere() {
        if (Frens.CONFIG == null) {
            return;
        }
        Frens.CONFIG.setIdleHobbiesAnywhereEnabled(!Frens.CONFIG.isIdleHobbiesAnywhereEnabled());
        Frens.CONFIG.save();
    }

    private boolean isBaritonePathfinderActive() {
        return Frens.CONFIG != null && Frens.CONFIG.isBaritonePathfinderEnabled();
    }

    private void toggleBaritonePathfinder() {
        if (Frens.CONFIG == null) return;
        boolean newValue = !Frens.CONFIG.isBaritonePathfinderEnabled();
        Frens.CONFIG.setBaritonePathfinderEnabled(newValue);
        Frens.CONFIG.save();
        net.wcfcarolina13.PathFinding.PathFinder.USE_BARITONE_STYLE = newValue;
    }

    private boolean isUnleashTetheredActive() {
        return this.handler != null && this.handler.isBotUnleashTetheredEnabled();
    }

    private void toggleUnleashTethered() {
        String botTarget = formatBotTarget();
        String command = "bot unleash_tethered toggle " + botTarget;
        sendChatCommand(command);
    }

    private boolean isLeashOnDismountActive() {
        return this.handler != null && this.handler.isBotLeashOnDismountEnabled();
    }

    private void toggleLeashOnDismount() {
        String botTarget = formatBotTarget();
        String command = "bot leash_on_dismount toggle " + botTarget;
        sendChatCommand(command);
    }

    private void openBasesManager() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new BaseManagerScreen(this, botAlias));
    }

    private void openCraftingHistory() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new CraftingHistoryScreen(this));
    }

    private void openCookingMenu() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new CookablesScreen(this, formatBotTarget()));
    }

    private void openHuntingMenu() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new HuntablesScreen(this, formatBotTarget()));
    }

    private void openConstructionMenu() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new ConstructionScreen(this, formatBotTarget()));
    }

    private void openSpellsMenu() {
        if (this.client == null) {
            return;
        }
        if (this.client.player != null) {
            this.client.player.playSound(SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, 0.28f, 1.8f);
            this.client.player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f, 1.4f);
        }
        this.client.setScreen(new CompanionSpellsScreen(this, this.botAlias));
    }

    private void openGuideMenu() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new BotGuideScreen(this, this.botAlias));
    }

    private void openBotControls() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new BotControlScreen(this));
    }

    /**
     * Closes the screen and sends a shelter command with @look flag.
     * The server will use the player's current look direction to determine placement.
     * For hovel: centered where player looks
     * For burrow: digs in the direction player looks
     */
    private void runShelterWithLook(String shelterType) {
        if (this.client == null) {
            return;
        }
        String botTarget = formatBotTarget();
        // Set the pending shelter type - will be used when player presses go_to_look keybind
        net.wcfcarolina13.FrensClient.setPendingShelter(shelterType, botTarget);
        // Close the screen first so player can see where they're looking
        this.close();
        // Show instruction message for 2.5 seconds (action bar messages fade quickly, so we repeat)
        if (this.client.player != null) {
            String message = "Look where you want the " + shelterType + " and press your 'Go To Look' keybind";
            net.minecraft.text.Text msgText = net.minecraft.text.Text.literal(message);
            // Send immediately
            this.client.player.sendMessage(msgText, true);
            // Schedule repeats at 0.5s, 1.0s, 1.5s, 2.0s to keep message visible for ~2.5s
            java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            final net.minecraft.client.MinecraftClient clientRef = this.client;
            for (int delayMs : new int[]{500, 1000, 1500, 2000}) {
                scheduler.schedule(() -> {
                    if (clientRef.player != null) {
                        clientRef.execute(() -> clientRef.player.sendMessage(msgText, true));
                    }
                }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            scheduler.shutdown();
        }
    }

    private void sendChatCommand(String command) {
        if (this.client == null || this.client.getNetworkHandler() == null) {
            return;
        }
        String raw = command.startsWith("/") ? command.substring(1) : command;
        this.client.getNetworkHandler().sendChatCommand(raw);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        this.drawEntities(context, mouseX, mouseY);
        if (topicsExpanded) {
            drawTopicsOverlay(context, mouseX, mouseY);
        }
        if (!topicsExpanded || !isMouseOverTopicsOverlay(mouseX, mouseY)) {
            this.drawMouseoverTooltip(context, mouseX, mouseY);
        }
    }

    private void drawEntities(DrawContext context, int mouseX, int mouseY) {
        int leftX1 = this.x + 26;
        int leftY1 = this.y + 8;
        int leftX2 = this.x + 75;
        int leftY2 = this.y + 78;
        int rightX1 = this.x + SECTION_WIDTH + BLOCK_GAP + 26;
        int rightY1 = this.y + 8;
        int rightX2 = this.x + SECTION_WIDTH + BLOCK_GAP + 75;
        int rightY2 = this.y + 78;
        LivingEntity botEntity = findBotEntity();
        if (botEntity != null) {
            InventoryScreen.drawEntity(context, leftX1, leftY1, leftX2, leftY2,
                    30, 0.0625f, this.lastMouseX, this.lastMouseY, botEntity);
        }
        if (this.client != null && this.client.player != null) {
            InventoryScreen.drawEntity(context, rightX1, rightY1, rightX2, rightY2,
                    30, 0.0625f, this.lastMouseX, this.lastMouseY, this.client.player);
        }
    }

    private LivingEntity findBotEntity() {
        if (this.client == null || this.client.world == null) return null;
        String raw = this.title.getString();
        int idx = raw.indexOf("'s Inventory");
        if (idx <= 0) return null;
        String name = raw.substring(0, idx);
        for (AbstractClientPlayerEntity player : this.client.world.getPlayers()) {
            if (player.getGameProfile().name().equals(name)) {
                return player;
            }
        }
        if (this.client.player == null) return null;
        if (fallbackBot == null || !fallbackBot.getGameProfile().name().equals(name)) {
            GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("bot:" + name).getBytes(StandardCharsets.UTF_8)), name);
            fallbackBot = new OtherClientPlayerEntity(this.client.world, profile);
            fallbackBot.copyPositionAndRotation(this.client.player);
        }
        fallbackBot.tick();
        return fallbackBot;
    }

    private static String extractAlias(String raw) {
        int idx = raw.indexOf("'s Inventory");
        if (idx <= 0) return raw;
        return raw.substring(0, idx);
    }
}
