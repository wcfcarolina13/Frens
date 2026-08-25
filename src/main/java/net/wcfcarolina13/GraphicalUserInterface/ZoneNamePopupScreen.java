package net.wcfcarolina13.GraphicalUserInterface;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.FrensClient;
import net.wcfcarolina13.network.ZoneConfirmPayload;

/** Small popup for naming a new protected zone after wand selection. */
public class ZoneNamePopupScreen extends Screen {

    private static final int POPUP_WIDTH = 200;
    private static final int POPUP_HEIGHT = 100;

    private TextFieldWidget nameField;

    public ZoneNamePopupScreen() {
        super(Text.literal("\u00A7bName this zone"));
    }

    @Override
    protected void init() {
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;

        nameField = new TextFieldWidget(this.textRenderer, cx + 10, cy + 30, POPUP_WIDTH - 20, 20,
                Text.literal("Zone name"));
        nameField.setMaxLength(32);
        nameField.setFocused(true);
        addSelectableChild(nameField);
        setInitialFocus(nameField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Confirm"), button -> confirmZone())
                .dimensions(cx + 10, cy + 60, 85, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> cancelZone())
                .dimensions(cx + POPUP_WIDTH - 95, cy + 60, 85, 20).build());
    }

    private void confirmZone() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) return;

        BlockPos pos1 = FrensClient.getZonePos1();
        BlockPos pos2 = FrensClient.getZonePos2();
        if (pos1 == null || pos2 == null) {
            cancelZone();
            return;
        }

        ClientPlayNetworking.send(new ZoneConfirmPayload(
                pos1.getX(), pos1.getY(), pos1.getZ(),
                pos2.getX(), pos2.getY(), pos2.getZ(),
                name));
        FrensClient.clearZoneWandState();
        close();
    }

    private void cancelZone() {
        FrensClient.clearZoneWandState();
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;

        // Border (1px cyan) then dark interior
        context.fill(cx - 1, cy - 1, cx + POPUP_WIDTH + 1, cy + POPUP_HEIGHT + 1, 0xFF00CCCC);
        context.fill(cx, cy, cx + POPUP_WIDTH, cy + POPUP_HEIGHT, 0xCC222222);

        // Title
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, cy + 10, 0xFFFFFFFF);

        nameField.render(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input != null ? input.key() : -1;
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            confirmZone();
            return true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            cancelZone();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
