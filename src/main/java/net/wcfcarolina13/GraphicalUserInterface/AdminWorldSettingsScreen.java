package net.wcfcarolina13.GraphicalUserInterface;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.wcfcarolina13.network.AdminMaxBaseRadiusPayload;

/**
 * Admin-only screen for world-wide frens settings. Currently hosts:
 *
 * <ul>
 *   <li>Max user-settable base radius (integer, bounded by server's absolute ceiling)</li>
 * </ul>
 *
 * <p>Opens from the Admin tab inside {@link BotPlayerInventoryScreen}. When shown, the screen
 * queries the server (value &lt; 0 on {@link AdminMaxBaseRadiusPayload}) and the resulting
 * state payload is applied via {@link #applyState(int, int)} from the client networking
 * handler. The user can then edit the value and press "Apply" to push it back.
 */
public class AdminWorldSettingsScreen extends Screen {

    /** Kept accessible so the FrensClient S2C receiver can push state into the active screen. */
    private static AdminWorldSettingsScreen INSTANCE;

    private final Screen parent;

    private int currentMaxRadius = -1;       // -1 = unknown until server responds
    private int hardLimit = 256;             // safe default until server responds
    private TextFieldWidget radiusField;
    private ButtonWidget applyButton;
    private ButtonWidget doneButton;
    private String statusMessage = "Requesting current value from server...";

    public AdminWorldSettingsScreen(Screen parent) {
        super(Text.literal("World Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        INSTANCE = this;

        int centerX = this.width / 2;
        int fieldY = this.height / 2 - 10;

        this.radiusField = new TextFieldWidget(this.textRenderer, centerX - 40, fieldY, 80, 20,
                Text.literal("Max base radius"));
        this.radiusField.setMaxLength(5);
        this.radiusField.setText(currentMaxRadius > 0 ? Integer.toString(currentMaxRadius) : "");
        this.radiusField.setChangedListener(s -> updateApplyState());
        this.addDrawableChild(this.radiusField);

        this.applyButton = ButtonWidget.builder(Text.literal("Apply"), b -> onApply())
                .dimensions(centerX - 80, fieldY + 30, 75, 20)
                .build();
        this.addDrawableChild(this.applyButton);

        this.doneButton = ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(centerX + 5, fieldY + 30, 75, 20)
                .build();
        this.addDrawableChild(this.doneButton);

        // Fire a query to the server; the S2C state handler will call applyState() when it arrives.
        ClientPlayNetworking.send(new AdminMaxBaseRadiusPayload(-1));
        updateApplyState();
    }

    private void updateApplyState() {
        if (this.applyButton == null) return;
        Integer parsed = parseRadius(this.radiusField.getText());
        boolean ok = parsed != null && parsed >= 1 && parsed <= this.hardLimit
                && parsed != this.currentMaxRadius;
        this.applyButton.active = ok;
    }

    private static Integer parseRadius(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void onApply() {
        Integer parsed = parseRadius(this.radiusField.getText());
        if (parsed == null || parsed < 1 || parsed > this.hardLimit) {
            this.statusMessage = "Enter a whole number between 1 and " + this.hardLimit + ".";
            return;
        }
        ClientPlayNetworking.send(new AdminMaxBaseRadiusPayload(parsed));
        this.statusMessage = "Applied. Awaiting confirmation...";
    }

    /** Called by the client networking handler when the server sends current state. */
    public static void pushState(int current, int hardLimit) {
        AdminWorldSettingsScreen s = INSTANCE;
        if (s != null) {
            s.applyState(current, hardLimit);
        }
    }

    private void applyState(int current, int hardLimit) {
        this.currentMaxRadius = current;
        this.hardLimit = hardLimit;
        if (this.radiusField != null) {
            String typed = this.radiusField.getText();
            // Only overwrite the field if the user hasn't started editing a different value.
            if (typed == null || typed.isBlank()) {
                this.radiusField.setText(Integer.toString(current));
            }
        }
        this.statusMessage = "Current: " + current + " (limit " + hardLimit + ").";
        updateApplyState();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int titleY = this.height / 2 - 60;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, titleY, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Max base radius (blocks)"), centerX, titleY + 20, 0xCCCCCC);
        if (this.statusMessage != null) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(this.statusMessage), centerX, this.height / 2 + 60, 0xAAAAAA);
        }
    }

    @Override
    public void close() {
        INSTANCE = null;
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public void removed() {
        super.removed();
        if (INSTANCE == this) {
            INSTANCE = null;
        }
    }
}
