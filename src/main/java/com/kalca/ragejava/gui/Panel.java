package com.kalca.ragejava.gui;

import com.kalca.ragejava.module.Category;
import com.kalca.ragejava.module.Module;
import com.kalca.ragejava.util.RenderUtil;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;

public class Panel {

    private static final float HEADER_HEIGHT = 16f;
    private static final float DEFAULT_WIDTH = 122f;
    private static final float SCROLLBAR_W = 3f;
    private static final float WHEEL_STEP = 14f;

    private final Category category;
    private final ClickGUI gui;
    private final List<ModuleButton> buttons = new ArrayList<>();

    private float x;
    private float y;
    private boolean collapsed;
    private float scroll;

    private boolean draggingHeader;
    private float dragOffsetX;
    private float dragOffsetY;

    private boolean draggingScrollbar;
    private float scrollGrab;

    private float forcedWidth = -1f;

    public Panel(Category category, ClickGUI gui) {
        this.category = category;
        this.gui = gui;
        for (Module module : gui.getClient().moduleManager.getModulesInCategory(category)) {
            buttons.add(new ModuleButton(module, this));
        }
    }

    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public Category getCategory() {
        return category;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    private List<ModuleButton> visibleButtons() {
        List<ModuleButton> out = new ArrayList<>();
        for (ModuleButton button : buttons) {
            if (button.matchesFilter(gui.getSearchField())) out.add(button);
        }
        return out;
    }

    private float contentHeight() {
        float h = 0f;
        for (ModuleButton button : visibleButtons()) h += button.getHeight();
        return h;
    }

    private float viewHeight() {
        return Math.min(contentHeight(), Math.max(0f, gui.getMaxPanelHeight() - HEADER_HEIGHT));
    }

    private float maxScroll() {
        return Math.max(0f, contentHeight() - viewHeight());
    }

    private boolean hasScrollbar() {
        return maxScroll() > 0f;
    }

    private float bodyWidth() {
        return hasScrollbar() ? getWidth() - SCROLLBAR_W - 2f : getWidth();
    }

    /**
     * Width the settings column needs on its own. The side preview is left out so the
     * uniform column width shared by the other panels does not balloon while ESP is open.
     */
    public float getBaseWidth() {
        float w = DEFAULT_WIDTH;
        for (ModuleButton button : visibleButtons()) {
            float bw = RenderUtil.getTextWidth(button.getModule().getName());
            float bindW = button.getBindWidget() == null ? 0 : RenderUtil.getTextWidth(button.getBindWidget().keyName());
            bw += bindW + 36;
            String tag = button.getModule().getTag();
            if (tag != null) bw += RenderUtil.getTextWidth(tag) + 8;
            for (Widget widget : button.getWidgets()) {
                bw = Math.max(bw, widget.getPreferredWidth() + 8);
            }
            w = Math.max(w, bw);
        }
        if (hasScrollbar()) w += SCROLLBAR_W + 2f;
        return w;
    }

    /** Full width this panel needs, including the preview column of an expanded module. */
    public float getNaturalWidth() {
        float w = getBaseWidth();
        if (collapsed) return w;
        for (ModuleButton button : visibleButtons()) {
            if (button.isExpanded() && button.hasSidePreview()) {
                w += ModuleButton.PREVIEW_WIDTH + 16;
                break;
            }
        }
        return w;
    }

    /**
     * The uniform column width is a floor, not a cap. ESP's side preview is drawn inside
     * the panel, so clamping the panel to the uniform width left the setting widgets only
     * about a third of the row and made their labels and values draw over each other.
     */
    public float getWidth() {
        if (forcedWidth <= 0f) return getNaturalWidth();
        return hasExpandedPreview() ? Math.max(forcedWidth, getNaturalWidth()) : forcedWidth;
    }

    private boolean hasExpandedPreview() {
        if (collapsed) return false;
        for (ModuleButton button : buttons) {
            if (button.hasSidePreview() && button.isExpanded()) return true;
        }
        return false;
    }

    public void setForcedWidth(float width) {
        this.forcedWidth = width;
    }

    public float getHeight() {
        return HEADER_HEIGHT + (collapsed ? 0f : viewHeight());
    }

    public boolean isInHeader(float mx, float my) {
        return mx >= x && mx <= x + getWidth() && my >= y && my <= y + HEADER_HEIGHT;
    }

    public boolean contains(float mx, float my) {
        return mx >= x && mx <= x + getWidth() && my >= y && my <= y + getHeight();
    }

    public void startHeaderDrag(float mx, float my) {
        draggingHeader = true;
        dragOffsetX = mx - x;
        dragOffsetY = my - y;
    }

    public void updateHeaderDrag(float mx, float my) {
        if (!draggingHeader) return;
        if (!Mouse.isButtonDown(0)) {
            draggingHeader = false;
            return;
        }
        x = mx - dragOffsetX;
        y = my - dragOffsetY;
    }

    public void stopHeaderDrag() {
        draggingHeader = false;
        draggingScrollbar = false;
    }

    public boolean isDragging() {
        return draggingHeader;
    }

    public void toggleCollapsed() {
        collapsed = !collapsed;
        scroll = 0f;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void scrollBy(float amount) {
        if (collapsed) return;
        scroll = clampScroll(scroll + amount);
    }

    private float clampScroll(float value) {
        float max = maxScroll();
        return value < 0f ? 0f : (value > max ? max : value);
    }

    public BindWidget getCapturingBind() {
        for (ModuleButton button : buttons) {
            BindWidget bindWidget = button.getBindWidget();
            if (bindWidget != null && bindWidget.isCapturing()) return bindWidget;
        }
        return null;
    }

    public TextInput getCapturingText() {
        for (ModuleButton button : buttons) {
            for (Widget widget : button.getWidgets()) {
                if (widget instanceof TextInput && ((TextInput) widget).isTextCapturing()) {
                    return (TextInput) widget;
                }
            }
        }
        return null;
    }

    public void draw(float mx, float my) {
        updateHeaderDrag(mx, my);
        if (draggingScrollbar) updateScrollbarDrag(my);

        scroll = clampScroll(scroll);

        float height = getHeight();
        float width = getWidth();
        RenderUtil.drawRect(x - 1, y - 1, width + 2, 1, Theme.PANEL_BORDER);
        RenderUtil.drawRect(x - 1, y + height, width + 2, 1, Theme.PANEL_BORDER);
        RenderUtil.drawRect(x - 1, y - 1, 1, height + 2, Theme.PANEL_BORDER);
        RenderUtil.drawRect(x + width, y - 1, 1, height + 2, Theme.PANEL_BORDER);
        RenderUtil.drawRect(x, y, width, height, Theme.PANEL_BG);
        RenderUtil.drawRect(x, y, width, HEADER_HEIGHT, Theme.HEADER_BG);

        RenderUtil.drawString(category.getName().toUpperCase(), x + 7, y + (HEADER_HEIGHT - RenderUtil.getTextHeight()) / 2f, Theme.TEXT);
        RenderUtil.drawString(collapsed ? "+" : "-", x + width - 12, y + (HEADER_HEIGHT - RenderUtil.getTextHeight()) / 2f, Theme.TEXT_GRAY);

        RenderUtil.drawRect(x + 6, y + HEADER_HEIGHT - 1, width - 12, 1, 0xAA00CFFF);

        if (collapsed) return;

        float bodyTop = y + HEADER_HEIGHT;
        float view = viewHeight();
        float inner = bodyWidth();
        float by = bodyTop - scroll;
        for (ModuleButton button : visibleButtons()) {
            float bh = button.getHeight();
            if (by + bh > bodyTop && by < bodyTop + view) {
                button.setPosition(x, by, inner);
                button.draw(mx, my);
            }
            by += bh;
        }

        if (hasScrollbar()) drawScrollbar(view);
    }

    private void drawScrollbar(float view) {
        float trackTop = y + HEADER_HEIGHT;
        RenderUtil.drawRect(x + getWidth() - SCROLLBAR_W - 1, trackTop, SCROLLBAR_W, view, 0x1AFFFFFF);

        float content = contentHeight();
        float thumbH = Math.max(18f, view * (view / content));
        float t = maxScroll() <= 0f ? 0f : scroll / maxScroll();
        float thumbY = trackTop + t * (view - thumbH);
        RenderUtil.drawRect(x + getWidth() - SCROLLBAR_W - 1, thumbY, SCROLLBAR_W, thumbH, 0xAA33D6FF);
    }

    private void updateScrollbarDrag(float my) {
        if (!Mouse.isButtonDown(0)) {
            draggingScrollbar = false;
            return;
        }
        float view = viewHeight();
        float content = contentHeight();
        float thumbH = Math.max(18f, view * (view / content));
        float trackTop = y + HEADER_HEIGHT;
        float usable = Math.max(1f, view - thumbH);
        scroll = clampScroll((my - scrollGrab - trackTop) / usable * maxScroll());
    }

    private boolean onScrollbar(float mx, float my) {
        if (!hasScrollbar() || collapsed) return false;
        return mx >= x + getWidth() - SCROLLBAR_W - 4
                && my >= y + HEADER_HEIGHT
                && my <= y + getHeight();
    }

    public boolean onClick(float mx, float my, int button) {
        if (my <= y + HEADER_HEIGHT) return false;

        if (onScrollbar(mx, my)) {
            if (button == 0) {
                float view = viewHeight();
                float content = contentHeight();
                float thumbH = Math.max(18f, view * (view / content));
                float trackTop = y + HEADER_HEIGHT;
                float t = Math.max(0f, Math.min(1f, (my - trackTop) / Math.max(1f, view)));
                scroll = clampScroll(t * maxScroll());
                draggingScrollbar = true;
                scrollGrab = my - (trackTop + t * (view - thumbH));
            }
            return true;
        }

        if (mx > x + bodyWidth()) return false;

        float bodyTop = y + HEADER_HEIGHT;
        float by = bodyTop - scroll;
        for (ModuleButton moduleButton : visibleButtons()) {
            float bh = moduleButton.getHeight();
            if (by + bh > bodyTop && my >= by && my <= by + bh) {
                return moduleButton.onClick(mx, my, button);
            }
            by += bh;
        }
        return false;
    }

    public ClickGUI getGui() {
        return gui;
    }
}
