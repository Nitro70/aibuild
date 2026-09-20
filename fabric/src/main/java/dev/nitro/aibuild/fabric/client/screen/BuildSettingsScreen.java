package dev.nitro.aibuild.fabric.client.screen;

import dev.nitro.aibuild.core.config.AiBuildConfig;
import dev.nitro.aibuild.fabric.AiBuildMod;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The build tuning knobs: size limits, speed, and the handful of toggles.
 *
 * <p>Sliders and on/off buttons rather than numbers to type, so nothing here can
 * be set to something nonsensical in the first place.
 */
public final class BuildSettingsScreen extends Screen {

    private static final int WIDGET_WIDTH = 200;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GAP = 4;

    private final Screen parent;
    private final AiBuildConfig config = AiBuildMod.config();

    public BuildSettingsScreen(Screen parent) {
        super(Component.literal("Build Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int gridWidth = WIDGET_WIDTH * 2 + GAP;
        int left = (width - gridWidth) / 2;
        int rightColumn = left + WIDGET_WIDTH + GAP;
        int top = 34;
        int row = 0;

        addRenderableWidget(new StringWidget(0, 12, width, 12,
                getTitle().copy().withStyle(ChatFormatting.AQUA), font));

        // Left column: how big and how fast.
        addRenderableWidget(new ValueSlider(left, top + rowY(row++), WIDGET_WIDTH, WIDGET_HEIGHT,
                1000, 50000, 1000, (int) config.maxBlocks,
                v -> Component.literal("Max blocks: " + v),
                v -> config.maxBlocks = v));

        addRenderableWidget(new ValueSlider(left, top + rowY(row++), WIDGET_WIDTH, WIDGET_HEIGHT,
                8, 128, 8, config.maxRadius,
                v -> Component.literal("Max radius: " + v),
                v -> config.maxRadius = v));

        addRenderableWidget(new ValueSlider(left, top + rowY(row++), WIDGET_WIDTH, WIDGET_HEIGHT,
                8, 128, 8, config.maxHeight,
                v -> Component.literal("Max height: " + v),
                v -> config.maxHeight = v));

        addRenderableWidget(new ValueSlider(left, top + rowY(row++), WIDGET_WIDTH, WIDGET_HEIGHT,
                0, 100, 1, config.blocksPerTick,
                v -> Component.literal("Build speed: " + (v == 0 ? "instant" : v + " blocks/tick")),
                v -> config.blocksPerTick = v));

        addRenderableWidget(new ValueSlider(left, top + rowY(row++), WIDGET_WIDTH, WIDGET_HEIGHT,
                0, 16, 1, config.originOffset,
                v -> Component.literal("Distance in front: " + v),
                v -> config.originOffset = v));

        addRenderableWidget(new ValueSlider(left, top + rowY(row++), WIDGET_WIDTH, WIDGET_HEIGHT,
                0, 300, 5, config.cooldownSeconds,
                v -> Component.literal("Cooldown: " + (v == 0 ? "off" : v + "s")),
                v -> config.cooldownSeconds = v));

        // Right column: model behaviour and the toggles.
        row = 0;

        addRenderableWidget(new ValueSlider(rightColumn, top + rowY(row++), WIDGET_WIDTH, WIDGET_HEIGHT,
                0, 20, 1, (int) Math.round(config.temperature * 10),
                v -> Component.literal("Temperature: " + String.format("%.1f", v / 10.0)),
                v -> config.temperature = v / 10.0));

        addRenderableWidget(new ValueSlider(rightColumn, top + rowY(row++), WIDGET_WIDTH, WIDGET_HEIGHT,
                1, 60, 1, config.undoHistory,
                v -> Component.literal("Undo history: " + v),
                v -> config.undoHistory = v));

        addRenderableWidget(CycleButton.onOffBuilder(config.rotateToPlayer)
                .create(rightColumn, top + rowY(row++), WIDGET_WIDTH, WIDGET_HEIGHT,
                        Component.literal("Face the player"),
                        (button, value) -> config.rotateToPlayer = value));

        addRenderableWidget(CycleButton.onOffBuilder(config.flipRepeaterFacing)
                .create(rightColumn, top + rowY(row++), WIDGET_WIDTH, WIDGET_HEIGHT,
                        Component.literal("Flip repeaters"),
                        (button, value) -> config.flipRepeaterFacing = value));

        addRenderableWidget(CycleButton.onOffBuilder(config.repairOnInvalidPlan)
                .create(rightColumn, top + rowY(row++), WIDGET_WIDTH, WIDGET_HEIGHT,
                        Component.literal("Let it fix mistakes"),
                        (button, value) -> config.repairOnInvalidPlan = value));

        addRenderableWidget(CycleButton.onOffBuilder(config.requireOperator)
                .create(rightColumn, top + rowY(row++), WIDGET_WIDTH, WIDGET_HEIGHT,
                        Component.literal("Operators only"),
                        (button, value) -> config.requireOperator = value));

        addRenderableWidget(new StringWidget(0, height - 48, width, 10, Component.literal(
                        "Flip repeaters if contraptions come out facing backwards.")
                .withStyle(ChatFormatting.DARK_GRAY), font));

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> saveAndClose())
                .bounds((width - 100) / 2, height - 28, 100, WIDGET_HEIGHT).build());
    }

    private static int rowY(int row) {
        return row * (WIDGET_HEIGHT + GAP);
    }

    private void saveAndClose() {
        if (AiBuildMod.saveConfig()) {
            AiBuildMod.reloadConfig();
        } else {
            AiBuildMod.LOGGER.warn("Could not write the AIBuild config from the build settings screen.");
        }
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public void onClose() {
        saveAndClose();
    }
}
