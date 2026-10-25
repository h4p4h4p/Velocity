package com.kalca.ragejava.gui;

import com.kalca.ragejava.RageJava;
import com.kalca.ragejava.module.Module;
import com.kalca.ragejava.util.RenderUtil;
import org.lwjgl.input.Keyboard;

public class BindWidget extends Widget {

    private final Module module;
    private boolean capturing;

    public BindWidget(Module module) {
        this.module = module;
    }

    @Override
    public void draw(float mx, float my) {
        RenderUtil.drawString("Keybind", x + 6, y + 4, Theme.TEXT_GRAY);

        String value;
        int color;
        if (capturing) {
            boolean blink = (System.currentTimeMillis() / 450L) % 2 == 0;
            value = "Listening...";
            color = blink ? Theme.ACCENT : Theme.TEXT_GRAY;
        } else {
            value = keyName();
            color = module.isEnabled() ? Theme.ACCENT : Theme.TEXT_GRAY;
        }

        int vw = RenderUtil.getTextWidth(value);
        RenderUtil.drawString(value, x + width - 6 - vw, y + 4, color);
    }

    @Override
    public boolean onClick(float mx, float my, int button) {
        if (button != 0) return false;
        capturing = true;
        return true;
    }

    public boolean isCapturing() {
        return capturing;
    }

    public void setBind(int key) {
        module.setKey(key);
        capturing = false;
        RageJava.scheduleSave();
    }

    public void cancelCapture() {
        capturing = false;
    }

    public String keyName() {
        int key = module.getKey();
        if (key <= 0) return "None";
        String name = Keyboard.getKeyName(key);
        if (name == null || name.isEmpty()) name = "Key " + key;
        return module.isHoldBind() ? name + " (Hold)" : name;
    }

    @Override
    public float getPreferredWidth() {
        String longest = "Listening...";
        if (RenderUtil.getTextWidth(keyName()) > RenderUtil.getTextWidth(longest)) {
            longest = keyName();
        }
        return Math.max(110, RenderUtil.getTextWidth("Keybind") + RenderUtil.getTextWidth(longest) + 24);
    }
}