package net.shasankp000.GraphicalUserInterface;


import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.shasankp000.AIPlayer;
import net.shasankp000.AIPlayerClient;
import net.shasankp000.GraphicalUserInterface.Widgets.DropdownMenuWidget;
import net.shasankp000.GraphicalUserInterface.BotControlScreen;
import net.shasankp000.Network.configNetworkManager;
import net.shasankp000.Network.ConfigJsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ConfigManager extends Screen {
    public static final Logger LOGGER = LoggerFactory.getLogger("ConfigMan");
    public Screen parent;
    private DropdownMenuWidget dropdownMenuWidget;
    private TextFieldWidget searchField;
    private List<String> allModels;
    private List<String> filteredModels;
    private int footerTopY = 0;
    private static long lastModelRefreshMs = 0L;


    public ConfigManager(Text title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    @Override
    protected void init() {

        List<String> cached = AIPlayer.CONFIG.getModelList();
        if (cached == null || cached.isEmpty()) {
            refreshModelList();
            cached = AIPlayer.CONFIG.getModelList();
        }

        allModels = cached;
        if (allModels == null) {
            allModels = new ArrayList<>();
        }
        LOGGER.info("Fetched {} models from provider on frontend: {}", allModels.size(), allModels);
        filteredModels = new ArrayList<>(allModels);

        // Calculate positions
        int centerX = this.width / 2;
        int topMargin = 50;
        int fieldWidth = 300;
        int fieldHeight = 20;
        int buttonWidth = 100;
        int spacing = 30;

        // Search field - centered and properly positioned
        searchField = new TextFieldWidget(this.textRenderer, centerX - fieldWidth / 2, topMargin, fieldWidth, fieldHeight, Text.of("Search models..."));
        searchField.setMaxLength(256);
        searchField.setPlaceholder(Text.of("Search models..."));
        searchField.setChangedListener(this::onSearchChanged);
        this.addDrawableChild(searchField);
        this.addSelectableChild(searchField);

        // Dropdown - centered below search field
        int dropdownY = topMargin + spacing + 10; // Extra space for label

        dropdownMenuWidget = new DropdownMenuWidget(centerX - fieldWidth / 2, dropdownY, fieldWidth, fieldHeight, Text.of("List of available models"), filteredModels);
        this.dropdownMenuWidget = dropdownMenuWidget;
        // Register dropdown as both drawable and selectable so it can render and receive input correctly
        this.addDrawableChild(dropdownMenuWidget);
        this.addSelectableChild(dropdownMenuWidget);

        // Try to pre-select the currently configured model, or fall back to the first entry.
        // This value is treated as the "default" model for the active provider until the user changes it.
        String currentSelected = AIPlayer.CONFIG.getSelectedLanguageModel();
        if (currentSelected != null && filteredModels.contains(currentSelected)) {
            // Config already has a valid default for this provider; reflect it in the UI.
            dropdownMenuWidget.setSelectedOption(currentSelected);
        } else if (!filteredModels.isEmpty()) {
            // No valid selection in config: choose the first model from the provider list
            // as the temporary default for this session, and update the in-memory config.
            String newDefault = filteredModels.get(0);
            dropdownMenuWidget.setSelectedOption(newDefault);
            AIPlayer.CONFIG.setSelectedLanguageModel(newDefault);
            LOGGER.info("No valid selectedLanguageModel in config; using first provider model as session default: {}", newDefault);
        }

        java.util.List<ButtonWidget> footerButtons = new java.util.ArrayList<>();
        footerButtons.add(ButtonWidget.builder(
                Text.of("API Keys"),
                (btn) -> Objects.requireNonNull(this.client).setScreen(new APIKeysScreen(Text.of("API Keys"), this))
        ).dimensions(0, 0, 80, fieldHeight).build());
        footerButtons.add(ButtonWidget.builder(
                Text.of("Reasoning Log"),
                (btn) -> Objects.requireNonNull(this.client).setScreen(new ReasoningLogScreen(this))
        ).dimensions(0, 0, 80, fieldHeight).build());
        footerButtons.add(ButtonWidget.builder(
                Text.of("Bot Controls"),
                (btn) -> Objects.requireNonNull(this.client).setScreen(new BotControlScreen(this))
        ).dimensions(0, 0, 80, fieldHeight).build());
        footerButtons.add(ButtonWidget.builder(
                Text.of("Refresh Models"),
                (btn) -> this.reloadModels()
        ).dimensions(0, 0, 80, fieldHeight).build());
        footerButtons.add(ButtonWidget.builder(Text.of("Save"), (btn1) -> {
            this.saveToFile();
            if (this.client != null) {
                this.client.getToastManager().add(
                        SystemToast.create(this.client, SystemToast.Type.NARRATOR_TOGGLE,
                                Text.of("Settings saved!"), Text.of("Saved settings.")));
            }
        }).dimensions(0, 0, 80, fieldHeight).build());
        footerButtons.add(ButtonWidget.builder(Text.of("Close"), (btn1) -> this.close())
                .dimensions(0, 0, 80, fieldHeight).build());
        layoutButtons(footerButtons, fieldHeight);
    }

    private void layoutButtons(java.util.List<ButtonWidget> buttons, int buttonHeight) {
        if (buttons.isEmpty()) {
            return;
        }
        int availableWidth = Math.max(80, this.width - 40);
        int spacing = 8;
        int minButtonWidth = 80;
        int buttonsPerRow = Math.min(buttons.size(),
                Math.max(1, (availableWidth + spacing) / (minButtonWidth + spacing)));
        int rows = (int) Math.ceil(buttons.size() / (double) buttonsPerRow);
        int rowSpacing = 10;
        int totalHeight = rows * buttonHeight + (rows - 1) * rowSpacing;
        int startY = Math.max(40, this.height - 20 - totalHeight);
        this.footerTopY = startY;

        int index = 0;
        for (int row = 0; row < rows; row++) {
            int remaining = buttons.size() - index;
            int columns = Math.min(buttonsPerRow, remaining);
            int rowButtonWidth = Math.max(minButtonWidth,
                    (availableWidth - spacing * (columns - 1)) / columns);
            int rowWidth = columns * rowButtonWidth + spacing * (columns - 1);
            int rowStartX = (this.width - rowWidth) / 2;
            for (int col = 0; col < columns; col++) {
                ButtonWidget button = buttons.get(index++);
                int x = rowStartX + col * (rowButtonWidth + spacing);
                int y = startY + row * (buttonHeight + rowSpacing);
                button.setX(x);
                button.setY(y);
                button.setWidth(rowButtonWidth);
                button.setHeight(buttonHeight);
                this.addDrawableChild(button);
            }
        }
    }

    private void refreshModelList() {
        long now = System.currentTimeMillis();
        if (now - lastModelRefreshMs < 10_000) {
            LOGGER.info("Skipping model refresh; last refresh was less than 10s ago.");
            return;
        }
        lastModelRefreshMs = now;
        LOGGER.info("Refreshing model list from provider...");
        AIPlayer.CONFIG.updateModels();
    }

    private void reloadModels() {
        refreshModelList();
        LOGGER.info("Reloading model list from provider...");

        // Remember the currently selected option in the dropdown so we can try to keep it.
        String previouslySelected = dropdownMenuWidget != null ? dropdownMenuWidget.getSelectedOption() : null;

        AIPlayer.CONFIG.updateModels();
        allModels = AIPlayer.CONFIG.getModelList();
        if (allModels == null) {
            allModels = new ArrayList<>();
        }

        filteredModels = new ArrayList<>(allModels);
        dropdownMenuWidget.updateOptions(filteredModels);

        // Try to preserve the previous selection if it still exists in the new list.
        if (previouslySelected != null && filteredModels.contains(previouslySelected)) {
            dropdownMenuWidget.setSelectedOption(previouslySelected);
            AIPlayer.CONFIG.setSelectedLanguageModel(previouslySelected);
            LOGGER.info("Preserved previously selected model after reload: {}", previouslySelected);
        } else if (!filteredModels.isEmpty()) {
            // Fall back to the first model if the previous one no longer exists.
            String newDefault = filteredModels.get(0);
            dropdownMenuWidget.setSelectedOption(newDefault);
            AIPlayer.CONFIG.setSelectedLanguageModel(newDefault);
            LOGGER.info("Previous selection not available; using first provider model as new default after reload: {}", newDefault);
        } else {
            // No models at all; clear the selection in config.
            AIPlayer.CONFIG.setSelectedLanguageModel("");
            LOGGER.warn("Model list is empty after reload; cleared selectedLanguageModel in config.");
        }

        LOGGER.info("Reloaded {} models from provider on frontend: {}", allModels.size(), allModels);

        // Show a toast notification with more context
        if (this.client != null) {
            String subtitle;
            if (allModels.isEmpty()) {
                subtitle = "No models found – check your API key or provider settings";
            } else {
                String current = AIPlayer.CONFIG.getSelectedLanguageModel();
                subtitle = "Found " + allModels.size() + " models; default is now: " + (current != null ? current : "None");
            }
            this.client.getToastManager().add(
                    SystemToast.create(this.client, SystemToast.Type.NARRATOR_TOGGLE,
                            Text.of("Models Reloaded"),
                            Text.of(subtitle)));
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Draw a simple dim background without triggering the blur helper
        context.fill(0, 0, this.width, this.height, 0xB0000000);

        // Color scheme
        int titleColor = 0xFFFFFFFF;    // White
        int labelColor = 0xFFFFD700;    // Gold
        int infoColor = 0xFF00FF00;     // Green
        int counterColor = 0xFFADD8E6;  // Light blue
        int hintColor = 0xFFFFB6C1;     // Light pink

        int centerX = this.width / 2;

        // Title - centered
        String title = "AI-Player Mod Configuration Menu v1.0.5.2-release+1.21.1";
        int titleWidth = this.textRenderer.getWidth(title);
        context.drawText(this.textRenderer, title, centerX - titleWidth / 2, 20, titleColor, true);

        if (searchField != null) {
            // Search label - above search field
            context.drawText(this.textRenderer, "Search Models:", centerX - 150, searchField.getY() - 15, labelColor, true);
        }

        if (dropdownMenuWidget != null) {
            // Model selection label - above dropdown
            context.drawText(this.textRenderer, "Select Language Model:", centerX - 150, dropdownMenuWidget.getY() - 15, labelColor, true);

            // Current selection and model count info - positioned lower on the screen
            int footerGuard = footerTopY > 0 ? footerTopY - 60 : this.height - 160;
            int infoBaseY = Math.min(footerGuard, dropdownMenuWidget.getY() + dropdownMenuWidget.getHeight() + 40);
            String currentModel = AIPlayer.CONFIG.getSelectedLanguageModel();
            String currentText = "Currently selected: " + (currentModel != null ? currentModel : "None");
            context.drawText(this.textRenderer, currentText, centerX - 150, infoBaseY, infoColor, true);

            String countText = "Showing " + filteredModels.size() + " of " + allModels.size() + " models";
            context.drawText(this.textRenderer, countText, centerX - 150, infoBaseY + 15, counterColor, true);

            if (allModels.isEmpty()) {
                String warnText = "No models loaded – open API Keys and verify your settings, then click Refresh Models";
                int warnWidth = this.textRenderer.getWidth(warnText);
                context.drawText(this.textRenderer, warnText, centerX - warnWidth / 2, infoBaseY + 30, 0xFFFF5555, true);
            }
        }

        // Help text at bottom
        String helpText = "Search to filter models • Select a model and click Save";
        int helpY = footerTopY > 0 ? Math.min(this.height - 65, footerTopY - 20) : this.height - 65;
        if (dropdownMenuWidget != null) {
            helpY = Math.max(helpY, dropdownMenuWidget.getY() + dropdownMenuWidget.getHeight() + 50);
        }
        int maxWidth = this.width - 40;
        java.util.List<net.minecraft.text.OrderedText> wrapped = this.textRenderer.wrapLines(net.minecraft.text.Text.of(helpText), maxWidth);
        int lineY = helpY;
        for (net.minecraft.text.OrderedText line : wrapped) {
            int lineWidth = this.textRenderer.getWidth(line);
            context.drawText(this.textRenderer, line, centerX - lineWidth / 2, lineY, hintColor, true);
            lineY += this.textRenderer.fontHeight + 2;
        }

        // Finally, render all child widgets (dropdown, text field, buttons) on top of the labels
        super.render(context, mouseX, mouseY, delta);
    }

    private void onSearchChanged(String searchText) {
        // Filter models based on search text
        if (searchText.trim().isEmpty()) {
            filteredModels = new ArrayList<>(allModels);
        } else {
            filteredModels = allModels.stream()
                    .filter(model -> model.toLowerCase().contains(searchText.toLowerCase().trim()))
                    .collect(Collectors.toList());
        }

        // Update dropdown with filtered models
        // You'll need to add this method to your DropdownMenuWidget
        dropdownMenuWidget.updateOptions(filteredModels);
    }

    private void saveToFile() {
        String modelName = this.dropdownMenuWidget.getSelectedOption();

        if (modelName == null || modelName.trim().isEmpty()) {
            LOGGER.warn("No model selected or model name is empty. Skipping save.");

            if (this.client != null) {
                this.client.getToastManager().add(
                        SystemToast.create(this.client, SystemToast.Type.NARRATOR_TOGGLE,
                                Text.of("Error"), Text.of("Please select a model first!")));
            }
            return;
        }

        System.out.println("Selected model: " + modelName);
        LOGGER.info("Persisting selected model as default for current provider: {}", modelName);

        AIPlayer.CONFIG.setSelectedLanguageModel(modelName);
        AIPlayer.CONFIG.save();

        configNetworkManager.sendSaveConfigPacket(ConfigJsonUtil.configToJson());

        close();
        assert this.client != null;
        this.client.setScreen(new ConfigManager(Text.empty(), this.parent));
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }
}
