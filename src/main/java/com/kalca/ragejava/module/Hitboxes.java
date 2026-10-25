package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.settings.ModeSetting;
import com.kalca.ragejava.settings.Setting;
import com.kalca.ragejava.settings.SliderSetting;
import com.kalca.ragejava.util.RenderUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.lang.reflect.Field;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Hitboxes extends Module {

    public static final String MODE_STANDARD = "Standard";
    public static final String MODE_DISADVANTAGE = "Disadvantage";

    private final ModeSetting modeSetting = new ModeSetting("Mode", new String[]{MODE_STANDARD, MODE_DISADVANTAGE}, 0);
    private final SliderSetting multiplierSetting = new SliderSetting("Multiplier", 1.5, 1.0, 5.0, 0.05);
    private final BooleanSetting espSetting = new BooleanSetting("Show Hitboxes", false);

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Map<UUID, double[]> originalDims = new HashMap<>();

    private DisadvantageHandler disadvantageHandler;
    private Channel boundChannel;
    private static final String HANDLER_NAME = "ragejava_disadvantage";

    public Hitboxes() {
        super("Hitboxes", Category.COMBAT);
        settings.add(modeSetting);
        settings.add(multiplierSetting);
        settings.add(espSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        return true;
    }

    private String mode() {
        return modeSetting.getValue();
    }

    private boolean standard() {
        return MODE_STANDARD.equals(mode());
    }

    private boolean disadvantage() {
        return MODE_DISADVANTAGE.equals(mode());
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (mc.theWorld == null || mc.thePlayer == null) return;

        double mult = multiplierSetting.getValue();

        if (standard()) {
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
        } else if (disadvantage()) {
            armDisadvantageChannel();
        }
    }

    private void armDisadvantageChannel() {
        if (mc.getNetHandler() == null) return;
        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return;
        Channel channel = manager.channel();
        if (channel == null || !channel.isActive()) return;

        if (disadvantageHandler == null) {
            disadvantageHandler = new DisadvantageHandler();
            boundChannel = channel;
            if (channel.pipeline().get(HANDLER_NAME) == null) {
                channel.eventLoop().execute(() -> {
                    try {
                        channel.pipeline().addBefore("packet_handler", HANDLER_NAME, disadvantageHandler);
                    } catch (Exception ignored) {}
                });
            }
        } else if (boundChannel != channel) {
            final Channel old = boundChannel;
            boundChannel = channel;
            channel.eventLoop().execute(() -> {
                try {
                    if (old != null && old.isActive()) old.pipeline().remove(HANDLER_NAME);
                } catch (Exception ignored) {}
            });
            try {
                if (channel.pipeline().get(HANDLER_NAME) == null) {
                    channel.pipeline().addBefore("packet_handler", HANDLER_NAME, disadvantageHandler);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onEnable() {
        originalDims.clear();
        if (disadvantage()) armDisadvantageChannel();
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

        if (disadvantageHandler != null) {
            Channel channel = boundChannel;
            if (channel != null && channel.isActive()) {
                channel.eventLoop().execute(() -> {
                    try {
                        if (channel.pipeline().get(HANDLER_NAME) != null) channel.pipeline().remove(HANDLER_NAME);
                    } catch (Exception ignored) {}
                });
            }
            disadvantageHandler = null;
            boundChannel = null;
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!isEnabled()) return;
        if (!espSetting.getValue()) return;
        if (mc.theWorld == null || mc.thePlayer == null) return;

        double camX = mc.getRenderManager().viewerPosX;
        double camY = mc.getRenderManager().viewerPosY;
        double camZ = mc.getRenderManager().viewerPosZ;

        int r = 255, g = 255, b = 255, a = 180;

        GlStateManager.disableTexture2D();
        Tessellator tessellator = Tessellator.getInstance();

        if (standard()) {
            for (Entity entity : mc.theWorld.loadedEntityList) {
                if (entity == mc.thePlayer) continue;
                if (!(entity instanceof EntityPlayer)) continue;
                if (entity.isDead) continue;

                UUID uuid = entity.getUniqueID();
                double[] dims = originalDims.get(uuid);
                if (dims == null) continue;

                double mult = multiplierSetting.getValue();
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

                AxisAlignedBB box = AxisAlignedBB.fromBounds(
                        expanded.minX - camX, expanded.minY - camY, expanded.minZ - camZ,
                        expanded.maxX - camX, expanded.maxY - camY, expanded.maxZ - camZ);

                RenderUtil.drawOutlinedBox(tessellator, box, 255, 255, 255, a);
            }
        } else if (disadvantage()) {
            EntityPlayer player = mc.thePlayer;
            UUID uuid = player.getUniqueID();
            double[] dims = originalDims.get(uuid);
            if (dims != null) {
                double mult = multiplierSetting.getValue();
                double origWidth = dims[0];
                double origHeight = dims[1];

                double halfX = (origWidth * 0.5) * mult;
                double halfY = (origHeight * 0.5) * mult;
                double halfZ = (origWidth * 0.5) * mult;

                double cx = player.posX;
                double cy = player.posY;
                double cz = player.posZ;

                AxisAlignedBB expanded = new AxisAlignedBB(
                        cx - halfX, cy - halfY, cz - halfZ,
                        cx + halfX, cy + halfY, cz + halfZ);

                AxisAlignedBB box = AxisAlignedBB.fromBounds(
                        expanded.minX - camX, expanded.minY - camY, expanded.minZ - camZ,
                        expanded.maxX - camX, expanded.maxY - camY, expanded.maxZ - camZ);

                RenderUtil.drawOutlinedBox(tessellator, box, 255, 255, 255, a);
            }
        }

        GlStateManager.enableTexture2D();
    }

private class DisadvantageHandler extends ChannelDuplexHandler {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (isEnabled() && disadvantage() && msg instanceof C03PacketPlayer) {
                C03PacketPlayer packet = (C03PacketPlayer) msg;
                try {
                    Field onGroundField = C03PacketPlayer.class.getDeclaredField("onGround");
                    onGroundField.setAccessible(true);
                    boolean onGround = onGroundField.getBoolean(packet);

                    Field posXField = C03PacketPlayer.class.getDeclaredField("posX");
                    posXField.setAccessible(true);
                    double posX = posXField.getDouble(packet);

                    Field posYField = C03PacketPlayer.class.getDeclaredField("posY");
                    posYField.setAccessible(true);
                    double posY = posYField.getDouble(packet);

                    Field posZField = C03PacketPlayer.class.getDeclaredField("posZ");
                    posZField.setAccessible(true);
                    double posZ = posZField.getDouble(packet);

                    double mult = multiplierSetting.getValue();
                    double origWidth = mc.thePlayer.width;
                    double origHeight = mc.thePlayer.height;
                    double halfX = (origWidth * 0.5) * mult;
                    double halfY = (origHeight * 0.5) * mult;
                    double halfZ = (origWidth * 0.5) * mult;

                    double newX = posX + halfX;
                    double newY = posY + halfY;
                    double newZ = posZ + halfZ;

                    msg = new C03PacketPlayer.C04PacketPlayerPosition(
                            newX, newY, newZ, onGround
                    );
                } catch (Exception e) {
                    // Ignore reflection errors
                }
            }
            super.write(ctx, msg, promise);
        }
    }

    @Override
    public String getTag() {
        double value = multiplierSetting.getValue();
        return (Math.round(value * 100.0) / 100.0) + "x";
    }
}