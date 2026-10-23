package net.wcfcarolina13.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.wcfcarolina13.Entity.createFakePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerManager.class)
public abstract class PlayerManagerRespawnMixin {
    @Redirect(
            method = "respawnPlayer(Lnet/minecraft/server/network/ServerPlayerEntity;ZLnet/minecraft/entity/Entity$RemovalReason;)Lnet/minecraft/server/network/ServerPlayerEntity;",
            at = @At(value = "NEW", target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/world/ServerWorld;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/network/packet/c2s/common/SyncedClientOptions;)Lnet/minecraft/server/network/ServerPlayerEntity;"),
            require = 1,
            allow = 1)
    private ServerPlayerEntity frens$createRespawnReplacement(MinecraftServer server, ServerWorld world,
                                                             GameProfile profile, SyncedClientOptions options,
                                                             ServerPlayerEntity oldPlayer, boolean alive,
                                                             Entity.RemovalReason removalReason) {
        if (oldPlayer instanceof createFakePlayer fake) {
            return fake.createRespawnReplacement(server, world, profile, options);
        }
        return new ServerPlayerEntity(server, world, profile, options);
    }
}
