package net.shasankp000.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.shasankp000.AIPlayer;
import net.shasankp000.Network.configNetworkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class APIKeysScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("ConfigAPIKeysMan");

    // Layout constants (tuned for MC 1.21 UI scale behavior)
    private static final int MARGIN_X = 24;          // horizontal screen margin
    private static final int LABEL_WIDTH = 160;      // reserved label column
    private static final int GAP = 10;               // label→field gap
    private static final int FIELD_HEIGHT = 20;
    private static final int FIELD_SPACING = 10;     // vertical spacing between fields
    private static final int BUTTON_WIDTH = 110;
    private static final int BUTTON_HEIGHT = 20;

    private final Screen parent;

    private TextFieldWidget openAIKeyField;
    private TextFieldWidget claudeKeyField;
    private TextFieldWidget geminiKeyField;
    private TextFieldWidget grokKeyField;
    private TextFieldWidget customApiKeyField;
    private TextFieldWidget customApiUrlField;

    public APIKeysScreen(Text title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    /** Small struct so init() and render() use identical geometry. */
    private static final class Layout {
        final int fieldWidth, groupLeft, labelX, fieldX, startY, buttonY, rowSpacing;
        Layout(int fieldWidth, int groupLeft, int labelX, int fieldX, int startY, int buttonY, int rowSpacing) {
            this.fieldWidth = fieldWidth; this.groupLeft = groupLeft; this.labelX = labelX; this.fieldX = fieldX;
            this.startY = startY; this.buttonY = buttonY; this.rowSpacing = rowSpacing;
        }
    }

    /** Compute a row-centered layout that never exceeds the screen width. */
    private Layout computeLayout() {
        int screenW = this.width;
        int screenH = this.height;

        // Field width is based on remaining space after label + gap + margins.
        int availableForField = Math.max(120, screenW - (MARGIN_X * 2) - LABEL_WIDTH - GAP);
        int fieldWidth = Math.min(520, availableForField); // upper bound looks nice on big screens

        int totalRowWidth = LABEL_WIDTH + GAP + fieldWidth;
        int groupLeft = Math.max(MARGIN_X, (screenW - totalRowWidth) / 2);

        int labelX = groupLeft;
        int fieldX = groupLeft + LABEL_WIDTH + GAP;

        int rows = 6;
        int formHeight = rows * FIELD_HEIGHT + (rows - 1) * FIELD_SPACING;

        // Center vertically, but keep 30px top padding and leave room for buttons.
        int startY = Math.max(30, (screenH - formHeight - 50) / 2);

        // Place buttons below the last field but clamp above bottom edge.
        int buttonY = Math.min(screenH - 36, startY + formHeight + 20);

        return new Layout(fieldWidth, groupLeft, labelX, fieldX, startY, buttonY, FIELD_HEIGHT + FIELD_SPACING);
    }

    @Override
    protected void init() {
        super.init();
        int maxApiKeyLength = 256;

        Layout L = computeLayout();

        // Row Y positions
        int y0 = L.startY;
        int y1 = y0 + L.rowSpacing;
        int y2 = y1 + L.rowSpacing;
        int y3 = y2 + L.rowSpacing;
        int y4 = y3 + L.rowSpacing;
        int y5 = y4 + L.rowSpacing;

        // OpenAI
        openAIKeyField = new TextFieldWidget(this.textRenderer, L.fieldX, y0, L.fieldWidth, FIELD_HEIGHT, Text.empty());
        openAIKeyField.setMaxLength(maxApiKeyLength);
        openAIKeyField.setText(AIPlayer.CONFIG.getOpenAIKey());
        this.addDrawableChild(openAIKeyField);
        this.addSelectableChild(openAIKeyField);

        // Claude
        claudeKeyField = new TextFieldWidget(this.textRenderer, L.fieldX, y1, L.fieldWidth, FIELD_HEIGHT, Text.empty());
        claudeKeyField.setMaxLength(maxApiKeyLength);
        claudeKeyField.setText(AIPlayer.CONFIG.getClaudeKey());
        this.addDrawableChild(claudeKeyField);
        this.addSelectableChild(claudeKeyField);

        // Gemini
        geminiKeyField = new TextFieldWidget(this.textRenderer, L.fieldX, y2, L.fieldWidth, FIELD_HEIGHT, Text.empty());
        geminiKeyField.setMaxLength(maxApiKeyLength);
        geminiKeyField.setText(AIPlayer.CONFIG.getGeminiKey());
        this.addDrawableChild(geminiKeyField);
        this.addSelectableChild(geminiKeyField);

        // Grok
        grokKeyField = new TextFieldWidget(this.textRenderer, L.fieldX, y3, L.fieldWidth, FIELD_HEIGHT, Text.empty());
        grokKeyField.setMaxLength(maxApiKeyLength);
        grokKeyField.setText(AIPlayer.CONFIG.getGrokKey());
        this.addDrawableChild(grokKeyField);
        this.addSelectableChild(grokKeyField);

        // Custom URL
        customApiUrlField = new TextFieldWidget(this.textRenderer, L.fieldX, y4, L.fieldWidth, FIELD_HEIGHT, Text.empty());
        customApiUrlField.setMaxLength(512);
        customApiUrlField.setText(AIPlayer.CONFIG.getCustomApiUrl());
        this.addDrawableChild(customApiUrlField);
        this.addSelectableChild(customApiUrlField);

        // Custom Key
        customApiKeyField = new TextFieldWidget(this.textRenderer, L.fieldX, y5, L.fieldWidth, FIELD_HEIGHT, Text.empty());
        customApiKeyField.setMaxLength(maxApiKeyLength);
        customApiKeyField.setText(AIPlayer.CONFIG.getCustomApiKey());
        this.addDrawableChild(customApiKeyField);
        this.addSelectableChild(customApiKeyField);

        // Buttons (centered)
        int centerX = this.width / 2;
        ButtonWidget saveButton = ButtonWidget.builder(Text.of("Save"), (btn) -> {
            saveToFile();
            if (this.client != null) {
                this.client.getToastManager().add(
                    SystemToast.create(this.client, SystemToast.Type.NARRATOR_TOGGLE,
                        Text.of("API Keys Saved!"), Text.of("Your API keys have been saved."))
                );
            }
        }).dimensions(centerX - BUTTON_WIDTH - 6, L.buttonY, BUTTON_WIDTH, BUTTON_HEIGHT).build();

        ButtonWidget doneButton = ButtonWidget.builder(Text.of("Done"), (btn) -> {
            assert this.client != null;
            this.client.setScreen(this.parent);
        }).dimensions(centerX + 6, L.buttonY, BUTTON_WIDTH, BUTTON_HEIGHT).build();

        this.addDrawableChild(saveButton);
        this.addDrawableChild(doneButton);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // flat dim background (avoids blur reentry crash)
        context.fill(0, 0, this.width, this.height, 0xB0000000);
        super.render(context, mouseX, mouseY, delta);

        Layout L = computeLayout();
        int labelY0 = L.startY + 5;

        context.drawText(this.textRenderer, "OpenAI API Key:", L.labelX, labelY0, 0xFFFFFFFF, true);
        context.drawText(this.textRenderer, "Claude API Key:", L.labelX, labelY0 + L.rowSpacing, 0xFFFFFFFF, true);
        context.drawText(this.textRenderer, "Gemini API Key:", L.labelX, labelY0 + L.rowSpacing * 2, 0xFFFFFFFF, true);
        context.drawText(this.textRenderer, "Grok API Key:",   L.labelX, labelY0 + L.rowSpacing * 3, 0xFFFFFFFF, true);
        context.drawText(this.textRenderer, "Custom API URL:", L.labelX, labelY0 + L.rowSpacing * 4, 0xFFFFFFFF, true);
        context.drawText(this.textRenderer, "Custom API Key:", L.labelX, labelY0 + L.rowSpacing * 5, 0xFFFFFFFF, true);
    }

    private void saveToFile() {
        AIPlayer.CONFIG.setOpenAIKey(this.openAIKeyField.getText());
        AIPlayer.CONFIG.setClaudeKey(this.claudeKeyField.getText());
        AIPlayer.CONFIG.setGeminiKey(this.geminiKeyField.getText());
        AIPlayer.CONFIG.setGrokKey(this.grokKeyField.getText());
        AIPlayer.CONFIG.setCustomApiKey(this.customApiKeyField.getText());
        AIPlayer.CONFIG.setCustomApiUrl(this.customApiUrlField.getText());
        AIPlayer.CONFIG.save();

        String llmMode = System.getProperty("aiplayer.llmMode", "ollama");
        switch (llmMode) {
            case "openai" -> configNetworkManager.sendSaveAPIPacket(llmMode, this.openAIKeyField.getText());
            case "gemini" -> configNetworkManager.sendSaveAPIPacket(llmMode, this.geminiKeyField.getText());
            case "claude" -> configNetworkManager.sendSaveAPIPacket(llmMode, this.claudeKeyField.getText());
            case "grok"   -> configNetworkManager.sendSaveAPIPacket(llmMode, this.grokKeyField.getText());
            case "custom" -> configNetworkManager.sendSaveCustomProviderPacket(this.customApiKeyField.getText(), this.customApiUrlField.getText());
            case "ollama" -> LOGGER.info("No API key packet sent for Ollama mode.");
            default       -> LOGGER.warn("Unsupported LLM mode: {}", llmMode);
        }
    }

    @Override
    public void close() {
        assert this.client != null;
        this.client.setScreen(this.parent);
    }
}