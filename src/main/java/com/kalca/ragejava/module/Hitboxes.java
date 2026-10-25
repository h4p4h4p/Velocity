package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.settings.SliderSetting;
import com.kalca.ragejava.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Hitboxes extends Module {

    private final SliderSetting multiplierSetting = new SliderSetting("Multiplier", 1.5, 1.0, 5.0, 0.05);
    private final BooleanSetting espSetting = new BooleanSetting("Show Hitboxes", false);

    private static final double NEAR_CAMERA_MARGIN = 1.2D;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Map<UUID, double[]> originalDims = new HashMap<>();

    public Hitboxes() {
        super("Hitboxes", Category.COMBAT);
        settings.add(multiplierSetting);
        settings.add(espSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (mc.theWorld == null || mc.thePlayer == null) return;

        double mult = multiplierSetting.getValue();

        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (entity == mc.thePlayer) continue;
            if (!(entity instanceof EntityPlayer)) continue;
            if (entity.isDead) continue;

            UUID uuid = entity.getUniqueID();
            double[] dims = originalDims.get(uuid);

            if (dims == null) {
                dims = new double[]{entity.width, entity.height};
                originalDims.put(uuid, dims);
            }

            double origWidth = dims[0];
            double origHeight = dims[1];

            double halfX = (origWidth * 0.5) * mult;
            double halfY = (origHeight * 0.5) * mult;
            double halfZ = (origWidth * 0.5) * mult;

            double cx = entity.posX;
            double cy = entity.posY;
            double cz = entity.posZ;

            AxisAlignedBB expanded = new AxisAlignedBB(
                    cx - halfX, cy - halfY, cz - halfZ,
                    cx + halfX, cy + halfY, cz + halfZ);

            entity.setEntityBoundingBox(expanded);
        }
    }

    @Override
    public void onEnable() {
        originalDims.clear();
    }

    @Override
    public void onDisable() {
        if (mc.theWorld == null) return;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (entity instanceof EntityPlayer && entity != mc.thePlayer) {
                UUID uuid = entity.getUniqueID();
                double[] dims = originalDims.get(uuid);
                if (dims != null) {
                    double w = dims[0] * 0.5;
                    double h = dims[1];
                    double yOffset = 0;
                    if (entity instanceof EntityLivingBase) {
                        yOffset = ((EntityLivingBase) entity).getEyeHeight() - h;
                    }
                    entity.setEntityBoundingBox(new AxisAlignedBB(
                            entity.posX - w, entity.posY + yOffset, entity.posZ - w,
                            entity.posX + w, entity.posY + yOffset + h, entity.posZ + w));
                } else {
                    double w = entity.width * 0.5;
                    double h = entity.height;
                    double yOffset = 0;
                    if (entity instanceof EntityLivingBase) {
                        yOffset = ((EntityLivingBase) entity).getEyeHeight() - h;
                    }
                    entity.setEntityBoundingBox(new AxisAlignedBB(
                            entity.posX - w, entity.posY + yOffset, entity.posZ - w,
                            entity.posX + w, entity.posY + yOffset + h, entity.posZ + w));
                }
            }
        }
        if (mc.thePlayer != null) {
            UUID uuid = mc.thePlayer.getUniqueID();
            double[] dims = originalDims.get(uuid);
            if (dims != null) {
                double w = dims[0] * 0.5;
                double h = dims[1];
                double yOffset = 0;
                if (mc.thePlayer instanceof EntityLivingBase) {
                    yOffset = ((EntityLivingBase) mc.thePlayer).getEyeHeight() - h;
                }
                mc.thePlayer.setEntityBoundingBox(new AxisAlignedBB(
                        mc.thePlayer.posX - w, mc.thePlayer.posY + yOffset, mc.thePlayer.posZ - w,
                        mc.thePlayer.posX + w, mc.thePlayer.posY + yOffset + h, mc.thePlayer.posZ + w));
            }
        }
        originalDims.clear();
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!isEnabled()) return;
        if (!espSetting.getValue()) return;
        if (mc.theWorld == null || mc.thePlayer == null) return;

        double camX = mc.getRenderManager().viewerPosX;
        double camY = mc.getRenderManager().viewerPosY;
        double camZ = mc.getRenderManager().viewerPosZ;
        float partialTicks = event.partialTicks;

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        Tessellator tessellator = Tessellator.getInstance();

        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (entity == mc.thePlayer) continue;
            if (!(entity instanceof EntityPlayer)) continue;
            if (entity.isDead) continue;

            UUID uuid = entity.getUniqueID();
            double[] dims = originalDims.get(uuid);
            if (dims == null) continue;

            double mult = multiplierSetting.getValue();
            double halfX = (dims[0] * 0.5) * mult;
            double halfY = (dims[1] * 0.5) * mult;
            double halfZ = (dims[0] * 0.5) * mult;

            double ox = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - entity.posX;
            double oy = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - entity.posY;
            double oz = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - entity.posZ;

            double cx = entity.posX + ox;
            double cy = entity.posY + oy;
            double cz = entity.posZ + oz;

            AxisAlignedBB expanded = new AxisAlignedBB(
                    cx - halfX, cy - halfY, cz - halfZ,
                    cx + halfX, cy + halfY, cz + halfZ);
            if (RenderUtil.pointNearBox(camX, camY, camZ, expanded, NEAR_CAMERA_MARGIN)) continue;

            RenderUtil.drawFilledBox(tessellator,
                    expanded.minX - camX, expanded.minY - camY, expanded.minZ - camZ,
                    expanded.maxX - camX, expanded.maxY - camY, expanded.maxZ - camZ,
                    255, 255, 255, 100);
        }

        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
    }

    @Override
    public String getTag() {
        double value = multiplierSetting.getValue();
        return (Math.round(value * 100.0) / 100.0) + "x";
    }
}
