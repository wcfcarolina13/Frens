package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.wcfcarolina13.network.BotEnchantOpenPayload;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.FrensClient;
import net.wcfcarolina13.items.ModItems;

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
    private ButtonWidget openInvBtn;
    private ButtonWidget enchantBtn;

    private static final class AccessState {
        final boolean full;
        final boolean eye;
        final boolean horn;
        final boolean playerHasPearl;
        final boolean playerHasChorus;
        final int botNavTier; // 0=NONE, 1=BASIC, 2=ENHANCED

        private AccessState(boolean full, boolean eye, boolean horn,
                            boolean playerHasPearl, boolean playerHasChorus, int botNavTier) {
            this.full = full;
            this.eye = eye;
            this.horn = horn;
            this.playerHasPearl = playerHasPearl;
            this.playerHasChorus = playerHasChorus;
            this.botNavTier = botNavTier;
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

        comeBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal("Regroup"), (btn) -> sendSpell(withBotAlias("bot companion come")))
                .dimensions(cx - w / 2, top, w, h)
                .build());

        summonBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal("Summon"), (btn) -> castSummon())
                .dimensions(cx - w / 2, top + (h + gap), w, h)
                .build());

        homeBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal("Home"), (btn) -> sendSpell(withBotAlias("bot companion home")))
                .dimensions(cx - w / 2, top + 2 * (h + gap), w, h)
                .build());

        openInvBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal("Remote Inventory"), (btn) -> sendSpell(withBotAlias("bot companion open")))
                .dimensions(cx - w / 2, top + 3 * (h + gap), w, h)
                .build());

        enchantBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal("Enchant"), (btn) -> openBotEnchanting())
                .dimensions(cx - w / 2, top + 4 * (h + gap), w, h)
                .build());

        comeBtn.setTooltip(Tooltip.of(Text.literal("Call your companion to your location.\nRequires: Enchanting Table or Wizard's Tome, or Goat Horn.")));
        summonBtn.setTooltip(Tooltip.of(Text.literal("Teleport your companion directly to you.\nRequires: Enchanting Table or Wizard's Tome, or Eye of Ender (60s cooldown).")));
        homeBtn.setTooltip(Tooltip.of(Text.literal("Send your companion back to their home base.\nRequires: Navigation tier 1+ (Map on bot) or higher.")));
        openInvBtn.setTooltip(Tooltip.of(Text.literal("Open your companion's inventory remotely.\nRequires: Enchanting Table or Wizard's Tome (full access).")));
        enchantBtn.setTooltip(Tooltip.of(Text.literal("Open the enchanting table for your companion.\nUses the bot's XP, lapis, and inventory.\nRequires: nearby Enchanting Table.")));

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"), (btn) -> close())
                .dimensions(cx - w / 2, top + 5 * (h + gap) + 10, w, h)
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
        boolean eyeReady = state.eye && !FrensClient.isEyeSpellOnCooldown();
        if (comeBtn != null) comeBtn.active = state.full || state.horn;
        if (summonBtn != null) summonBtn.active = state.full || eyeReady;
        if (homeBtn != null) homeBtn.active = state.full || state.botNavTier >= 1;
        if (openInvBtn != null) openInvBtn.active = state.full;
        if (enchantBtn != null) enchantBtn.active = isNearEnchantingTable(this.client, 4);
    }

    private AccessState getAccessState() {
        MinecraftClient client = this.client;
        if (client == null || client.player == null) {
            return new AccessState(false, false, false, false, false, 0);
        }

        boolean full = isNearEnchantingTable(client, 4) || hasSpellbookToken(client);
        boolean eye = !full && hasEyeOfEnderToken(client);
        boolean horn = !full && hasGoatHornToken(client);
        boolean playerHasPearl = hasEnderPearlInInventory(client);
        boolean playerHasChorus = playerHasPearl && hasChorusFruitInInventory(client);
        int botNavTier = FrensClient.getCachedBotNavTier();
        return new AccessState(full, eye, horn, playerHasPearl, playerHasChorus, botNavTier);
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

    private boolean hasEnderPearlInInventory(MinecraftClient client) {
        if (client == null || client.player == null) return false;
        var inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack != null && !stack.isEmpty() && stack.isOf(Items.ENDER_PEARL)) return true;
        }
        return false;
    }

    private boolean hasChorusFruitInInventory(MinecraftClient client) {
        if (client == null || client.player == null) return false;
        var inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack != null && !stack.isEmpty() && stack.isOf(Items.CHORUS_FRUIT)) return true;
        }
        return false;
    }

    private void castSummon() {
        AccessState state = getAccessState();
        // When full access is unavailable, Eye of Ender enables summon-only with cooldown.
        if (!state.full && state.eye) {
            if (FrensClient.isEyeSpellOnCooldown()) {
                // Server will also reject; keep UI snappy with a local hint.
                long sec = Math.max(1L, FrensClient.getEyeSpellCooldownRemainingMs() / 1000L);
                if (this.client != null && this.client.player != null) {
                    this.client.player.sendMessage(Text.literal("Eye of Ender spell is on cooldown (" + sec + "s)."), true);
                }
                return;
            }
            // Arm local cooldown for UX; server remains authoritative.
            FrensClient.armEyeSpellCooldown();
        }
        sendSpell(withBotAlias("bot companion summon"));
    }

    private void openBotEnchanting() {
        MinecraftClient client = this.client;
        if (client == null || client.getNetworkHandler() == null) {
            return;
        }
        ClientPlayNetworking.send(new BotEnchantOpenPayload(botAlias));
        close();
    }

    private void sendSpell(String command) {
        MinecraftClient client = this.client;
        if (client == null || client.getNetworkHandler() == null) {
            return;
        }
        String raw = command.startsWith("/") ? command.substring(1) : command;
        client.getNetworkHandler().sendChatCommand(raw);
    }

    private String withBotAlias(String base) {
        String b = base == null ? "" : base.trim();
        if (b.isBlank()) {
            return b;
        }
        String alias = (botAlias == null) ? "" : botAlias.trim();
        if (alias.isBlank()) {
            return b;
        }
        if (alias.contains(" ")) {
            return b + " \"" + alias + "\"";
        }
        return b + " " + alias;
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
            hint = "Partial access: Goat Horn (regroup) + Eye of Ender (summon).";
        } else if (state.horn) {
            hint = "Goat Horn access: Regroup only.";
        } else if (state.eye) {
            hint = "Eye of Ender access: Summon only.";
        } else {
            hint = "Companion actions. Spells are in the Spells tab.";
        }
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(hint), cx, this.height - 28, 0xFFB0B0B0);
    }
}
