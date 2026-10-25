package com.kalca.ragejava.gui;

import com.kalca.ragejava.module.Esp;
import com.kalca.ragejava.module.Module;
import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.settings.ColorSetting;
import com.kalca.ragejava.settings.ModeSetting;
import com.kalca.ragejava.settings.Setting;
import com.kalca.ragejava.settings.SliderSetting;
import com.kalca.ragejava.settings.StringSetting;
import com.kalca.ragejava.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.AxisAlignedBB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModuleButton {

    private static final Logger LOGGER = LogManager.getLogger("RageJava");
    private static final float ROW_HEIGHT = 14f;
    private static final float EXPAND_ZONE = 12f;
    public static final float PREVIEW_WIDTH = 88f;
    private static final float PREVIEW_MIN_HEIGHT = 74f;

    private final Module module;
    private final Panel panel;
    private final List<Widget> widgets = new ArrayList<>();
    private final List<Setting> builtSettings = new ArrayList<>();
    private final Minecraft mc = Minecraft.getMinecraft();
    private BindWidget bindWidget;
    private boolean expanded;
    private EntityOtherPlayerMP previewPlayer;

    private float x;
    private float y;
    private float width;

    public ModuleButton(Module module, Panel panel) {
        this.module = module;
        this.panel = panel;
        refreshWidgets();
    }

    public void setPosition(float x, float y, float width) {
        this.x = x;
        this.y = y;
        this.width = width;
    }

    public float getHeight() {
        float h = ROW_HEIGHT;
        if (expanded) h += Math.max(widgetsHeight(), getPreviewMinHeight());
        return h;
    }

    public boolean hasSidePreview() {
        return module instanceof Esp;
    }

    public float getPreviewMinHeight() {
        return hasSidePreview() ? PREVIEW_MIN_HEIGHT : 0;
    }

    private float widgetsHeight() {
        float h = 0;
        for (Widget widget : widgets) h += widget.getHeight();
        return h;
    }

    private void refreshWidgets() {
        SearchField search = panel.getGui().getSearchField();
        List<Setting> visible = new ArrayList<>();
        for (Setting setting : module.getSettings()) {
            if (!module.isSettingVisible(setting)) continue;
            if (!search.matches(setting.getName())) continue;
            visible.add(setting);
        }
        boolean same = builtSettings.size() == visible.size();
        if (same) {
            for (int i = 0; i < visible.size(); i++) {
                if (builtSettings.get(i) != visible.get(i)) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return;

        widgets.clear();
        builtSettings.clear();
        for (Setting setting : visible) {
            if (setting instanceof SliderSetting) {
                widgets.add(new SliderWidget((SliderSetting) setting));
            } else if (setting instanceof BooleanSetting) {
                widgets.add(new BooleanWidget((BooleanSetting) setting));
            } else if (setting instanceof ModeSetting) {
                widgets.add(new ModeWidget((ModeSetting) setting));
            } else if (setting instanceof ColorSetting) {
                widgets.add(new ColorWidget((ColorSetting) setting));
            } else if (setting instanceof StringSetting) {
                widgets.add(new StringWidget((StringSetting) setting));
            }
            builtSettings.add(setting);
        }
        if (module.canBind()) {
            bindWidget = new BindWidget(module);
            widgets.add(bindWidget);
        }
    }

    public boolean isExpanded() {
        return expanded;
    }

    /** Hidden entirely while a search is active and no setting of this module matches. */
    public boolean matchesFilter(SearchField search) {
        if (search == null || !search.isFiltering()) return true;
        for (Setting setting : module.getSettings()) {
            if (module.isSettingVisible(setting) && search.matches(setting.getName())) return true;
        }
        return false;
    }

    public BindWidget getBindWidget() {
        return bindWidget;
    }

    public List<Widget> getWidgets() {
        return widgets;
    }

    public Module getModule() {
        return module;
    }

    public void draw(float mx, float my) {
        refreshWidgets();
        boolean hovered = mx >= x && mx <= x + width && my >= y && my <= y + ROW_HEIGHT;

        if (module.isEnabled()) {
            RenderUtil.drawRect(x, y, 2, ROW_HEIGHT, Theme.ACCENT);
        }

        if (hovered) {
            RenderUtil.drawRect(x + 2, y, width - 2, ROW_HEIGHT, Theme.HOVER);
        }

        int textColor = module.isEnabled() ? Theme.TEXT : Theme.TEXT_GRAY;
        RenderUtil.drawString(module.getName(), x + 7, y + (ROW_HEIGHT - RenderUtil.getTextHeight()) / 2f, textColor);

        String indicator = "+";
        RenderUtil.drawString(indicator, x + width - 4 - RenderUtil.getTextWidth(indicator),
                y + (ROW_HEIGHT - RenderUtil.getTextHeight()) / 2f, hovered ? Theme.TEXT : Theme.TEXT_GRAY);

        if (expanded) {
            float wh = Math.max(widgetsHeight(), getPreviewMinHeight());
            boolean preview = hasSidePreview();
            RenderUtil.drawRect(x, y + ROW_HEIGHT, width, wh, Theme.BODY_BG);

            float bodyRight = x + width - 4;
            float pw = PREVIEW_WIDTH;
            float px = preview ? bodyRight - pw + 3 : bodyRight;
            float widgetW = preview ? px - (x + 4) - 8 : width - 8;

            float wy = y + ROW_HEIGHT;
            for (Widget widget : widgets) {
                widget.setPosition(x + 4, wy, widgetW, widget.getHeight());
                widget.draw(mx, my);
                wy += widget.getHeight();
            }
            if (preview) {
                RenderUtil.drawRect(px - 5, y + ROW_HEIGHT, 1, wh, Theme.SEPARATOR);
                drawEspPreview((Esp) module, px, y + ROW_HEIGHT, pw, wh);
            }
            RenderUtil.drawRect(x + 4, y + ROW_HEIGHT + wh - 1, width - 8, 1, Theme.SEPARATOR);
        } else {
            RenderUtil.drawRect(x + 4, y + ROW_HEIGHT - 1, width - 8, 1, Theme.SEPARATOR);
        }
    }

    private void drawEspPreview(Esp esp, float px, float py, float pw, float ph) {
        EntityOtherPlayerMP player = getPreviewEntity();
        if (player == null) return;

        int color = esp.getPreviewColor();
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        boolean draw3D = Esp.MODE_3D.equals(esp.getPreviewMode());

        logPreviewLayout(px, pw, x, width);

        float cx = px + pw / 2f;
        float top = py + 4f;
        float bottom = py + ph - 4f;
        float scale = (bottom - top) / 1.8f;

        boolean depthWas = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);

        GlStateManager.enableColorMaterial();
        GlStateManager.enableRescaleNormal();
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, bottom, 80.0F);
        GlStateManager.scale(-scale, scale, scale);
        GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(135.0F, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        if (!depthWas) GlStateManager.enableDepth();
        mc.getRenderManager().renderEntityWithPosYaw(player, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F);
        RenderHelper.disableStandardItemLighting();

        if (draw3D) {
            GlStateManager.disableDepth();
            GlStateManager.disableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            AxisAlignedBB box = AxisAlignedBB.fromBounds(-0.1D, -0.1D, -0.1D, 0.7D, 1.9D, 0.7D);
            RenderUtil.drawFilledBox(Tessellator.getInstance(), box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, r, g, b, 100);
            GlStateManager.enableTexture2D();
        }
        GlStateManager.popMatrix();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableColorMaterial();

        if (depthWas) {
            GlStateManager.enableDepth();
        } else {
            GlStateManager.disableDepth();
        }

        if (!draw3D) {
            float h = bottom - top;
            float w = h * 0.5f;
            float xr = cx - w / 2f;
            float len = Math.max(4f, Math.min(h / 6f, 10f));
            if (esp.getPreviewCorners()) {
                RenderUtil.drawRect(xr, top, len, 1, color);
                RenderUtil.drawRect(xr, top, 1, len, color);
                RenderUtil.drawRect(xr + w - len, top, len, 1, color);
                RenderUtil.drawRect(xr + w - 1, top, 1, len, color);
                RenderUtil.drawRect(xr, top + h - 1, len, 1, color);
                RenderUtil.drawRect(xr, top + h - len, 1, len, color);
                RenderUtil.drawRect(xr + w - len, top + h - 1, len, 1, color);
                RenderUtil.drawRect(xr + w - 1, top + h - len, 1, len, color);
            } else {
                RenderUtil.drawRect(xr, top, w, 1, color);
                RenderUtil.drawRect(xr, top + h - 1, w, 1, color);
                RenderUtil.drawRect(xr, top, 1, h, color);
                RenderUtil.drawRect(xr + w - 1, top, 1, h, color);
            }
        }
        if (esp.getPreviewNames()) {
            String name = "[Steve]";
            RenderUtil.drawString(name, cx - RenderUtil.getTextWidth(name) / 2f, top - 11f, esp.getPreviewNameColor());
        }
    }

    private EntityOtherPlayerMP getPreviewEntity() {
        if (mc.theWorld == null || mc.getSession() == null) return null;
        if (previewPlayer == null || previewPlayer.worldObj != mc.theWorld) {
            previewPlayer = new EntityOtherPlayerMP(mc.theWorld, mc.getSession().getProfile());
        }

        double rx = getRenderPos("renderPosX");
        double ry = getRenderPos("renderPosY");
        double rz = getRenderPos("renderPosZ");
        previewPlayer.setPosition(rx, ry, rz);
        previewPlayer.lastTickPosX = rx;
        previewPlayer.lastTickPosY = ry;
        previewPlayer.lastTickPosZ = rz;
        previewPlayer.prevPosX = rx;
        previewPlayer.prevPosY = ry;
        previewPlayer.prevPosZ = rz;
        previewPlayer.rotationYaw = 0;
        previewPlayer.rotationPitch = 0;
        previewPlayer.rotationYawHead = 0;
        previewPlayer.renderYawOffset = 0;
        previewPlayer.limbSwing = 0;
        previewPlayer.limbSwingAmount = 0;
        previewPlayer.prevLimbSwingAmount = 0;
        previewPlayer.swingProgress = 0;
        previewPlayer.prevSwingProgress = 0;
        previewPlayer.onGround = true;
        previewPlayer.hurtTime = 0;
        return previewPlayer;
    }

    private static final Map<String, Field> RENDER_POS_FIELDS = new HashMap<>();

    private float lastLoggedPx = Float.NaN;

    private void logPreviewLayout(float px, float pw, float buttonX, float buttonW) {
        if (lastLoggedPx != px) {
            lastLoggedPx = px;
            LOGGER.info("ESP preview at px={} pw={} buttonX={} buttonW={}", px, pw, buttonX, buttonW);
        }
    }

    private double getRenderPos(String name) {
        try {
            Field field = RENDER_POS_FIELDS.get(name);
            if (field == null) {
                field = RenderManager.class.getDeclaredField(name);
                field.setAccessible(true);
                RENDER_POS_FIELDS.put(name, field);
            }
            return field.getDouble(mc.getRenderManager());
        } catch (Exception ignored) {
            return 0.0D;
        }
    }

    public boolean onClick(float mx, float my, int button) {
        if (my > y + ROW_HEIGHT) {
            if (!expanded) return false;
            for (Widget widget : widgets) {
                if (widget.contains(mx, my)) {
                    return widget.onClick(mx, my, button);
                }
            }
            return false;
        }

        if (button == 1) {
            expanded = !expanded;
            return true;
        }

        module.toggle();
        return true;
    }
}