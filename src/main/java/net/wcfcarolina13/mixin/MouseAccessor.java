package net.wcfcarolina13.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for {@link Mouse} private fields.
 *
 * <p>MC's own {@code Mouse.unlockCursor()} directly writes the {@code x/y}
 * fields before calling {@code GLFW.glfwSetCursorPos}, because the GLFW
 * callback ({@code onCursorPos}) only fires on the <em>next</em>
 * {@code glfwPollEvents}. Without direct field access the bot-switch cursor
 * restore in {@code BotPlayerInventoryScreen.init()} would leave
 * {@code Mouse.x/y} at the screen centre for one frame, causing the first
 * click after a switch to resolve to wrong coordinates.
 */
@Mixin(Mouse.class)
public interface MouseAccessor {

    @Accessor("x")
    void setX(double x);

    @Accessor("y")
    void setY(double y);
}
