package net.wcfcarolina13.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Vanilla's listed-player skin path can reject unsigned packed texture
 * properties. Our bot skins are intentionally unsigned (preset CDN URLs),
 * so this mixin provides a fallback render path for those profiles by using
 * {@code supplySkinTextures(profile, false)}.
 *
 * <p>Scope is intentionally narrow: it only activates when a profile has a
 * {@code textures} property and that property has no signature.
 */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntitySkinMixin {

    private static final ConcurrentHashMap<String, Supplier<SkinTextures>> UNSIGNED_SKIN_CACHE =
            new ConcurrentHashMap<>();

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void frens$resolveUnsignedTextures(CallbackInfoReturnable<SkinTextures> cir) {
        GameProfile profile = ((AbstractClientPlayerEntity) (Object) this).getGameProfile();
        if (profile == null || profile.properties() == null) return;

        Collection<Property> textures = profile.properties().get("textures");
        if (textures == null || textures.isEmpty()) return;

        Property textureProp = textures.iterator().next();
        if (textureProp == null || textureProp.value() == null || textureProp.value().isBlank()) return;

        // Signed properties should keep vanilla's secure path.
        if (textureProp.hasSignature()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getSkinProvider() == null) return;

        String key = (profile.id() != null ? profile.id().toString() : "no-uuid")
                + ":" + textureProp.value().hashCode();

        Supplier<SkinTextures> supplier = UNSIGNED_SKIN_CACHE.computeIfAbsent(
                key,
                ignored -> client.getSkinProvider().supplySkinTextures(profile, false)
        );

        SkinTextures resolved = supplier.get();
        if (resolved != null) {
            cir.setReturnValue(resolved);
        }
    }
}
