package net.shasankp000.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.screen.slot.CraftingResultSlot;
import org.spongepowered.asm.mixin.Final;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.services.CraftingHistoryService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CraftingResultSlot.class)
public class CraftingResultSlotMixin {

    @Shadow @Final private PlayerEntity player;

    @Inject(method = "onTakeItem", at = @At("HEAD"))
    private void recordCraftingHistory(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
        recordForPlayer(player, stack);
    }

    @Inject(method = "onCrafted(Lnet/minecraft/item/ItemStack;)V", at = @At("HEAD"), require = 0)
    private void recordCraftingHistoryCrafted(ItemStack stack, CallbackInfo ci) {
        recordForPlayer(this.player, stack);
    }

    @Inject(method = "onQuickCraft(Lnet/minecraft/item/ItemStack;I)V", at = @At("HEAD"), require = 0)
    private void recordCraftingHistoryQuick(ItemStack stack, int amount, CallbackInfo ci) {
        recordForPlayer(this.player, stack);
    }

    private void recordForPlayer(PlayerEntity player, ItemStack stack) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }
        if (BotEventHandler.isRegisteredBot(serverPlayer)) {
            return;
        }
        ItemStack result = stack;
        if (result == null || result.isEmpty() || result.isOf(Items.AIR)) {
            result = ((CraftingResultSlot) (Object) this).getStack();
        }
        if (result == null || result.isEmpty() || result.isOf(Items.AIR)) {
            return;
        }
        Identifier id = Registries.ITEM.getId(result.getItem());
        if (id != null) {
            CraftingHistoryService.recordCraft(serverPlayer, id);
        }
    }
}
