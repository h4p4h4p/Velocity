package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.settings.ColorSetting;
import com.kalca.ragejava.settings.ModeSetting;
import com.kalca.ragejava.settings.Setting;
import com.kalca.ragejava.util.RenderUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StorageESP extends Module {

    public static final String MODE_2D = "2D";
    public static final String MODE_3D = "3D";

    private static final double NEAR_CAMERA_MARGIN = 0.2D;
    private static final int MAX_QUADS = 16384;
    private static final int FILL_ALPHA = 100;

    private final ModeSetting modeSetting = new ModeSetting("Mode", new String[]{MODE_2D, MODE_3D}, 1);
    private final BooleanSetting cornersSetting = new BooleanSetting("Corners", false);
    private final BooleanSetting labelsSetting = new BooleanSetting("Labels", true);
    private final BooleanSetting throughWallsSetting = new BooleanSetting("Through Walls", true);
    private final ColorSetting colorSetting = new ColorSetting("Color", 0xFFFF5555);

    private final Minecraft mc = Minecraft.getMinecraft();

    private final float[] mv = new float[16];
    private final float[] pr = new float[16];
    private final double[] cornerScratch = new double[2];
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

    private String[] labelTexts = new String[32];
    private float[] labelXs = new float[32];
    private float[] labelYs = new float[32];
    private int labelCount;

    private final Map<BlockPos, TileEntity> storages = new HashMap<BlockPos, TileEntity>();
    private final Set<BlockPos> visited = new HashSet<BlockPos>();
    private final Deque<BlockPos> queue = new ArrayDeque<BlockPos>();
    private final List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>();
    private final List<String> boxLabels = new ArrayList<String>();

    public StorageESP() {
        super("StorageESP", Category.RENDER);
        settings.add(modeSetting);
        settings.add(cornersSetting);
        settings.add(labelsSetting);
        settings.add(throughWallsSetting);
        settings.add(colorSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == cornersSetting) return MODE_2D.equals(mode());
        if (setting == throughWallsSetting) return MODE_3D.equals(mode());
        return true;
    }

    private String mode() {
        return modeSetting.getValue();
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        quadCount = 0;
        labelCount = 0;
        if (modeSetting.isUnset()) return;
        if (!captureMatrices()) return;

        collectBoxes();
        if (boxes.isEmpty()) return;

        if (MODE_3D.equals(mode())) {
            render3D();
        } else {
            collect2D();
        }
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (!isEnabled()) return;
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        int color = colorSetting.getValue();
        if (MODE_2D.equals(mode()) && quadCount > 0) {
            RenderUtil.drawBatchedRects(quadBuf, quadCount, color);
        }
        if (labelCount > 0) {
            for (int i = 0; i < labelCount; i++) {
                String text = labelTexts[i];
                RenderUtil.drawString(text, labelXs[i] - RenderUtil.getTextWidth(text) * 0.5f, labelYs[i] - 9f, color);
            }
        }
    }

    /**
     * Groups loaded storage tile entities into visual boxes. Normal chests that touch are merged
     * into one double-chest box, so a double chest renders as a single 2x1 outline instead of two
     * overlapping 1x1 boxes. Ender chests never merge.
     */
    private void collectBoxes() {
        storages.clear();
        visited.clear();
        boxes.clear();
        boxLabels.clear();

        for (TileEntity entity : mc.theWorld.loadedTileEntityList) {
            if (entity instanceof TileEntityChest || entity instanceof TileEntityEnderChest) {
                storages.put(entity.getPos(), entity);
            }
        }

        for (TileEntity entity : storages.values()) {
            BlockPos pos = entity.getPos();
            if (visited.contains(pos)) continue;

            if (entity instanceof TileEntityEnderChest) {
                visited.add(pos);
                boxes.add(blockBox(pos));
                boxLabels.add("Ender Chest");
                continue;
            }

            Block block = mc.theWorld.getBlockState(pos).getBlock();
            if (!(block instanceof BlockChest)) continue;
            AxisAlignedBB box = blockBox(pos);
            int count = 1;

            visited.add(pos);
            queue.clear();
            queue.add(pos);
            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                BlockPos[] neighbours = {
                        current.west(), current.east(), current.north(), current.south()
                };
                for (BlockPos neighbour : neighbours) {
                    if (visited.contains(neighbour)) continue;
                    TileEntity adjacent = storages.get(neighbour);
                    if (!(adjacent instanceof TileEntityChest)) continue;
                    if (mc.theWorld.getBlockState(neighbour).getBlock() != block) continue;
                    visited.add(neighbour);
                    box = box.union(blockBox(neighbour));
                    count++;
                    queue.add(neighbour);
                }
            }

            boxes.add(box);
            boxLabels.add(count > 1 ? "Double Chest" : "Chest");
        }
    }

    private static AxisAlignedBB blockBox(BlockPos pos) {
        return new AxisAlignedBB(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    private void render3D() {
        double camX = mc.getRenderManager().viewerPosX;
        double camY = mc.getRenderManager().viewerPosY;
        double camZ = mc.getRenderManager().viewerPosZ;

        int color = colorSetting.getValue();
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        boolean labels = labelsSetting.getValue();

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        if (throughWallsSetting.getValue()) GlStateManager.disableDepth();

        Tessellator tessellator = Tessellator.getInstance();
        for (int i = 0; i < boxes.size(); i++) {
            AxisAlignedBB box = boxes.get(i);
            if (RenderUtil.pointNearBox(camX, camY, camZ, box, NEAR_CAMERA_MARGIN)) continue;

            RenderUtil.drawFilledBox(tessellator,
                    box.minX - camX, box.minY - camY, box.minZ - camZ,
                    box.maxX - camX, box.maxY - camY, box.maxZ - camZ,
                    r, g, b, FILL_ALPHA);

            if (!labels) continue;
            double midX = (box.minX + box.maxX) * 0.5D - camX;
            double midZ = (box.minZ + box.maxZ) * 0.5D - camZ;
            double topY = box.maxY + 0.3D - camY;
            if (project(midX, topY, midZ, cornerScratch)) {
                storeLabel(boxLabels.get(i), cornerScratch[0], cornerScratch[1]);
            }
        }

        if (throughWallsSetting.getValue()) GlStateManager.enableDepth();
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
    }

    private void collect2D() {
        double camX = mc.getRenderManager().viewerPosX;
        double camY = mc.getRenderManager().viewerPosY;
        double camZ = mc.getRenderManager().viewerPosZ;
        boolean labels = labelsSetting.getValue();

        for (int i = 0; i < boxes.size(); i++) {
            AxisAlignedBB box = boxes.get(i);
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            int hits = 0;

            for (int corner = 0; corner < 8; corner++) {
                double x = (corner & 1) == 0 ? box.minX : box.maxX;
                double y = (corner & 2) == 0 ? box.minY : box.maxY;
                double z = (corner & 4) == 0 ? box.minZ : box.maxZ;
                if (!projectCorner(x - camX, y - camY, z - camZ, cornerScratch)) continue;
                hits++;
                if (cornerScratch[0] < minX) minX = cornerScratch[0];
                if (cornerScratch[0] > maxX) maxX = cornerScratch[0];
                if (cornerScratch[1] < minY) minY = cornerScratch[1];
                if (cornerScratch[1] > maxY) maxY = cornerScratch[1];
            }

            if (hits < 2) continue;
            double topDownTop = vh - maxY;
            double topDownBottom = vh - minY;
            float x = (float) (minX / scale);
            float y = (float) (topDownTop / scale);
            float w = (float) ((maxX - minX) / scale);
            float h = (float) ((topDownBottom - topDownTop) / scale);
            if (w < 1.5f || h < 1.5f) continue;

            addRect(x, y, w, h);

            if (!labels) continue;
            double midX = (box.minX + box.maxX) * 0.5D - camX;
            double midZ = (box.minZ + box.maxZ) * 0.5D - camZ;
            double topY = box.maxY + 0.3D - camY;
            if (project(midX, topY, midZ, cornerScratch)) {
                storeLabel(boxLabels.get(i), cornerScratch[0], cornerScratch[1]);
            }
        }
    }

    private void storeLabel(String text, double screenX, double screenY) {
        if (labelCount == labelTexts.length) {
            labelTexts = Arrays.copyOf(labelTexts, labelCount * 2);
            labelXs = Arrays.copyOf(labelXs, labelCount * 2);
            labelYs = Arrays.copyOf(labelYs, labelCount * 2);
        }
        labelTexts[labelCount] = "[" + text + "]";
        labelXs[labelCount] = (float) (screenX / scale);
        labelYs[labelCount] = (float) ((vh - screenY) / scale);
        labelCount++;
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

    /** Projection for labels: rejects points behind or well outside the screen. */
    private boolean project(double x, double y, double z, double[] out) {
        if (!projectCorner(x, y, z, out)) return false;
        double ndcX = (out[0] / vw) * 2.0D - 1.0D;
        double ndcY = (out[1] / vh) * 2.0D - 1.0D;
        return ndcX >= -1.1D && ndcX <= 1.1D && ndcY >= -1.1D && ndcY <= 1.1D;
    }

    /** Projection for box corners: only needs to be in front of the camera. */
    private boolean projectCorner(double x, double y, double z, double[] out) {
        double inX = mv[0] * x + mv[4] * y + mv[8] * z + mv[12];
        double inY = mv[1] * x + mv[5] * y + mv[9] * z + mv[13];
        double inZ = mv[2] * x + mv[6] * y + mv[10] * z + mv[14];
        double inW = mv[3] * x + mv[7] * y + mv[11] * z + mv[15];

        double clipX = pr[0] * inX + pr[4] * inY + pr[8] * inZ + pr[12] * inW;
        double clipY = pr[1] * inX + pr[5] * inY + pr[9] * inZ + pr[13] * inW;
        double clipW = pr[3] * inX + pr[7] * inY + pr[11] * inZ + pr[15] * inW;

        if (clipW <= 0.0D) return false;
        double invW = 1.0D / clipW;
        double ndcX = clipX * invW;
        double ndcY = clipY * invW;
        if (ndcX < -20.0D || ndcX > 20.0D) return false;
        if (ndcY < -20.0D || ndcY > 20.0D) return false;

        out[0] = (ndcX + 1.0D) * 0.5D * vw;
        out[1] = (ndcY + 1.0D) * 0.5D * vh;
        return true;
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

    private boolean ensureQuadRoom(int quads) {
        if ((quadCount + quads) * 4 > quadBuf.length) {
            int needed = (quadCount + quads) * 4;
            if (needed > MAX_QUADS * 4) return false;
            quadBuf = Arrays.copyOf(quadBuf, Math.max(needed, quadBuf.length * 2));
        }
        return true;
    }

    private void addQuad(float x, float y, float w, float h) {
        int offset = quadCount << 2;
        quadBuf[offset] = x;
        quadBuf[offset + 1] = y;
        quadBuf[offset + 2] = w;
        quadBuf[offset + 3] = h;
        quadCount++;
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
        quadCount = 0;
        labelCount = 0;
        boxes.clear();
        boxLabels.clear();
    }

    @Override
    public String getTag() {
        return modeSetting.getDisplayValue();
    }
}
