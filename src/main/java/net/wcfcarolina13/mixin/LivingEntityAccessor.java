package net.wcfcarolina13.mixin;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link LivingEntity}'s protected {@code dead} field for fake-player
 * respawn recovery.  Paired with {@link ServerPlayNetworkHandlerAccessor} to
 * fully reset death-related state on bots that "die" without an actual
 * vanilla respawn cycle.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {

    @Accessor("dead")
    void setDead(boolean dead);
}
