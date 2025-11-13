package net.shasankp000.ui;

import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;

public final class BotInventoryAccess {
    private BotInventoryAccess() {}

    public static boolean openMain(ServerPlayerEntity viewer, ServerPlayerEntity bot) {
        if (viewer == null || bot == null) return false;
        if (viewer.getEntityWorld() != bot.getEntityWorld()) return false;
        if (viewer.squaredDistanceTo(bot) > 64.0) return false;
        // TODO: ownership/op check (see section 3)
        var invView = new BotMainInventoryView(bot);
        viewer.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, playerInv, player) ->
                GenericContainerScreenHandler.createGeneric9x3(syncId, playerInv, invView),
            Text.literal(bot.getName().getString() + "'s Inventory")
        ));
        return true;
    }

    // v2: Full inventory (armor 36..39, offhand 40, main 9..35, hotbar 0..8)
    public static boolean openFull(net.minecraft.server.network.ServerPlayerEntity viewer,
                                   net.minecraft.server.network.ServerPlayerEntity bot) {
        if (viewer == null || bot == null) return false;
        if (viewer.getEntityWorld() != bot.getEntityWorld()) return false;
        if (viewer.squaredDistanceTo(bot) > 64.0) return false;

        viewer.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, player) ->
                        new BotInventoryScreenHandler(syncId, playerInv, bot.getInventory(), bot),
                net.minecraft.text.Text.literal(bot.getName().getString() + "'s Inventory")
        ));
        return true;
    }

    /** Exposes bot armor/offhand/main/hotbar; real bot inventory, no copies. */
    public static class BotInventoryScreenHandler extends net.minecraft.screen.ScreenHandler {
        private final Inventory botInv;
        private final net.minecraft.server.network.ServerPlayerEntity botRef;

        // Client factory required by ScreenHandlerType; server will sync contents.
        public static BotInventoryScreenHandler clientFactory(int syncId,
                                                              net.minecraft.entity.player.PlayerInventory viewerInv) {
            // Client has no access to the bot's PlayerInventory; use a temporary 41-slot inventory.
            return new BotInventoryScreenHandler(syncId, viewerInv, new SimpleInventory(41), null);
        }

        public BotInventoryScreenHandler(int syncId,
                                         net.minecraft.entity.player.PlayerInventory viewerInv,
                                         Inventory botInv,
                                         net.minecraft.server.network.ServerPlayerEntity botRef) {
            super(net.shasankp000.AIPlayer.BOT_INV_HANDLER, syncId);
            this.botInv = botInv;
            this.botRef = botRef;

            int x0 = 8;
            int yTop = 18;

            // Armor (36..39) + Offhand (40)
            this.addSlot(new Slot(botInv, 39, x0, yTop)); // Corrected index for head
            this.addSlot(new Slot(botInv, 38, x0 + 18, yTop)); // Corrected index for chest
            this.addSlot(new Slot(botInv, 37, x0 + 36, yTop)); // Corrected index for legs
            this.addSlot(new Slot(botInv, 36, x0 + 54, yTop)); // Corrected index for feet
            this.addSlot(new net.minecraft.screen.slot.Slot(botInv, 40, x0 + 90, yTop)); // offhand (index 40)

            // Main (9..35) — 3 rows x 9
            int y0 = yTop + 22;
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new net.minecraft.screen.slot.Slot(
                            botInv, 9 + row * 9 + col, x0 + col * 18, y0 + row * 18
                    ));
                }
            }

            // Hotbar (0..8)
            int yHot = y0 + 3 * 18 + 4;
            for (int i = 0; i < 9; i++) {
                this.addSlot(new net.minecraft.screen.slot.Slot(botInv, i, x0 + i * 18, yHot));
            }

            // Viewer inventory (3 rows + hotbar)
            int yPlayer = yHot + 28;
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new net.minecraft.screen.slot.Slot(
                            viewerInv, 9 + row * 9 + col, x0 + col * 18, yPlayer + row * 18
                    ));
                }
            }
            for (int i = 0; i < 9; i++) {
                this.addSlot(new net.minecraft.screen.slot.Slot(viewerInv, i, x0 + i * 18, yPlayer + 58));
            }
        }

        @Override
        public boolean canUse(net.minecraft.entity.player.PlayerEntity player) {
            if (botRef == null) return true; // client-side or no ref; server will enforce
            return player.getEntityWorld() == botRef.getEntityWorld() && player.squaredDistanceTo(botRef) <= 64.0;
        }

        // Shift-click routing
        @Override
        public net.minecraft.item.ItemStack quickMove(net.minecraft.entity.player.PlayerEntity player, int index) {
            net.minecraft.item.ItemStack newStack = net.minecraft.item.ItemStack.EMPTY;
            net.minecraft.screen.slot.Slot slot = this.slots.get(index);
            if (slot != null && slot.hasStack()) {
                net.minecraft.item.ItemStack original = slot.getStack();
                newStack = original.copy();

                int botCount = 5 + 27 + 9; // armor+offhand(5) + main(27) + hotbar(9) = 41
                int viewerStart = botCount;
                int viewerCount = 36;

                if (index < botCount) {
                    // bot -> viewer
                    if (!this.insertItem(original, viewerStart, viewerStart + viewerCount, true))
                        return net.minecraft.item.ItemStack.EMPTY;
                } else {
                    // viewer -> bot (main+hotbar only)
                    int botMainStart = 5;
                    int botMainCount = 27 + 9;
                    if (!this.insertItem(original, botMainStart, botMainStart + botMainCount, false))
                        return net.minecraft.item.ItemStack.EMPTY;
                }

                if (original.isEmpty()) slot.setStack(net.minecraft.item.ItemStack.EMPTY);
                else slot.markDirty();

                if (original.getCount() == newStack.getCount()) return net.minecraft.item.ItemStack.EMPTY;
                slot.onTakeItem(player, original);
            }
            return newStack;
        }
    }
}
