package net.wcfcarolina13.mixin;

import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link ServerPlayNetworkHandler}'s private {@code dead} field for
 * fake-player respawn recovery.
 *
 * <p>When a fake player "dies", {@code createFakePlayer.kill()} schedules
 * {@code networkHandler.disconnect()}, which sets {@code dead = true} on the
 * handler.  {@code ServerPlayerEntity.isInvulnerableTo()} then treats the
 * player as invulnerable via {@code canInteractWithGame()} for the rest of
 * the session, blocking all damage.  Our forced-respawn path resets this
 * flag to restore normal damage processing.</p>
 */
@Mixin(ServerPlayNetworkHandler.class)
public interface ServerPlayNetworkHandlerAccessor {

    @Accessor("dead")
    void setDead(boolean dead);
}
