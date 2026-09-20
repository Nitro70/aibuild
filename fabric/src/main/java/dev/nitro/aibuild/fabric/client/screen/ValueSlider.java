package dev.nitro.aibuild.fabric.client.screen;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * A slider over a range of whole numbers.
 *
 * <p>Vanilla's slider works in 0 to 1, so the mapping to a real range lives here.
 * Only {@code updateMessage} and {@code applyValue} are overridden, which keeps
 * this clear of 26.3's reworked render pipeline entirely.
 */
public final class ValueSlider extends AbstractSliderButton {

    private final int min;
    private final int max;
    private final int step;
    private final IntFunction<Component> label;
    private final IntConsumer onChange;

    private int current;

    /**
     * @param step  rounding applied to the result, so a blocks slider can move in
     *              hundreds rather than ones
     * @param label renders the current value into the button's text
     */
    public ValueSlider(int x, int y, int width, int height,
                       int min, int max, int step, int initial,
                       IntFunction<Component> label, IntConsumer onChange) {
        super(x, y, width, height, Component.empty(), fraction(initial, min, max));
        this.min = min;
        this.max = max;
        this.step = Math.max(1, step);
        this.label = label;
        this.onChange = onChange;
        this.current = clamp(initial);
        updateMessage();
    }

    private static double fraction(int value, int min, int max) {
        if (max <= min) {
            return 0;
        }
        return (double) (Math.max(min, Math.min(max, value)) - min) / (max - min);
    }

    private int clamp(int value) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void updateMessage() {
        setMessage(label.apply(current));
    }

    @Override
    protected void applyValue() {
        int raw = min + (int) Math.round(value * (max - min));
        // Snap to the step, but never past the ends of the range.
        int snapped = Math.round((float) (raw - min) / step) * step + min;
        current = clamp(snapped);
        onChange.accept(current);
    }

    public int current() {
        return current;
    }
}
