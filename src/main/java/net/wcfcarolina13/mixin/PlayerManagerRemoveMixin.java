package net.wcfcarolina13.mixin;

import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.GameAI.services.BotPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// On a singleplayer quit IntegratedServer.stop calls PlayerManager.remove for every non-host
// player before SERVER_STOPPING fires. Vanilla then writes only the .dat, but fake-player items
// live in the Frens snapshot, so save that first or the last 30 s autosave is what survives.
@Mixin(PlayerManager.class)
public abstract class PlayerManagerRemoveMixin {
    private static final Logger FRENS$LOGGER = LoggerFactory.getLogger("bot-persistence");

    @Inject(method = "remove(Lnet/minecraft/server/network/ServerPlayerEntity;)V", at = @At("HEAD"))
    private void frens$saveBotBeforeVanillaRemove(ServerPlayerEntity player, CallbackInfo ci) {
        if (!(player instanceof createFakePlayer)) {
            return;
        }
        try {
            BotPersistenceService.onVanillaRemove(player);
        } catch (Throwable t) {
            // Never break vanilla removal over a Frens save failure.
            FRENS$LOGGER.warn("Vanilla removal save failed for fakeplayer '{}': {}",
                    player.getName().getString(), t.toString());
        }
    }
}
