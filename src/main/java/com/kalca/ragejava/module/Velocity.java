package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.ModeSetting;
import com.kalca.ragejava.settings.Setting;
import com.kalca.ragejava.settings.SliderSetting;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
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

    private static final String HANDLER_NAME = "ragejava_velocity";

    private final ModeSetting modeSetting = new ModeSetting("Mode", new String[]{MODE_MODIFY, MODE_LAG}, 0);
    private final SliderSetting horizontalSetting = new SliderSetting("Horizontal", 0, -1.0, 1.0, 0.1);
    private final SliderSetting verticalSetting = new SliderSetting("Vertical", 0, -1.0, 1.0, 0.1);
    private final SliderSetting lagChanceSetting = new SliderSetting("Lag Chance", 0, 0, 100, 5);

    private final Minecraft mc = Minecraft.getMinecraft();
    private VelocityHandler handler;
    private Channel boundChannel;
    private int playerId = -1;

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

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (mc.thePlayer != null) playerId = mc.thePlayer.getEntityId();
        armChannel();
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
        if (mc.thePlayer != null) playerId = mc.thePlayer.getEntityId();
        armChannel();
    }

    @Override
    public void onDisable() {
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
                        if (lag() && (lagChanceSetting.getValue() <= 0 || Math.random() * 100 >= lagChanceSetting.getValue())) {
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
                    }
                } else if (msg instanceof S27PacketExplosion) {
                    S27PacketExplosion p = (S27PacketExplosion) msg;
                    if (lag() && (lagChanceSetting.getValue() <= 0 || Math.random() * 100 >= lagChanceSetting.getValue())) {
                        return;
                    }
                    if (modify()) {
                        double h = horizontalSetting.getValue();
                        double v = verticalSetting.getValue();
                        msg = new S27PacketExplosion(p.getX(), p.getY(), p.getZ(), p.getStrength(),
                                p.getAffectedBlockPositions(),
                                new Vec3(p.func_149149_c() * h, p.func_149144_d() * v, p.func_149147_e() * h));
                    }
                }
            }
            ctx.fireChannelRead(msg);
        }
    }
}