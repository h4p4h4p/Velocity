package com.kalca.ragejava.module;

import com.kalca.ragejava.gui.Theme;
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
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayDeque;

public class Internet extends Module {

    public static final String MODE_INBOUND = "Inbound";
    public static final String MODE_OUTBOUND = "Outbound";
    public static final String MODE_BOTH = "Both";
    public static final String MODE_PULSE = "Pulse";

    private static final String HANDLER_NAME = "ragejava_network_modifications";

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_INBOUND, MODE_OUTBOUND, MODE_BOTH, MODE_PULSE}, ModeSetting.UNSET);
    private final ModeSetting pulseDirectionSetting = new ModeSetting("Pulse Direction",
            new String[]{MODE_OUTBOUND, MODE_INBOUND, MODE_BOTH}, 2);
    private final SliderSetting pulseDelaySetting = new SliderSetting("Pulse Delay", 500, 10, 5000, 50);

    private final Minecraft mc = Minecraft.getMinecraft();
    private NetworkHandler handler;
    private Channel boundChannel;
    private boolean anchorSet;
    private double anchorX;
    private double anchorY;
    private double anchorZ;

    public Internet() {
        super("Internet", Category.MOVEMENT);
        settings.add(modeSetting);
        settings.add(pulseDirectionSetting);
        settings.add(pulseDelaySetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == pulseDelaySetting) return pulse();
        if (setting == pulseDirectionSetting) return pulse();
        return super.isSettingVisible(setting);
    }

    public boolean hasAnchor() {
        return anchorSet;
    }

    public double getAnchorX() {
        return anchorX;
    }

    public double getAnchorY() {
        return anchorY;
    }

    public double getAnchorZ() {
        return anchorZ;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        armChannel();
        if (!pulse()) return;

        // The whole cycle decision is handed to the event loop. Deciding it on the client thread and
        // posting a flush would let writes slip past the buffer in between, and those writes would then
        // reach the server *before* the older buffered positions, walking you backwards.
        final NetworkHandler h = handler;
        final Channel channel = boundChannel;
        if (h == null || channel == null || !channel.isActive()) return;
        final long delay = (long) pulseDelaySetting.getValue();
        channel.eventLoop().execute(() -> h.pulse(delay));
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        double camX = mc.getRenderManager().viewerPosX;
        double camY = mc.getRenderManager().viewerPosY;
        double camZ = mc.getRenderManager().viewerPosZ;

        double ax = hasAnchor() ? anchorX : mc.thePlayer.posX;
        double ay = hasAnchor() ? anchorY : mc.thePlayer.posY;
        double az = hasAnchor() ? anchorZ : mc.thePlayer.posZ;
        AxisAlignedBB bb = mc.thePlayer.getEntityBoundingBox();

        AxisAlignedBB anchorBox = AxisAlignedBB.fromBounds(
                bb.minX - mc.thePlayer.posX + ax,
                bb.minY - mc.thePlayer.posY + ay,
                bb.minZ - mc.thePlayer.posZ + az,
                bb.maxX - mc.thePlayer.posX + ax,
                bb.maxY - mc.thePlayer.posY + ay,
                bb.maxZ - mc.thePlayer.posZ + az);
        if (RenderUtil.pointNearBox(camX, camY, camZ, anchorBox, 1.2D)) return;

        int r = (Theme.ACCENT >> 16) & 0xFF;
        int g = (Theme.ACCENT >> 8) & 0xFF;
        int b = Theme.ACCENT & 0xFF;
        int a = 255;

        GlStateManager.disableTexture2D();
        Tessellator tessellator = Tessellator.getInstance();
        double pad = 0.1D;
        AxisAlignedBB box = AxisAlignedBB.fromBounds(
                bb.minX - mc.thePlayer.posX + ax - camX - pad,
                bb.minY - mc.thePlayer.posY + ay - camY - pad,
                bb.minZ - mc.thePlayer.posZ + az - camZ - pad,
                bb.maxX - mc.thePlayer.posX + ax - camX + pad,
                bb.maxY - mc.thePlayer.posY + ay - camY + pad,
                bb.maxZ - mc.thePlayer.posZ + az - camZ + pad);
        RenderUtil.drawOutlinedBox(tessellator, box, r, g, b, a);
        GlStateManager.enableTexture2D();
    }

    private String mode() {
        return modeSetting.getValue();
    }

    private boolean pulse() {
        return MODE_PULSE.equals(mode());
    }

    private void armChannel() {
        if (mc.getNetHandler() == null) return;
        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return;
        Channel channel = manager.channel();
        if (channel == null || !channel.isActive()) return;

        if (handler == null) {
            handler = new NetworkHandler();
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
            channel.eventLoop().execute(() -> {
                try {
                    if (boundChannel != null && boundChannel.isActive()) boundChannel.pipeline().remove(HANDLER_NAME);
                } catch (Exception ignored) {
                }
            });
            boundChannel = channel;
            try {
                if (channel.pipeline().get(HANDLER_NAME) == null) {
                    channel.pipeline().addBefore("packet_handler", HANDLER_NAME, handler);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private boolean shouldHoldInbound() {
        String m = mode();
        if (MODE_PULSE.equals(m)) return true;
        return m.equals(MODE_INBOUND) || m.equals(MODE_BOTH);
    }

    private boolean shouldHoldOutbound() {
        String m = mode();
        if (MODE_PULSE.equals(m)) return true;
        return m.equals(MODE_OUTBOUND) || m.equals(MODE_BOTH);
    }

    @Override
    public void onEnable() {
        if (mc.thePlayer != null) {
            anchorX = mc.thePlayer.posX;
            anchorY = mc.thePlayer.posY;
            anchorZ = mc.thePlayer.posZ;
            anchorSet = true;
        }
        armChannel();
        NetworkHandler h = handler;
        Channel channel = boundChannel;
        if (h != null && channel != null && channel.isActive()) {
            final long delay = (long) pulseDelaySetting.getValue();
            channel.eventLoop().execute(() -> h.rearm(delay));
        }
    }

    @Override
    public void onDisable() {
        NetworkHandler h = handler;
        handler = null;
        if (h != null) h.flush();
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

    private class NetworkHandler extends ChannelDuplexHandler {

        private final ArrayDeque<Packet> inbound = new ArrayDeque<>();
        private final ArrayDeque<Packet> outbound = new ArrayDeque<>();
        private ChannelHandlerContext savedCtx;
        private volatile boolean flushing;

        // Owned by the event loop: only mutated from pulse() and rearm(), both of which run there.
        // A volatile read here keeps the client thread's tick from ever needing to touch it.
        private boolean holding = true;
        private long cycleEnd;

        /** Pulse cycle boundary. Runs on the event loop, so the drain cannot race a live write. */
        void pulse(long delay) {
            long now = System.currentTimeMillis();
            if (now < cycleEnd) return;
            cycleEnd = now + delay;
            if (holding) {
                holding = false;
                drain();
            } else {
                holding = true;
            }
        }

        /** Re-enters the holding phase and starts a fresh cycle. Runs on the event loop. */
        void rearm(long delay) {
            holding = true;
            cycleEnd = System.currentTimeMillis() + delay;
        }

        private boolean pulseHoldsInbound() {
            String d = pulseDirectionSetting.getValue();
            return MODE_BOTH.equals(d) || MODE_INBOUND.equals(d);
        }

        private boolean pulseHoldsOutbound() {
            String d = pulseDirectionSetting.getValue();
            return MODE_BOTH.equals(d) || MODE_OUTBOUND.equals(d);
        }

        private boolean holdsInbound() {
            String m = mode();
            if (MODE_PULSE.equals(m)) return holding && pulseHoldsInbound();
            return shouldHoldInbound();
        }

        private boolean holdsOutbound() {
            String m = mode();
            if (MODE_PULSE.equals(m)) return holding && pulseHoldsOutbound();
            return shouldHoldOutbound();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            savedCtx = ctx;
            if (!flushing && holdsInbound() && msg instanceof Packet) {
                inbound.add((Packet) msg);
                return;
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            savedCtx = ctx;
            if (!flushing && holdsOutbound() && msg instanceof C03PacketPlayer) {
                outbound.add((Packet) msg);
                promise.setSuccess();
                return;
            }
            ctx.write(msg, promise);
        }

        void flush() {
            Channel channel = boundChannel;
            if (channel == null) return;
            channel.eventLoop().execute(() -> drain());
        }

        private void drain() {
            ChannelHandlerContext ctx = savedCtx;
            if (ctx == null) return;
            flushing = true;
            try {
                Packet p;
                while ((p = outbound.poll()) != null) ctx.writeAndFlush(p);
                while ((p = inbound.poll()) != null) ctx.fireChannelRead(p);
            } finally {
                flushing = false;
            }
        }
    }

    @Override
    public String getTag() {
        return modeSetting.getValue();
    }
}