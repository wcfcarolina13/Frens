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

    private final Screen parent;
    private TextFieldWidget openAIKeyField;
    private TextFieldWidget claudeKeyField;
    private TextFieldWidget geminiKeyField;
    private TextFieldWidget grokKeyField;
    public static final Logger LOGGER = LoggerFactory.getLogger("ConfigAPIKeysMan");

    public APIKeysScreen(Text title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int startY = this.height / 4 + 10;
        int fieldWidth = 300;
        int fieldHeight = 20;
        int labelWidth = 100; // New variable for label width
        int startX = this.width / 2 - (fieldWidth / 2) + labelWidth; // New starting X for text fields
        int buttonWidth = 100;

        // OpenAI Key Field
        this.openAIKeyField = new TextFieldWidget(this.textRenderer, startX, startY, fieldWidth, fieldHeight, Text.empty());
        this.openAIKeyField.setText(AIPlayer.CONFIG.openAIKey());
        this.addDrawableChild(this.openAIKeyField);
        this.addSelectableChild(this.openAIKeyField);

        // Claude Key Field
        this.claudeKeyField = new TextFieldWidget(this.textRenderer, startX, startY + 30, fieldWidth, fieldHeight, Text.empty());
        this.claudeKeyField.setText(AIPlayer.CONFIG.claudeKey());
        this.addDrawableChild(this.claudeKeyField);
        this.addSelectableChild(this.claudeKeyField);

        // Gemini Key Field
        this.geminiKeyField = new TextFieldWidget(this.textRenderer, startX, startY + 60, fieldWidth, fieldHeight, Text.empty());
        this.geminiKeyField.setText(AIPlayer.CONFIG.geminiKey());
        this.addDrawableChild(this.geminiKeyField);
        this.addSelectableChild(this.geminiKeyField);

        // Grok Key Field
        this.grokKeyField = new TextFieldWidget(this.textRenderer, startX, startY + 90, fieldWidth, fieldHeight, Text.empty());
        this.grokKeyField.setText(AIPlayer.CONFIG.grokKey());
        this.addDrawableChild(this.grokKeyField);
        this.addSelectableChild(this.grokKeyField);

        // Save Button
        ButtonWidget saveButton = ButtonWidget.builder(Text.of("Save"), (btn) -> {
            this.saveToFile();

            if (this.client != null) {
                this.client.getToastManager().add(
                        SystemToast.create(this.client, SystemToast.Type.NARRATOR_TOGGLE, Text.of("API Keys Saved!"), Text.of("Your API keys have been saved.")));
            }
        }).dimensions(this.width / 2 - buttonWidth - 10, this.height - 40, buttonWidth, fieldHeight).build();
        this.addDrawableChild(saveButton);

        // Done Button
        ButtonWidget doneButton = ButtonWidget.builder(Text.of("Done"), (btn) -> {
            // Close the current screen and return to the parent
            assert this.client != null;
            this.client.setScreen(this.parent);
        }).dimensions(this.width / 2 + 10, this.height - 40, buttonWidth, fieldHeight).build();
        this.addDrawableChild(doneButton);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        // Draw labels
        int labelX = this.width / 2 - 150;
        int startY = this.height / 4 + 10;
        int spacing = 30;

        context.drawText(this.textRenderer, "OpenAI API Key:", labelX, startY + 5, 0xFFFFFFFF, true);
        context.drawText(this.textRenderer, "Claude API Key:", labelX, startY + spacing + 5, 0xFFFFFFFF, true);
        context.drawText(this.textRenderer, "Gemini API Key:", labelX, startY + (spacing * 2) + 5, 0xFFFFFFFF, true);
        context.drawText(this.textRenderer, "Grok API Key:", labelX, startY + (spacing * 3) + 5, 0xFFFFFFFF, true);
    }


    private void saveToFile() {
        // 1. Save all API keys to the client's config. This should always happen.
        AIPlayer.CONFIG.openAIKey(this.openAIKeyField.getText());
        AIPlayer.CONFIG.claudeKey(this.claudeKeyField.getText());
        AIPlayer.CONFIG.geminiKey(this.geminiKeyField.getText());
        AIPlayer.CONFIG.grokKey(this.grokKeyField.getText());

        // 2. Only save the config file once, after all values have been updated.
        AIPlayer.CONFIG.save();

        // 3. Send a network packet for the currently selected mode only if it's a valid provider.
        String llmMode = System.getProperty("aiplayer.llmMode", "ollama");

        switch (llmMode) {
            case "openai":
                configNetworkManager.sendSaveAPIPacket(llmMode, this.openAIKeyField.getText());
                break;
            case "gemini":
                configNetworkManager.sendSaveAPIPacket(llmMode, this.geminiKeyField.getText());
                break;
            case "claude":
                configNetworkManager.sendSaveAPIPacket(llmMode, this.claudeKeyField.getText());
                break;
            case "grok":
                configNetworkManager.sendSaveAPIPacket(llmMode, this.grokKeyField.getText());
                break;
            case "ollama":
                LOGGER.info("No API key packet sent for Ollama mode.");
                break;
            default:
                LOGGER.warn("Unsupported LLM mode: " + llmMode);
                break;
        }
    }

    @Override
    public void close() {
        assert this.client != null;
        this.client.setScreen(this.parent);
    }
}
