package net.shasankp000.GraphicalUserInterface.Widgets;

// This file is still heavily work in progress. At least it doesn't crash.

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;


import java.util.ArrayList;
import java.util.List;

public class DropdownMenuWidget extends ClickableWidget {
    private List<String> options;
    private boolean isOpen;
    private int selectedIndex;
    private int width;
    private int height;
    private static final boolean DEBUG = false;

    // scrolling support
    private int scrollIndex = 0; // first visible option when open

    public DropdownMenuWidget(int x, int y, int width, int height, Text message, List<String> options) {
        super(x, y, width, height, message);
        this.options = options != null ? new ArrayList<>(options) : new ArrayList<>();
        this.isOpen = false;
        this.active = true;
        this.visible = true;

        this.selectedIndex = -1; // No initial selection; ConfigManager or caller can set one
        if (DEBUG) {
            System.out.println("[DropdownMenuWidget] initial selectedIndex=" + this.selectedIndex);
        }
        this.width = width;
        this.height = height;
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;

        // Draw main button background
        int x = this.getX();
        int y = this.getY();
        int right = x + this.width;
        int bottom = y + this.height;

        // Outer border (dark gray)
        context.fill(x - 1, y - 1, right + 1, bottom + 1, 0xFF202020);
        // Inner background (black-ish)
        context.fill(x, y, right, bottom, 0xFF000000);

        // Text to display on the main button: always prefer the selected option
        Text displayText;
        if (selectedIndex >= 0 && selectedIndex < options.size()) {
            displayText = Text.of(options.get(selectedIndex));
        } else if (this.getMessage() != null && !this.getMessage().getString().isEmpty()) {
            displayText = this.getMessage();
        } else {
            displayText = Text.of("Select a model");
        }

        if (DEBUG) {
            System.out.println("[DropdownMenuWidget] renderWidget isOpen=" + isOpen + ", options=" + options.size() + ", selectedIndex=" + selectedIndex + ", text='" + displayText.getString() + "'");
        }

        // Draw the label text
        drawCenteredText(context, textRenderer, displayText, x + this.width / 2, y + (this.height - 8) / 2, 0xFFFFFFFF);

        // Draw a simple dropdown arrow on the right side
        int arrowX = right - 10;
        int arrowY = y + this.height / 2 - 2;
        context.drawText(textRenderer, "▼", arrowX, arrowY, 0xFFFFFFFF, false);

        // Render the dropdown menu if open
        if (isOpen && !options.isEmpty()) {
            int menuTop = y + this.height;

            // Compute how many rows can be shown before hitting the bottom of the screen.
            int screenH = MinecraftClient.getInstance().getWindow().getScaledHeight();
            int marginBottom = 36; // keep room above bottom UI chrome
            int available = Math.max(0, (screenH - marginBottom) - menuTop);
            int maxVisible = Math.max(1, Math.min(options.size(), available / this.height));
            int visibleCount = maxVisible;

            // Clamp scrollIndex and compute panel bottom.
            int maxScroll = Math.max(0, options.size() - visibleCount);
            if (scrollIndex > maxScroll) scrollIndex = maxScroll;
            if (scrollIndex < 0) scrollIndex = 0;

            int menuBottom = menuTop + this.height * visibleCount;

            // Solid panel behind list
            context.fill(x - 1, menuTop - 1, right + 1, menuBottom + 1, 0xFF000000);

            // Draw visible rows
            for (int i = 0; i < visibleCount; i++) {
                int optionIndex = scrollIndex + i;
                int optionTop = menuTop + this.height * i;
                int optionBottom = optionTop + this.height;

                // Background for each option (slightly lighter)
                context.fill(x - 1, optionTop - 1, right + 1, optionBottom + 1, 0xFF202020);
                context.fill(x, optionTop, right, optionBottom, 0xFF101010);

                // If this is the selected option, highlight it a bit
                if (optionIndex == selectedIndex) {
                    context.fill(x, optionTop, right, optionBottom, 0x4010A0FF);
                }

                drawCenteredText(context, textRenderer, Text.of(options.get(optionIndex)), x + this.width / 2, optionTop + (this.height - 8) / 2, 0xFFFFFFFF);
            }

            // Simple scrollbar if there is more content than we can show
            if (options.size() > visibleCount) {
                int trackX0 = right - 4;
                int trackX1 = right - 2;
                context.fill(trackX0, menuTop, trackX1, menuBottom, 0xFF303030);

                float frac = (float) scrollIndex / (float) Math.max(1, options.size() - visibleCount);
                int thumbH = Math.max(6, (menuBottom - menuTop) * visibleCount / options.size());
                int thumbY = menuTop + Math.round(((menuBottom - menuTop - thumbH) * frac));
                context.fill(trackX0, thumbY, trackX1, thumbY + thumbH, 0xFFA0A0A0);
            }
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean isInside) {
        double mouseX = click.x();
        double mouseY = click.y();
        int x = this.getX();
        int y = this.getY();
        int right = x + this.width;
        int bottom = y + this.height;

        // Click on the main button toggles the dropdown open/closed
        if (mouseX >= x && mouseX < right && mouseY >= y && mouseY < bottom) {
            isOpen = !isOpen;
            return true;
        }

        // If open, check for clicks on any of the visible option rows
        if (isOpen && !options.isEmpty()) {
            int menuTop = y + this.height;

            int screenH = MinecraftClient.getInstance().getWindow().getScaledHeight();
            int marginBottom = 36;
            int available = Math.max(0, (screenH - marginBottom) - menuTop);
            int visibleCount = Math.max(1, Math.min(options.size(), available / this.height));

            int maxScroll = Math.max(0, options.size() - visibleCount);
            if (scrollIndex > maxScroll) scrollIndex = maxScroll;
            if (scrollIndex < 0) scrollIndex = 0;

            int menuBottom = menuTop + visibleCount * this.height;

            if (mouseX >= x && mouseX < right && mouseY >= menuTop && mouseY < menuBottom) {
                int relative = (int) ((mouseY - menuTop) / this.height);
                int optionIndex = scrollIndex + Math.max(0, Math.min(visibleCount - 1, relative));
                selectedIndex = optionIndex;
                isOpen = false;
                setMessage(Text.of(options.get(optionIndex)));
                if (DEBUG) {
                    System.out.println("[DropdownMenuWidget] Option clicked: index=" + optionIndex + ", value=" + options.get(optionIndex));
                }
                return true;
            }

            // Clicked outside the menu while open: close it
            isOpen = false;
        }

        return false;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        int x = this.getX();
        int y = this.getY();
        int right = x + this.width;
        int bottom = y + this.height;

        if (!isOpen) {
            return mouseX >= x && mouseX < right && mouseY >= y && mouseY < bottom;
        }

        int menuTop = y + this.height;
        int screenH = MinecraftClient.getInstance().getWindow().getScaledHeight();
        int marginBottom = 36;
        int available = Math.max(0, (screenH - marginBottom) - menuTop);
        int visibleCount = Math.max(1, Math.min(options.size(), available / this.height));
        int menuBottom = menuTop + visibleCount * this.height;

        return mouseX >= x && mouseX < right && mouseY >= y && mouseY < menuBottom;
    }

    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        // nothing to do there
        return;
    }

    public void updateOptions(List<String> newOptions) {
        String previousSelected = getSelectedOption();
        this.options = new ArrayList<>(newOptions != null ? newOptions : List.of());
        if (DEBUG) {
            System.out.println("[DropdownMenuWidget] updateOptions size=" + this.options.size());
        }
        if (this.options.isEmpty()) {
            this.selectedIndex = -1;
            setMessage(Text.of("No models"));
        } else if (previousSelected != null && this.options.contains(previousSelected)) {
            this.selectedIndex = this.options.indexOf(previousSelected);
            setMessage(Text.of(previousSelected));
        } else {
            this.selectedIndex = -1;
            setMessage(Text.of("Select a model"));
        }
        this.isOpen = false;
        this.scrollIndex = 0;
    }

    public void setSelectedOption(String option) {
        if (option == null || options == null || options.isEmpty()) {
            return;
        }
        int index = options.indexOf(option);
        if (index >= 0) {
            selectedIndex = index;
            isOpen = false;
            setMessage(Text.of(options.get(selectedIndex)));
        }
    }

    public String getSelectedOption() {
        if (selectedIndex < 0 || options == null || selectedIndex >= options.size()) {
            return null;
        }
        return options.get(selectedIndex);
    }

    private void drawCenteredText(DrawContext context, TextRenderer textRenderer, Text text, int centerX, int y, int color) {
        int textWidth = textRenderer.getWidth(text);
        context.drawText(textRenderer, text, centerX - textWidth / 2, y, color, true);
    }

    // Fallback signature used by many 1.20/1.21 mappings
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!(isOpen && !options.isEmpty())) return false;

        int menuTop = this.getY() + this.height;
        int screenH = MinecraftClient.getInstance().getWindow().getScaledHeight();
        int marginBottom = 36;
        int available = Math.max(0, (screenH - marginBottom) - menuTop);
        int visibleCount = Math.max(1, Math.min(options.size(), available / this.height));
        int maxScroll = Math.max(0, options.size() - visibleCount);
        if (maxScroll == 0) return false;

        int delta = (verticalAmount > 0) ? -1 : (verticalAmount < 0 ? 1 : 0);
        if (delta == 0) return false;

        scrollIndex = Math.max(0, Math.min(maxScroll, scrollIndex + delta));
        return true;
    }
}
