package net.wcfcarolina13.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import net.wcfcarolina13.FrensClient;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link FrensClient} consume HUD-level mouse clicks (clicks made when no
 * Screen is open) so the dismissible "no companions present" hint can be closed
 * with the X button.  Without this, mouse events go straight to the player
 * (attack/use) and never reach the HUD overlay.
 */
@Mixin(Mouse.class)
public abstract class MouseHudClickMixin {

    @Shadow @Final private MinecraftClient client;

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void frens$interceptHudClick(long window, MouseInput input, int action, CallbackInfo ci) {
        if (action != 1 /* GLFW_PRESS */) return;
        if (client == null || client.currentScreen != null) return;
        // Use the live cursor position from the Mouse so we don't rely on stale state.
        Mouse self = (Mouse) (Object) this;
        if (FrensClient.handleHudClick(self.getX(), self.getY(), input.button())) {
            ci.cancel();
        }
    }
}
