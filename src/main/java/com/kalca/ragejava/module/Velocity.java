package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.ModeSetting;
import com.kalca.ragejava.settings.Setting;
import com.kalca.ragejava.settings.SliderSetting;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class Velocity extends Module {

    public static final String MODE_MODIFY = "Modify";
    public static final String MODE_LAG = "Lag";
    public static final String MODE_REVERSAL = "Reversal";

    private static final String HANDLER_NAME = "ragejava_velocity";
    private static final int REVERSAL_MAX_WAIT_TICKS = 4;

    private final ModeSetting modeSetting = new ModeSetting("Mode", new String[]{MODE_MODIFY, MODE_LAG, MODE_REVERSAL}, 0);
    private final SliderSetting horizontalSetting = new SliderSetting("Horizontal", 0, -1.0, 1.0, 0.1);
    private final SliderSetting verticalSetting = new SliderSetting("Vertical", 0, -1.0, 1.0, 0.1);
    private final SliderSetting lagChanceSetting = new SliderSetting("Lag Chance", 0, 0, 100, 5);

    private final Minecraft mc = Minecraft.getMinecraft();
    private VelocityHandler handler;
    private Channel boundChannel;
    private int playerId = -1;

    private volatile int clientTick;
    private volatile boolean reversalPending;
    private volatile int reversalApplyTick;
    private volatile double reversalX;
    private volatile double reversalY;
    private volatile double reversalZ;

    public Velocity() {
        super("Velocity", Category.COMBAT);
        settings.add(modeSetting);
        settings.add(horizontalSetting);
        settings.add(verticalSetting);
        settings.add(lagChanceSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == horizontalSetting) return modify();
        if (setting == verticalSetting) return modify();
        if (setting == lagChanceSetting) return lag();
        return super.isSettingVisible(setting);
    }

    private String mode() {
        return modeSetting.getValue();
    }

    private boolean modify() {
        return MODE_MODIFY.equals(mode());
    }

    private boolean lag() {
        return MODE_LAG.equals(mode());
    }

    private boolean reversal() {
        return MODE_REVERSAL.equals(mode());
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            ++clientTick;
            if (!isEnabled()) return;
            if (!reversal()) clearReversal();
            if (mc.thePlayer != null) playerId = mc.thePlayer.getEntityId();
            armChannel();
            return;
        }
        if (isEnabled() && reversal()) handleReversal();
    }

    /**
     * Records where the player was when the knockback arrived, then lets the packet through.
     * The next tick is spent measuring where the knockback actually moved them.
     */
    private void captureReversal() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return;
        reversalX = player.posX;
        reversalY = player.posY;
        reversalZ = player.posZ;
        reversalApplyTick = clientTick + 1;
        reversalPending = true;
    }

    /**
     * Sends the player back with the velocity that covers the distance to the pre-knockback
     * position. Running at the end of the tick means the correction moves them on the next one.
     */
    private void handleReversal() {
        if (!reversalPending || clientTick < reversalApplyTick) return;

        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            clearReversal();
            return;
        }

        double dx = reversalX - player.posX;
        double dy = reversalY - player.posY;
        double dz = reversalZ - player.posZ;

        if (dx * dx + dy * dy + dz * dz < 1.0E-4D) {
            if (clientTick - reversalApplyTick >= REVERSAL_MAX_WAIT_TICKS) clearReversal();
            return;
        }

        player.motionX = dx;
        player.motionY = dy;
        player.motionZ = dz;
        clearReversal();
    }

    private void clearReversal() {
        reversalPending = false;
    }

    private void armChannel() {
        if (mc.getNetHandler() == null) return;
        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return;
        Channel channel = manager.channel();
        if (channel == null || !channel.isActive()) return;

        if (handler == null) {
            handler = new VelocityHandler();
            boundChannel = channel;
            if (channel.pipeline().get(HANDLER_NAME) == null) {
                channel.eventLoop().execute(() -> {
                    try {
                        channel.pipeline().addBefore("packet_handler", HANDLER_NAME, handler);
                    } catch (Exception ignored) {
                    }
                });
            }
        } else if (boundChannel != channel) {
            final Channel old = boundChannel;
            boundChannel = channel;
            channel.eventLoop().execute(() -> {
                try {
                    if (old != null && old.isActive()) old.pipeline().remove(HANDLER_NAME);
                } catch (Exception ignored) {
                }
            });
            try {
                if (channel.pipeline().get(HANDLER_NAME) == null) {
                    channel.pipeline().addBefore("packet_handler", HANDLER_NAME, handler);
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onEnable() {
        clearReversal();
        if (mc.thePlayer != null) playerId = mc.thePlayer.getEntityId();
        armChannel();
    }

    @Override
    public void onDisable() {
        clearReversal();
        VelocityHandler h = handler;
        handler = null;
        Channel channel = boundChannel;
        boundChannel = null;
        if (channel != null && channel.isActive()) {
            channel.eventLoop().execute(() -> {
                try {
                    if (channel.pipeline().get(HANDLER_NAME) != null) channel.pipeline().remove(HANDLER_NAME);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private class VelocityHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (isEnabled() && msg instanceof Packet) {
                int id = playerId;
                if (msg instanceof S12PacketEntityVelocity) {
                    S12PacketEntityVelocity p = (S12PacketEntityVelocity) msg;
                    if (id >= 0 && p.getEntityID() == id) {
                        if (lag() && Math.random() * 100.0D < lagChanceSetting.getValue()) {
                            return;
                        }
                        if (modify()) {
                            double h = horizontalSetting.getValue();
                            double v = verticalSetting.getValue();
                            msg = new S12PacketEntityVelocity(id,
                                    p.getMotionX() / 8000.0 * h,
                                    p.getMotionY() / 8000.0 * v,
                                    p.getMotionZ() / 8000.0 * h);
                        }
                        if (reversal()) captureReversal();
                    }
                } else if (msg instanceof S27PacketExplosion) {
                    S27PacketExplosion p = (S27PacketExplosion) msg;
                    if (lag() && Math.random() * 100.0D < lagChanceSetting.getValue()) {
                        return;
                    }
                    if (modify()) {
                        double h = horizontalSetting.getValue();
                        double v = verticalSetting.getValue();
                        msg = new S27PacketExplosion(p.getX(), p.getY(), p.getZ(), p.getStrength(),
                                p.getAffectedBlockPositions(),
                                new Vec3(p.func_149149_c() * h, p.func_149144_d() * v, p.func_149147_e() * h));
                    }
                    if (reversal()) captureReversal();
                }
            }
            ctx.fireChannelRead(msg);
        }
    }
}