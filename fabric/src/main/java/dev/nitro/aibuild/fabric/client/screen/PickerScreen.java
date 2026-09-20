package dev.nitro.aibuild.fabric.client.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * A page of buttons, one per choice.
 *
 * <p>Used wherever something would otherwise have to be typed exactly right: the
 * provider and the model. A grid of plain buttons rather than a scrolling list,
 * because a list entry has to draw itself and drawing is the one part of 26.3's
 * client API that changed shape.
 */
public final class PickerScreen extends Screen {

    /** One choice: the value stored, what the button says, and an optional note. */
    public record Choice(String value, String label, String note, boolean highlighted) {
        public Choice(String value, String label) {
            this(value, label, "", false);
        }
    }

    private static final int COLUMNS = 2;
    private static final int BUTTON_WIDTH = 190;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 4;

    private final Screen parent;
    private final List<Choice> choices;
    private final Consumer<String> onPick;
    private final String emptyMessage;

    private int scroll;

    public PickerScreen(Screen parent, String title, List<Choice> choices,
                        String emptyMessage, Consumer<String> onPick) {
        super(Component.literal(title));
        this.parent = parent;
        this.choices = choices;
        this.onPick = onPick;
        this.emptyMessage = emptyMessage;
    }

    /** How many rows fit between the header and the footer. */
    private int rowsPerPage() {
        int usable = height - 70;
        return Math.max(1, usable / (BUTTON_HEIGHT + GAP));
    }

    private int pageSize() {
        return rowsPerPage() * COLUMNS;
    }

    @Override
    protected void init() {
        int gridWidth = COLUMNS * BUTTON_WIDTH + (COLUMNS - 1) * GAP;
        int left = (width - gridWidth) / 2;

        addRenderableWidget(new StringWidget(
                0, 14, width, 12, getTitle().copy().withStyle(ChatFormatting.AQUA), font));

        if (choices.isEmpty()) {
            addRenderableWidget(new StringWidget(0, 50, width, 12,
                    Component.literal(emptyMessage).withStyle(ChatFormatting.GRAY), font));
        }

        int pageSize = pageSize();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, choices.size() - 1) / Math.max(1, pageSize) * pageSize));

        for (int i = 0; i < pageSize && scroll + i < choices.size(); i++) {
            Choice choice = choices.get(scroll + i);
            int column = i % COLUMNS;
            int row = i / COLUMNS;
            int x = left + column * (BUTTON_WIDTH + GAP);
            int y = 36 + row * (BUTTON_HEIGHT + GAP);

            Component label = Component.literal(choice.label()
                    + (choice.note().isEmpty() ? "" : "  " + choice.note()))
                    .withStyle(choice.highlighted() ? ChatFormatting.GREEN : ChatFormatting.WHITE);

            addRenderableWidget(Button.builder(label, button -> {
                onPick.accept(choice.value());
                minecraft.setScreenAndShow(parent);
            }).bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        }

        int footerY = height - 28;
        if (choices.size() > pageSize) {
            addRenderableWidget(Button.builder(Component.literal("< Prev"), button -> {
                scroll = Math.max(0, scroll - pageSize);
                rebuildWidgets();
            }).bounds(left, footerY, 60, BUTTON_HEIGHT).build());

            addRenderableWidget(Button.builder(Component.literal("Next >"), button -> {
                if (scroll + pageSize < choices.size()) {
                    scroll += pageSize;
                    rebuildWidgets();
                }
            }).bounds(left + gridWidth - 60, footerY, 60, BUTTON_HEIGHT).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Back"),
                        button -> minecraft.setScreenAndShow(parent))
                .bounds((width - 100) / 2, footerY, 100, BUTTON_HEIGHT).build());
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
