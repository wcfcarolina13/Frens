package net.shasankp000.GraphicalUserInterface;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.shasankp000.AIPlayerClient;
import net.shasankp000.items.ModItems;

import java.util.Locale;

/**
 * Spell-like companion commands window.
 *
 * <p>Access is intended to be gated by proximity to an Enchanting Table. A later-stage quest reward
 * (the "Wizard's Tome") can allow access anywhere.</p>
 */
public class CompanionSpellsScreen extends Screen {

    private final Screen parent;
    private final String botAlias;

    private ButtonWidget comeBtn;
    private ButtonWidget summonBtn;
    private ButtonWidget homeBtn;

    private static final class AccessState {
        final boolean full;
        final boolean eye;
        final boolean horn;

        private AccessState(boolean full, boolean eye, boolean horn) {
            this.full = full;
            this.eye = eye;
            this.horn = horn;
        }
    }

    public CompanionSpellsScreen(Screen parent, String botAlias) {
        super(Text.literal("Spells"));
        this.parent = parent;
        this.botAlias = botAlias != null ? botAlias : "";
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = 40;

        int w = 160;
        int h = 20;
        int gap = 6;

        comeBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal("Come"), (btn) -> sendSpell("bot companion come"))
                .dimensions(cx - w / 2, top, w, h)
                .build());

        summonBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal("Summon"), (btn) -> castSummon())
                .dimensions(cx - w / 2, top + (h + gap), w, h)
                .build());

        homeBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal("Home"), (btn) -> sendSpell("bot companion home"))
                .dimensions(cx - w / 2, top + 2 * (h + gap), w, h)
                .build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"), (btn) -> close())
                .dimensions(cx - w / 2, top + 3 * (h + gap) + 10, w, h)
                .build());

        refreshEnabledState();
    }

    @Override
    public void tick() {
        super.tick();
        refreshEnabledState();
    }

    private void refreshEnabledState() {
        AccessState state = getAccessState();
        boolean eyeReady = state.eye && !AIPlayerClient.isEyeSpellOnCooldown();
        if (comeBtn != null) comeBtn.active = state.full || state.horn;
        if (summonBtn != null) summonBtn.active = state.full || eyeReady;
        if (homeBtn != null) homeBtn.active = state.full;
    }

    private AccessState getAccessState() {
        MinecraftClient client = this.client;
        if (client == null || client.player == null) {
            return new AccessState(false, false, false);
        }

        boolean full = isNearEnchantingTable(client, 4) || hasSpellbookToken(client);
        boolean eye = !full && hasEyeOfEnderToken(client);
        boolean horn = !full && hasGoatHornToken(client);
        return new AccessState(full, eye, horn);
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

    private boolean hasEyeOfEnderToken(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        var inv = client.player.getInventory();
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

    private boolean hasGoatHornToken(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        var inv = client.player.getInventory();
        int n = inv.size();
        for (int i = 0; i < n; i++) {
            var stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (stack.isOf(Items.GOAT_HORN)) {
                return true;
            }
        }
        return false;
    }

    private void castSummon() {
        AccessState state = getAccessState();
        // When full access is unavailable, Eye of Ender enables summon-only with cooldown.
        if (!state.full && state.eye) {
            if (AIPlayerClient.isEyeSpellOnCooldown()) {
                // Server will also reject; keep UI snappy with a local hint.
                long sec = Math.max(1L, AIPlayerClient.getEyeSpellCooldownRemainingMs() / 1000L);
                if (this.client != null && this.client.player != null) {
                    this.client.player.sendMessage(Text.literal("Eye of Ender spell is on cooldown (" + sec + "s)."), true);
                }
                return;
            }
            // Arm local cooldown for UX; server remains authoritative.
            AIPlayerClient.armEyeSpellCooldown();
        }
        sendSpell("bot companion summon");
    }

    private void sendSpell(String command) {
        MinecraftClient client = this.client;
        if (client == null || client.getNetworkHandler() == null) {
            return;
        }
        String raw = command.startsWith("/") ? command.substring(1) : command;
        client.getNetworkHandler().sendChatCommand(raw);
    }

    @Override
    public void close() {
        MinecraftClient client = this.client;
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int cx = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 14, 0xFFFFFF);

        String who = (botAlias != null && !botAlias.isBlank()) ? ("Companion: " + botAlias) : "Companion spells";
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(who), cx, 26, 0xFFB0B0B0);

        AccessState state = getAccessState();
        String hint;
        if (state.full) {
            hint = "Full access: Enchanting Table or Wizard's Tome.";
        } else if (state.eye && state.horn) {
            if (AIPlayerClient.isEyeSpellOnCooldown()) {
                long sec = Math.max(1L, AIPlayerClient.getEyeSpellCooldownRemainingMs() / 1000L);
                hint = "Partial access: Horn (come-only) + Eye (summon-only, cooldown " + sec + "s).";
            } else {
                hint = "Partial access: Horn (come-only) + Eye (summon-only).";
            }
        } else if (state.horn) {
            hint = "Goat Horn access: Come only.";
        } else if (state.eye) {
            if (AIPlayerClient.isEyeSpellOnCooldown()) {
                long sec = Math.max(1L, AIPlayerClient.getEyeSpellCooldownRemainingMs() / 1000L);
                hint = "Eye of Ender access: Summon only (cooldown " + sec + "s).";
            } else {
                hint = "Eye of Ender access: Summon only (cooldown after cast).";
            }
        } else {
            hint = "Requires Enchanting Table, Wizard's Tome, Goat Horn, or Eye of Ender.";
        }
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(hint), cx, this.height - 28, 0xFFB0B0B0);
    }
}
