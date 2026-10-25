package com.kalca.ragejava.gui;

import com.kalca.ragejava.RageJava;
import com.kalca.ragejava.module.Category;
import com.kalca.ragejava.module.Interface;
import com.kalca.ragejava.module.Module;
import com.kalca.ragejava.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ClickGUI extends GuiScreen {

    private static final float PANEL_MARGIN = 12f;
    private static final float PANEL_GAP = 6f;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final List<Panel> panels = new ArrayList<>();
    private final SearchField searchField = new SearchField();
    private final List<Float> dragOffsets = new ArrayList<>();

    private float maxPanelHeight = 200f;

    public ClickGUI() {
        for (Category category : Category.values()) {
            panels.add(new Panel(category, this));
        }
    }

    public RageJava getClient() {
        return RageJava.INSTANCE;
    }

    public SearchField getSearchField() {
        return searchField;
    }

    public float getMaxPanelHeight() {
        return maxPanelHeight;
    }

    @Override
    public void initGui() {
        layout();
        // Uniform column width comes from the settings columns only; a panel with a
        // side preview grows past it instead of squeezing its widgets into it.
        float maxWidth = 0f;
        for (Panel panel : panels) {
            maxWidth = Math.max(maxWidth, panel.getBaseWidth());
        }
        for (Panel panel : panels) {
            panel.setForcedWidth(maxWidth);
        }
        float x = PANEL_MARGIN / 2f;
        for (Panel panel : panels) {
            panel.setPosition(x, PANEL_MARGIN / 2f);
            x += panel.getWidth() + PANEL_GAP;
        }
    }

    private void layout() {
        ScaledResolution sr = new ScaledResolution(mc);
        maxPanelHeight = Math.max(60f, sr.getScaledHeight() - PANEL_MARGIN);
        searchField.layout(sr.getScaledHeight());
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);

        ScaledResolution sr = new ScaledResolution(mc);
        RenderUtil.drawRect(0, 0, sr.getScaledWidth(), sr.getScaledHeight(), getDimColor());

        layout();
        reflow();
        for (Panel panel : panels) {
            panel.draw(mouseX, mouseY);
        }
        // After every panel, so an open mode list is not painted over by the settings below it.
        ModeWidget.drawOpenPopup(mouseX, mouseY, sr.getScaledHeight() - 4f);
        searchField.draw(mouseX, mouseY);
    }

    private void reflow() {
        // Recalculate uniform width from the settings columns, so expanding a panel
        // with a side preview widens only that panel.
        float maxWidth = 0f;
        for (Panel panel : panels) {
            maxWidth = Math.max(maxWidth, panel.getBaseWidth());
        }
        for (Panel panel : panels) {
            panel.setForcedWidth(maxWidth);
        }

        float nx = PANEL_MARGIN / 2f;
        for (int i = 0; i < panels.size(); i++) {
            Panel panel = panels.get(i);
            float natural = nx;
            while (dragOffsets.size() <= i) dragOffsets.add(0f);
            if (!panel.isDragging()) {
                dragOffsets.set(i, panel.getX() - natural);
                panel.setPosition(natural + dragOffsets.get(i), panel.getY());
            }
            nx = natural + panel.getWidth() + PANEL_GAP;
        }
    }

    private int getDimColor() {
        int alpha = 153;
        if (RageJava.INSTANCE != null) {
            Module iface = RageJava.INSTANCE.moduleManager.getModule("Interface");
            if (iface instanceof Interface) {
                alpha = ((Interface) iface).getBgDimAlpha();
            }
        }
        return (alpha << 24) | 0x000000;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mx = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int my = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        for (Panel panel : panels) {
            if (panel.isCollapsed() || !panel.contains(mx, my)) continue;
            panel.scrollBy(-wheel * 14f);
            return;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        TextInput capturing = findCapturingText();
        if (capturing != null) {
            if (capturing.hitsTextField(mouseX, mouseY)) {
                capturing.onClick(mouseX, mouseY, mouseButton);
                return;
            } else {
                capturing.commitTextInput();
            }
        }

        // A mode list swallows every click while it is open: pick, or dismiss without
        // letting the click through to the module row and toggle it.
        if (ModeWidget.handleOpenPopupClick(mouseX, mouseY, mouseButton)) {
            return;
        }

        if (searchField.onClick(mouseX, mouseY, mouseButton)) {
            return;
        }

        for (Panel panel : panels) {
            if (panel.isInHeader(mouseX, mouseY)) {
                if (mouseButton == 0) {
                    if (mouseX >= panel.getX() + panel.getWidth() - 12 && mouseX <= panel.getX() + panel.getWidth()) {
                        panel.toggleCollapsed();
                    } else {
                        panel.startHeaderDrag(mouseX, mouseY);
                    }
                }
                return;
            }
        }

        for (Panel panel : panels) {
            if (!panel.isCollapsed() && panel.contains(mouseX, mouseY)) {
                panel.onClick(mouseX, mouseY, mouseButton);
                return;
            }
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        for (Panel panel : panels) {
            panel.stopHeaderDrag();
        }
        getClient().saveConfig();
    }

    private TextInput findCapturingText() {
        for (Panel panel : panels) {
            TextInput input = panel.getCapturingText();
            if (input != null) return input;
        }
        return null;
    }

    private BindWidget findCapturingBind() {
        for (Panel panel : panels) {
            BindWidget widget = panel.getCapturingBind();
            if (widget != null) return widget;
        }
        return null;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE && ModeWidget.isOpen()) {
            ModeWidget.closeOpen();
            return;
        }

        TextInput textInput = findCapturingText();
        if (textInput != null) {
            textInput.onTextKey(typedChar, keyCode);
            return;
        }

        if (searchField.isFocused()) {
            searchField.onKey(typedChar, keyCode);
            return;
        }

        BindWidget capturing = findCapturingBind();
        if (capturing != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                capturing.cancelCapture();
            } else if (keyCode == Keyboard.KEY_DELETE) {
                capturing.setBind(-1);
            } else {
                capturing.setBind(keyCode);
            }
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {
            return;
        }

        if (RageJava.INSTANCE != null) {
            if (RageJava.INSTANCE.clickGuiKey.getKeyCode() == keyCode) {
                close();
                return;
            }
            for (Module module : getClient().moduleManager.getModules()) {
                if (module.getKey() == keyCode && module.getKey() != -1) {
                    module.toggle();
                    return;
                }
            }
        }

        super.keyTyped(typedChar, keyCode);
    }

    public void close() {
        TextInput textInput = findCapturingText();
        if (textInput != null) textInput.commitTextInput();
        searchField.unfocus();
        mc.displayGuiScreen(null);
        getClient().saveConfig();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
