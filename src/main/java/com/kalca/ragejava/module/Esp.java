package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.settings.ColorSetting;
import com.kalca.ragejava.settings.ModeSetting;
import com.kalca.ragejava.settings.Setting;
import com.kalca.ragejava.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.List;

public class Esp extends Module {

    public static final String MODE_2D = "2D";
    public static final String MODE_3D = "3D";

    private static final double NEAR_CAMERA_MARGIN = 1.2D;
    private static final int MAX_QUADS = 16384;

    private final ModeSetting modeSetting = new ModeSetting("Mode", new String[]{MODE_2D, MODE_3D}, ModeSetting.UNSET);
    private final BooleanSetting cornersSetting = new BooleanSetting("Corners", false);
    private final BooleanSetting namesSetting = new BooleanSetting("Names", true);
    private final BooleanSetting legitSetting = new BooleanSetting("LegitESP", false);
    private final ColorSetting colorSetting = new ColorSetting("Color", 0xFF1B395C);
    private final ColorSetting nameColorSetting = new ColorSetting("Name Color", 0xFFFFFFFF);

    private final Minecraft mc = Minecraft.getMinecraft();

    private final float[] mv = new float[16];
    private final float[] pr = new float[16];
    private final double[] feetScratch = new double[2];
    private final double[] headScratch = new double[2];
    private IntBuffer viewport;
    private FloatBuffer mvBuf;
    private FloatBuffer prBuf;
    private int vw;
    private int vh;
    private double scale = 1.0D;
    private int lastDisplayWidth = -1;
    private int lastDisplayHeight = -1;

    private float[] quadBuf = new float[512];
    private int quadCount;

    private String[] nameTexts = new String[64];
    private float[] nameXs = new float[64];
    private float[] nameYs = new float[64];
    private int nameCount;

    public Esp() {
        super("ESP", Category.RENDER);
        settings.add(modeSetting);
        settings.add(cornersSetting);
        settings.add(namesSetting);
        settings.add(legitSetting);
        settings.add(colorSetting);
        settings.add(nameColorSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == cornersSetting) return MODE_2D.equals(mode());
        if (setting == nameColorSetting) return namesSetting.getValue();
        return true;
    }

    private String mode() {
        return modeSetting.getValue();
    }

    public String getPreviewMode() {
        return mode();
    }

    public int getPreviewColor() {
        return colorSetting.getValue();
    }

    public int getPreviewNameColor() {
        return nameColorSetting.getValue();
    }

    public boolean getPreviewCorners() {
        return cornersSetting.getValue();
    }

    public boolean getPreviewNames() {
        return namesSetting.getValue();
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        quadCount = 0;
        nameCount = 0;
        if (!captureMatrices()) return;
        if (modeSetting.isUnset()) return;
        if (MODE_3D.equals(mode())) {
            render3D(event.partialTicks);
        } else {
            collect2D(event.partialTicks);
        }
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (!isEnabled()) return;
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (MODE_2D.equals(mode()) && quadCount > 0) {
            RenderUtil.drawBatchedRects(quadBuf, quadCount, colorSetting.getValue());
        }
        if (nameCount > 0) {
            int nameColor = nameColorSetting.getValue();
            for (int i = 0; i < nameCount; i++) {
                String text = nameTexts[i];
                RenderUtil.drawString(text, nameXs[i] - RenderUtil.getTextWidth(text) * 0.5f, nameYs[i] - 9f, nameColor);
            }
        }
    }

    @SubscribeEvent
    public void onRenderName(RenderLivingEvent.Specials.Pre event) {
        if (!isEnabled()) return;
        if (!namesSetting.getValue()) return;
        if (event.entity instanceof net.minecraft.entity.player.EntityPlayer) {
            event.setCanceled(true);
        }
    }

    

    private boolean captureMatrices() {
        if (mvBuf == null) {
            viewport = BufferUtils.createIntBuffer(16);
            mvBuf = BufferUtils.createFloatBuffer(16);
            prBuf = BufferUtils.createFloatBuffer(16);
        }
        mvBuf.clear();
        prBuf.clear();
        viewport.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, mvBuf);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, prBuf);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        mvBuf.get(mv);
        prBuf.get(pr);
        vw = viewport.get(2);
        vh = viewport.get(3);
        if (vw <= 0 || vh <= 0) return false;

        if (mc.displayWidth != lastDisplayWidth || mc.displayHeight != lastDisplayHeight) {
            lastDisplayWidth = mc.displayWidth;
            lastDisplayHeight = mc.displayHeight;
            scale = vh / (double) new ScaledResolution(mc).getScaledHeight();
        }
        return scale > 0;
    }

    private void storeName(String text, double screenX, double screenY) {
        if (nameCount == nameTexts.length) {
            nameTexts = Arrays.copyOf(nameTexts, nameCount * 2);
            nameXs = Arrays.copyOf(nameXs, nameCount * 2);
            nameYs = Arrays.copyOf(nameYs, nameCount * 2);
        }
        nameTexts[nameCount] = "[" + text + "]";
        nameXs[nameCount] = (float) (screenX / scale);
        nameYs[nameCount] = (float) ((vh - screenY) / scale);
        nameCount++;
    }

    private void render3D(float partialTicks) {
        double camX = mc.getRenderManager().viewerPosX;
        double camY = mc.getRenderManager().viewerPosY;
        double camZ = mc.getRenderManager().viewerPosZ;

        int color = colorSetting.getValue();
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        boolean showNames = namesSetting.getValue();
        boolean legit = legitSetting.getValue();

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        Tessellator tessellator = Tessellator.getInstance();
        List<net.minecraft.entity.player.EntityPlayer> players = mc.theWorld.playerEntities;
        for (int i = 0; i < players.size(); i++) {
            Entity entity = players.get(i);
            if (entity == mc.thePlayer) continue;

            // Use original entity width/height instead of modified bounding box
            double width = entity.width;
            double height = entity.height;
            double halfW = width * 0.5;

            if (RenderUtil.pointNearBox(camX, camY, camZ, entity.getEntityBoundingBox(), NEAR_CAMERA_MARGIN)) continue;

            if (legit && !isEntityVisible(entity, camX, camY, camZ)) continue;

            double ox = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - entity.posX;
            double oy = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - entity.posY;
            double oz = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - entity.posZ;

            double cx = entity.posX + ox;
            double cz = entity.posZ + oz;

            double minX = cx - halfW - camX;
            double minY = entity.posY + oy - camY;
            double minZ = entity.posZ + oz - halfW - camZ;
            double maxX = cx + halfW - camX;
            double maxY = entity.posY + oy + entity.height - camY;
            double maxZ = entity.posZ + oz + halfW - camZ;

            // Filled box (semi-transparent)
            int fillAlpha = 100;
            RenderUtil.drawFilledBox(tessellator, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, fillAlpha);

            // Name above box
            if (namesSetting.getValue()) {
                double midX = (cx - halfW + cx + halfW) * 0.5;
                double midZ = (entity.posZ + oz - halfW + entity.posZ + oz + halfW) * 0.5;
                double maxYScreen = entity.posY + oy + entity.height + 0.3 - camY;
                if (project(midX - camX, maxYScreen + 0.3, midZ - camZ, feetScratch)) {
                    storeName(entity.getName(), feetScratch[0], feetScratch[1]);
                }
            }
        }

        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
    }

    private void collect2D(float partialTicks) {
        double camX = mc.getRenderManager().viewerPosX;
        double camY = mc.getRenderManager().viewerPosY;
        double camZ = mc.getRenderManager().viewerPosZ;
        boolean showNames = namesSetting.getValue();
        boolean legit = legitSetting.getValue();

        List<net.minecraft.entity.player.EntityPlayer> players = mc.theWorld.playerEntities;
        for (int i = 0; i < players.size(); i++) {
            Entity entity = players.get(i);
            if (entity == mc.thePlayer) continue;
            if (legit && !isEntityVisible(entity, camX, camY, camZ)) continue;
            AxisAlignedBB bb = entity.getEntityBoundingBox();

            double ox = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - entity.posX;
            double oy = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - entity.posY;
            double oz = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - entity.posZ;

            double midX = (bb.minX + bb.maxX) * 0.5D + ox;
            double midZ = (bb.minZ + bb.maxZ) * 0.5D + oz;
            double minY = bb.minY + oy;
            double maxY = bb.maxY + oy;

            if (!project(midX - camX, minY - camY, midZ - camZ, feetScratch)) continue;
            if (!project(midX - camX, maxY - camY, midZ - camZ, headScratch)) continue;

            double topDownHead = vh - headScratch[1];
            double topDownFeet = vh - feetScratch[1];
            double h = Math.abs(topDownFeet - topDownHead);
            if (h < 2.0D) continue;
            double w = h * 0.5D;

            float x = (float) ((feetScratch[0] - w * 0.5D) / scale);
            float y = (float) (topDownHead / scale);
            float rw = (float) (w / scale);
            float rh = (float) (h / scale);

            addRect(x, y, rw, rh);
            if (showNames) storeName(entity.getName(), headScratch[0], headScratch[1]);
        }
    }

    private void addRect(float x, float y, float w, float h) {
        if (!ensureQuadRoom(8)) return;
        if (cornersSetting.getValue()) {
            float len = Math.max(4f, Math.min(h / 6f, 10f));
            addQuad(x, y, len, 1f);
            addQuad(x, y, 1f, len);
            addQuad(x + w - len, y, len, 1f);
            addQuad(x + w - 1f, y, 1f, len);
            addQuad(x, y + h - 1f, len, 1f);
            addQuad(x, y + h - len, 1f, len);
            addQuad(x + w - len, y + h - 1f, len, 1f);
            addQuad(x + w - 1f, y + h - len, 1f, len);
        } else {
            addQuad(x, y, w, 1f);
            addQuad(x, y + h - 1f, w, 1f);
            addQuad(x, y, 1f, h);
            addQuad(x + w - 1f, y, 1f, h);
        }
    }

    /**
     * quadBuf holds four floats per quad, so capacity has to be reasoned about in floats.
     * The old guard compared a quad count against the array length and let addQuad run off
     * the end once 128 quads were queued, which any server with 33 visible entities hits.
     * Bounded at MAX_QUADS so a pathological entity count drops quads instead of
     * allocating its way into an OutOfMemoryError mid-frame.
     */
    private boolean ensureQuadRoom(int quads) {
        if ((quadCount + quads) * 4 > quadBuf.length) {
            int needed = (quadCount + quads) * 4;
            if (needed > MAX_QUADS * 4) return false;
            quadBuf = Arrays.copyOf(quadBuf, Math.max(needed, quadBuf.length * 2));
        }
        return true;
    }

    private void addQuad(float x, float y, float w, float h) {
        int o = quadCount << 2;
        quadBuf[o] = x;
        quadBuf[o + 1] = y;
        quadBuf[o + 2] = w;
        quadBuf[o + 3] = h;
        quadCount++;
    }

    private boolean project(double x, double y, double z, double[] out) {
        double inX = mv[0] * x + mv[4] * y + mv[8] * z + mv[12];
        double inY = mv[1] * x + mv[5] * y + mv[9] * z + mv[13];
        double inZ = mv[2] * x + mv[6] * y + mv[10] * z + mv[14];
        double inW = mv[3] * x + mv[7] * y + mv[11] * z + mv[15];

        double clipX = pr[0] * inX + pr[4] * inY + pr[8] * inZ + pr[12] * inW;
        double clipY = pr[1] * inX + pr[5] * inY + pr[9] * inZ + pr[13] * inW;
        double clipZ = pr[2] * inX + pr[6] * inY + pr[10] * inZ + pr[14] * inW;
        double clipW = pr[3] * inX + pr[7] * inY + pr[11] * inZ + pr[15] * inW;

        if (clipW <= 0.0D) return false;
        double invW = 1.0D / clipW;
        double ndcX = clipX * invW;
        double ndcY = clipY * invW;
        double ndcZ = clipZ * invW;
        if (ndcX < -1.5D || ndcX > 1.5D) return false;
        if (ndcY < -1.5D || ndcY > 1.5D) return false;
        if (ndcZ < -1.0D || ndcZ > 1.0D) return false;

        out[0] = (ndcX + 1.0D) * 0.5D * vw;
        out[1] = (ndcY + 1.0D) * 0.5D * vh;
        return true;
    }

    private boolean isEntityVisible(Entity entity, double camX, double camY, double camZ) {
        if (mc.theWorld == null) return false;
        Vec3 start = new Vec3(camX, camY, camZ);
        AxisAlignedBB bb = entity.getEntityBoundingBox().expand(0.5, 0.5, 0.5);
        Vec3 end = new Vec3(bb.minX + (bb.maxX - bb.minX) * 0.5, bb.minY + (bb.maxY - bb.minY) * 0.5, bb.minZ + (bb.maxZ - bb.minZ) * 0.5);
        MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(start, end, false, true, false);
        return mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK;
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
        quadCount = 0;
        nameCount = 0;
    }

    @Override
    public String getTag() {
        return modeSetting.getValue();
    }
}