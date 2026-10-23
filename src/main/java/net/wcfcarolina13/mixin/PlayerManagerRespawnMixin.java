package net.wcfcarolina13.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.wcfcarolina13.Entity.createFakePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// @WrapOperation, not a redirect: Carpet redirects this same constructor for its own fake players,
// and two redirects on one instruction crash at class load. Wrappers chain with a redirect.
@Mixin(PlayerManager.class)
public abstract class PlayerManagerRespawnMixin {
    @WrapOperation(
            method = "respawnPlayer(Lnet/minecraft/server/network/ServerPlayerEntity;ZLnet/minecraft/entity/Entity$RemovalReason;)Lnet/minecraft/server/network/ServerPlayerEntity;",
            at = @At(value = "NEW", target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/world/ServerWorld;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/network/packet/c2s/common/SyncedClientOptions;)Lnet/minecraft/server/network/ServerPlayerEntity;"),
            require = 1,
            allow = 1)
    private ServerPlayerEntity frens$createRespawnReplacement(MinecraftServer server, ServerWorld world,
                                                             GameProfile profile, SyncedClientOptions options,
                                                             Operation<ServerPlayerEntity> original,
                                                             @Local(argsOnly = true) ServerPlayerEntity oldPlayer) {
        if (oldPlayer instanceof createFakePlayer fake) {
            return fake.createRespawnReplacement(server, world, profile, options);
        }
        return original.call(server, world, profile, options);
    }
}
