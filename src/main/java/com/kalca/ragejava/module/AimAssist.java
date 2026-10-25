package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.settings.ColorSetting;
import com.kalca.ragejava.settings.ModeSetting;
import com.kalca.ragejava.settings.Setting;
import com.kalca.ragejava.settings.SliderSetting;
import com.kalca.ragejava.util.RenderUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class AimAssist extends Module {

    public static final String TARGET_HEAD = "Head";
    public static final String TARGET_TORSO = "Torso";
    public static final String TARGET_LEGS = "Legs";

    public static final String MODE_NORMAL = "Normal";
    public static final String MODE_SILENT = "Silent";

    private final ModeSetting modeSetting = new ModeSetting("Mode", new String[]{MODE_NORMAL, MODE_SILENT}, 0);
    private final SliderSetting xSmoothSetting = new SliderSetting("X Smooth", 30, 0, 100, 1);
    private final SliderSetting ySmoothSetting = new SliderSetting("Y Smooth", 30, 0, 100, 1);
    private final SliderSetting sensitivitySetting = new SliderSetting("Sensitivity", 1.0, 0.1, 3.0, 0.05);
    private final SliderSetting fovSetting = new SliderSetting("FOV", 45, 1, 180, 1);
    private final SliderSetting maxDistanceSetting = new SliderSetting("Max Distance", 4.5, 0.5, 20.0, 0.5);
    private final BooleanSetting showFovCircleSetting = new BooleanSetting("Show FOV Circle", false);
    private final ColorSetting circleColorSetting = new ColorSetting("Circle Color", 0xFF1B395C);
    private final BooleanSetting holdAttackSetting = new BooleanSetting("Hold Attack", false);
    private final BooleanSetting wallCheckSetting = new BooleanSetting("Wall Check", true);
    private final ModeSetting targetSetting = new ModeSetting("Target", new String[]{TARGET_HEAD, TARGET_TORSO, TARGET_LEGS}, 0);

    private final Minecraft mc = Minecraft.getMinecraft();

    private float silentYaw = 0.0F;
    private float silentPitch = 0.0F;
    private boolean hasSilentTarget = false;
    private boolean isAttacking = false;
    private Entity attackTarget = null;

    private SilentHandler silentHandler;
    private Channel boundChannel;
    private static final String HANDLER_NAME = "ragejava_silent_aim";

    public AimAssist() {
        super("AimAssist", Category.COMBAT);
        settings.add(modeSetting);
        settings.add(xSmoothSetting);
        settings.add(ySmoothSetting);
        settings.add(sensitivitySetting);
        settings.add(fovSetting);
        settings.add(maxDistanceSetting);
        settings.add(showFovCircleSetting);
        settings.add(circleColorSetting);
        settings.add(holdAttackSetting);
        settings.add(wallCheckSetting);
        settings.add(targetSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == circleColorSetting) return showFovCircleSetting.getValue();
        return true;
    }

    private String mode() {
        return modeSetting.getValue();
    }

    private boolean silent() {
        return MODE_SILENT.equals(mode());
    }

    private boolean normal() {
        return MODE_NORMAL.equals(mode());
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) return;
        if (holdAttackSetting.getValue() && !mc.gameSettings.keyBindAttack.isKeyDown()) return;

        float curYaw = mc.thePlayer.rotationYaw;
        float curPitch = mc.thePlayer.rotationPitch;
        float fov = (float) fovSetting.getValue();

        double px = mc.thePlayer.posX;
        double py = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
        double pz = mc.thePlayer.posZ;

        float targetYaw = 0.0F;
        float targetPitch = 0.0F;
        double bestScore = Double.MAX_VALUE;
        boolean found = false;
        double maxDistance = maxDistanceSetting.getValue();
        double maxDistanceSq = maxDistance * maxDistance;

        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (entity == mc.thePlayer || entity.isDead || !(entity instanceof EntityLivingBase)) continue;

            double targetX, targetY, targetZ;
            String targetMode = targetSetting.getValue();
            if (TARGET_HEAD.equals(targetMode)) {
                targetX = entity.posX;
                targetY = entity.posY + entity.getEyeHeight();
                targetZ = entity.posZ;
            } else if (TARGET_TORSO.equals(targetMode)) {
                targetX = entity.posX;
                targetY = entity.posY + entity.getEyeHeight() * 0.5;
                targetZ = entity.posZ;
            } else {
                targetX = entity.posX;
                targetY = entity.posY;
                targetZ = entity.posZ;
            }

            double dx = targetX - px;
            double dy = targetY - py;
            double dz = targetZ - pz;
            double hor = Math.sqrt(dx * dx + dz * dz);
            if (hor < 0.01D && Math.abs(dy) < 0.01D) continue;

            double distSq = hor * hor + dy * dy;
            if (distSq > maxDistanceSq) continue;

            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, hor));

            float dYaw = Math.abs(wrapDegrees(yaw - curYaw));
            float dPitch = Math.abs(pitch - curPitch);
            if (dYaw > fov || dPitch > fov) continue;

            double score = dYaw + dPitch;
            if (score < bestScore) {
                if (wallCheckSetting.getValue() && !hasLineOfSight(entity)) continue;
                bestScore = score;
                targetYaw = yaw;
                targetPitch = pitch;
                found = true;
            }
        }

        if (!found) return;

        double sensitivity = sensitivitySetting.getValue();
        float yawFactor = (float) (xSmoothSetting.getValue() / 100.0 * sensitivity);
        float pitchFactor = (float) (ySmoothSetting.getValue() / 100.0 * sensitivity);

        float newYaw = curYaw + wrapDegrees(targetYaw - curYaw) * yawFactor;
        float newPitch = MathHelper.clamp_float(curPitch + (targetPitch - curPitch) * pitchFactor, -90.0F, 90.0F);

        if (silent()) {
            silentYaw = newYaw;
            silentPitch = newPitch;
            hasSilentTarget = true;
            armSilentChannel();
        } else {
            mc.thePlayer.rotationYaw = newYaw;
            mc.thePlayer.rotationPitch = newPitch;
            hasSilentTarget = false;
        }
    }

    private void armSilentChannel() {
        if (mc.getNetHandler() == null) return;
        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return;
        Channel channel = manager.channel();
        if (channel == null || !channel.isActive()) return;

        if (silentHandler == null) {
            silentHandler = new SilentHandler();
            boundChannel = channel;
            if (channel.pipeline().get(HANDLER_NAME) == null) {
                channel.eventLoop().execute(() -> {
                    try {
                        channel.pipeline().addBefore("packet_handler", HANDLER_NAME, silentHandler);
                    } catch (Exception ignored) {}
                });
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
                        channel.pipeline().addBefore("packet_handler", HANDLER_NAME, silentHandler);
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
        if (silentHandler != null) {
            Channel channel = boundChannel;
            if (channel != null && channel.isActive()) {
                channel.eventLoop().execute(() -> {
                    try {
                        if (channel.pipeline().get(HANDLER_NAME) != null) channel.pipeline().remove(HANDLER_NAME);
                    } catch (Exception ignored) {}
                });
            }
            silentHandler = null;
            boundChannel = null;
        }
    }

    private boolean hasLineOfSight(Entity target) {
        Vec3 eyes = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ);
        String targetMode = targetSetting.getValue();
        double targetY;
        if (TARGET_HEAD.equals(targetMode)) {
            targetY = target.posY + target.getEyeHeight();
        } else if (TARGET_TORSO.equals(targetMode)) {
            targetY = target.posY + target.getEyeHeight() * 0.5;
        } else {
            targetY = target.posY;
        }
        Vec3 targetEyes = new Vec3(target.posX, targetY, target.posZ);
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(eyes, targetEyes, false, true, false);
        return hit == null;
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (!isEnabled()) return;
        if (!showFovCircleSetting.getValue()) return;
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) return;

        ScaledResolution sr = new ScaledResolution(mc);
        float cx = sr.getScaledWidth() / 2.0F;
        float cy = sr.getScaledHeight() / 2.0F;

        double denom = Math.tan(Math.toRadians(mc.gameSettings.fovSetting) * 0.5D);
        double halfAngle = Math.toRadians(Math.min(fovSetting.getValue(), 89.0F));
        float radius = denom > 1.0e-4D ? (float) (cy * Math.tan(halfAngle) / denom) : cy;
        radius = Math.min(radius, cy);

        RenderUtil.drawCircleOutline(cx, cy, radius, 1.5F, circleColorSetting.getValue(), 120);
    }

    private float wrapDegrees(float degrees) {
        float d = degrees % 360.0F;
        if (d >= 180.0F) d -= 360.0F;
        if (d < -180.0F) d += 360.0F;
        return d;
    }

    private class SilentHandler extends ChannelDuplexHandler {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (isEnabled() && silent() && msg instanceof C02PacketUseEntity) {
                C02PacketUseEntity packet = (C02PacketUseEntity) msg;
                if (packet.getAction() == C02PacketUseEntity.Action.ATTACK && hasSilentTarget) {
                    isAttacking = true;
                    attackTarget = packet.getEntityFromWorld(mc.theWorld);
                    if (attackTarget != null) {
                        // Inject silent rotation into the attack packet
                        try {
                            injectSilentRotation(packet);
                        } catch (Exception ignored) {}
                    }
                }
            }
            super.write(ctx, msg, promise);
        }

        private void injectSilentRotation(C02PacketUseEntity packet) throws Exception {
            // Use reflection to inject the silent rotation into the attack packet
            // This makes the server see the rotation at the exact moment of attack
            Class<?> clazz = C02PacketUseEntity.class;
            
            // Set the rotation fields if they exist
            try {
                java.lang.reflect.Field yawField = clazz.getDeclaredField("rotationYaw");
                yawField.setAccessible(true);
                yawField.setFloat(packet, silentYaw);
            } catch (Exception e) {
                // Try alternative field names
                try {
                    java.lang.reflect.Field yawField = clazz.getDeclaredField("yaw");
                    yawField.setAccessible(true);
                    yawField.setFloat(packet, silentYaw);
                } catch (Exception ignored) {}
            }
            
            try {
                java.lang.reflect.Field pitchField = clazz.getDeclaredField("rotationPitch");
                pitchField.setAccessible(true);
                pitchField.setFloat(packet, silentPitch);
            } catch (Exception e) {
                try {
                    java.lang.reflect.Field pitchField = clazz.getDeclaredField("pitch");
                    pitchField.setAccessible(true);
                    pitchField.setFloat(packet, silentPitch);
                } catch (Exception ignored) {}
            }
            
            // Also try to set on C03PacketPlayer if needed
            // The attack packet may not have rotation fields in 1.8.9
            // In that case, we send a C03PacketPlayer look packet right before the attack
        }
    }

    @Override
    public String getTag() {
        return modeSetting.getValue();
    }
}