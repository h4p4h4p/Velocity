package com.kalca.ragejava.gui;

import com.kalca.ragejava.RageJava;
import com.kalca.ragejava.settings.SliderSetting;
import com.kalca.ragejava.util.RenderUtil;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public class SliderWidget extends Widget implements TextInput {

    private static final int MAX_CHARS = 9;

    private final SliderSetting setting;
    private float trackX;
    private float trackW;
    private boolean dragging;

    private boolean capturing;
    private final StringBuilder buffer = new StringBuilder();

    public SliderWidget(SliderSetting setting) {
        this.setting = setting;
    }

    public boolean isDragging() {
        return dragging;
    }

    public void setDragging(boolean dragging) {
        this.dragging = dragging;
        if (!dragging) {
            RageJava.scheduleSave();
        }
    }

    @Override
    public void draw(float mx, float my) {
        if (dragging) {
            if (!Mouse.isButtonDown(0)) {
                dragging = false;
                RageJava.scheduleSave();
            } else {
                update(mx);
            }
        }

        boolean hovered = contains(mx, my);
        computeTrack();
        float valueX = x + width - 6 - RenderUtil.getTextWidth(display());

        if (capturing) {
            RenderUtil.drawRect(valueX - 3, y + 1, RenderUtil.getTextWidth(display()) + 6, height - 2, 0x40219609);
        }

        RenderUtil.drawString(setting.getName(), x + 6, y + 4, hovered ? Theme.TEXT : Theme.TEXT_GRAY);
        drawModifiedMarker(!setting.isDefault(), setting.getName());

        double range = setting.getMax() - setting.getMin();
        double frac = (setting.getValue() - setting.getMin()) / range;
        float fill = (float) (trackW * frac);

        RenderUtil.drawRect(trackX, y + height / 2f - 2f, trackW, 4, Theme.TRACK);
        if (fill > 0.5f) {
            RenderUtil.drawRect(trackX, y + height / 2f - 2f, Math.min(fill, trackW), 4, dragging || hovered ? MARKER : Theme.ACCENT);
        }

        float knobSize = 6f;
        float knobX = trackX + fill - knobSize / 2f;
        knobX = Math.max(trackX, Math.min(knobX, trackX + trackW - knobSize));
        RenderUtil.drawRect(knobX, y + height / 2f - 4f, knobSize, 8, Theme.KNOB);

        String value = display();
        RenderUtil.drawString(value, valueX, y + 4, capturing ? MARKER : Theme.ACCENT);
        if (capturing && (System.currentTimeMillis() / 450L) % 2 == 0) {
            RenderUtil.drawRect(valueX + RenderUtil.getTextWidth(value) + 1, y + 3, 1, RenderUtil.getTextHeight(), MARKER);
        }
    }

    private String display() {
        if (capturing) return buffer.length() == 0 ? "..." : buffer.toString();
        return formatValue();
    }

    private String formatValue() {
        int decimals = setting.getStep() >= 1 ? 0 : (setting.getStep() >= 0.1 ? 1 : 2);
        return String.format("%." + decimals + "f", setting.getValue());
    }

    private void computeTrack() {
        float labelZone = RenderUtil.getTextWidth(setting.getName()) + 10;
        if (!setting.isDefault()) labelZone += 7;
        trackX = x + labelZone;
        float maxVw = RenderUtil.getTextWidth(formatMaxValue());
        trackW = Math.max((x + width - 12 - maxVw) - trackX, 4);
    }

    private String formatMaxValue() {
        int decimals = setting.getStep() >= 1 ? 0 : (setting.getStep() >= 0.1 ? 1 : 2);
        return String.format("%." + decimals + "f", setting.getMax());
    }

    @Override
    public float getPreferredWidth() {
        float marker = setting.isDefault() ? 0 : 7;
        return Math.max(110, RenderUtil.getTextWidth(setting.getName()) + marker + 10 + 48 + RenderUtil.getTextWidth(formatMaxValue()) + 10);
    }

    private boolean onTrack(float mx, float my) {
        float ty = y + height / 2f - 2f;
        return mx >= trackX - 4 && mx <= trackX + trackW + 4 && my >= ty - 4 && my <= ty + 8;
    }

    @Override
    public boolean onClick(float mx, float my, int button) {
        if (button != 0) return false;
        if (hitsTextField(mx, my)) {
            capturing = true;
            dragging = false;
            buffer.setLength(0);
            return true;
        }
        computeTrack();
        if (!onTrack(mx, my)) return false;
        dragging = true;
        update(mx);
        return true;
    }

    private void update(float mx) {
        double frac = MathHelper.clamp_double((mx - trackX) / Math.max(trackW, 1f), 0, 1);
        double range = setting.getMax() - setting.getMin();
        double raw = setting.getMin() + frac * range;
        double snapped = Math.round(raw / setting.getStep()) * setting.getStep();
        setting.setValue(snapped);
    }

    @Override
    public boolean isTextCapturing() {
        return capturing;
    }

    @Override
    public boolean hitsTextField(float mx, float my) {
        String shown = formatValue();
        float vw = RenderUtil.getTextWidth(shown);
        float vx = x + width - 6 - vw;
        return mx >= vx - 3 && mx <= x + width - 3 && my >= y && my <= y + height;
    }

    @Override
    public boolean onTextKey(char typedChar, int keyCode) {
        if (!capturing) return false;
        if (keyCode == Keyboard.KEY_ESCAPE) {
            cancelTextInput();
            return true;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            commitTextInput();
            return true;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (buffer.length() > 0) buffer.setLength(buffer.length() - 1);
            return true;
        }
        if (accepts(typedChar) && buffer.length() < MAX_CHARS) {
            buffer.append(typedChar);
        }
        return true;
    }

    /** Allows negatives and decimals, which the -1..1 multiplier sliders need. */
    private boolean accepts(char c) {
        if (c >= '0' && c <= '9') return true;
        if (c == '-') return buffer.length() == 0;
        return c == '.' && buffer.indexOf(".") < 0;
    }

    @Override
    public void commitTextInput() {
        if (!capturing) return;
        capturing = false;
        if (buffer.length() > 0) {
            try {
                double parsed = Double.parseDouble(buffer.toString());
                double snapped = Math.round(parsed / setting.getStep()) * setting.getStep();
                setting.setValue(snapped);
            } catch (NumberFormatException ignored) {
            }
        }
        buffer.setLength(0);
        RageJava.scheduleSave();
    }

    @Override
    public void cancelTextInput() {
        capturing = false;
        buffer.setLength(0);
    }
}
