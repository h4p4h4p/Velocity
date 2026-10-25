package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.ModeSetting;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

/**
 * Replaces the MC|Brand custom payload the client sends in NetHandlerPlayClient.handleJoinGame.
 *
 * The packet is written on the outgoing channel, so the real FML brand never reaches the server.
 * The handler is installed on ClientConnectedToServerEvent, which FML fires when the handshake
 * finishes and before the join packet arrives. Enabling the module mid-game sends a fresh brand
 * payload so plugins that read the latest value pick it up without a reconnect.
 */
public class ClientSpoofer extends Module {

    public static final String MODE_VANILLA = "Vanilla";
    public static final String MODE_BADLION = "Badlion";
    public static final String MODE_LUNAR = "Lunar";
    public static final String MODE_GEYSER = "Geyser";
    public static final String MODE_CHEATBREAKER = "CheatBreaker";

    private static final String BRAND_CHANNEL = "MC|Brand";
    private static final String HANDLER_NAME = "ragejava_clientspoofer";

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_VANILLA, MODE_BADLION, MODE_LUNAR, MODE_GEYSER, MODE_CHEATBREAKER}, 0);

    private final Minecraft mc = Minecraft.getMinecraft();

    private Channel boundChannel;
    private BrandHandler handler;

    // Shared with the Netty handler, which runs on the channel thread.
    private volatile String lastBrand;
    private volatile boolean spoofedThisConnection;

    public ClientSpoofer() {
        super("ClientSpoofer", Category.INTERFACE);
        settings.add(modeSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private String mode() {
        String mode = modeSetting.getValue();
        return mode == null ? MODE_VANILLA : mode;
    }

    private String brand() {
        String mode = mode();
        if (MODE_BADLION.equals(mode)) return "badlion";
        if (MODE_LUNAR.equals(mode)) return "lunarclient:v2.21.33-2612";
        if (MODE_GEYSER.equals(mode)) return "geyser";
        if (MODE_CHEATBREAKER.equals(mode)) return "CB";
        return "vanilla";
    }

    @Override
    public String getTag() {
        return mode();
    }

    /** Fired on the channel thread once the FML handshake is done and before S01PacketJoinGame. */
    @SubscribeEvent
    public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        spoofedThisConnection = false;
        if (!isEnabled()) return;
        install(event.manager.channel());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        if (!isEnabled()) {
            if (handler != null || boundChannel != null) removeHandler();
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) return;

        armChannel();

        String brand = brand();
        if (!spoofedThisConnection || !brand.equals(lastBrand)) {
            sendBrand(brand);
        }
    }

    private void sendBrand(String brand) {
        if (mc.getNetHandler() == null) return;
        mc.getNetHandler().addToSendQueue(brandPacket(brand));
        lastBrand = brand;
        spoofedThisConnection = true;
    }

    private C17PacketCustomPayload brandPacket(String brand) {
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        buffer.writeString(brand);
        return new C17PacketCustomPayload(BRAND_CHANNEL, buffer);
    }

    private void armChannel() {
        if (mc.getNetHandler() == null) return;
        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return;
        install(manager.channel());
    }

    private void install(Channel channel) {
        if (channel == null || !channel.isActive()) return;

        if (boundChannel != channel) {
            removeFrom(boundChannel);
            boundChannel = channel;
            handler = null;
        }
        if (handler != null) return;

        final BrandHandler created = new BrandHandler();
        handler = created;
        Runnable add = () -> {
            try {
                if (isEnabled() && channel.isActive()
                        && channel.pipeline().get(HANDLER_NAME) == null) {
                    channel.pipeline().addBefore("packet_handler", HANDLER_NAME, created);
                }
            } catch (Exception ignored) {
            }
        };

        if (channel.eventLoop().inEventLoop()) {
            // ClientConnectedToServerEvent runs on the channel thread; installing inline is
            // what guarantees the handler is present before the join packet is written.
            add.run();
        } else {
            try {
                channel.eventLoop().execute(add);
            } catch (Exception ignored) {
            }
        }
    }

    private void removeHandler() {
        handler = null;
        Channel channel = boundChannel;
        boundChannel = null;
        removeFrom(channel);
    }

    private void removeFrom(Channel channel) {
        if (channel == null || !channel.isActive()) return;
        try {
            channel.eventLoop().execute(() -> {
                try {
                    if (channel.pipeline().get(HANDLER_NAME) != null) {
                        channel.pipeline().remove(HANDLER_NAME);
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onEnable() {
        lastBrand = null;
        spoofedThisConnection = false;
        armChannel();
    }

    @Override
    public void onDisable() {
        lastBrand = null;
        spoofedThisConnection = false;
        removeHandler();
    }

    private class BrandHandler extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
            if (msg instanceof C17PacketCustomPayload) {
                C17PacketCustomPayload packet = (C17PacketCustomPayload) msg;
                if (BRAND_CHANNEL.equals(packet.getChannelName())) {
                    String brand = brand();
                    msg = brandPacket(brand);
                    lastBrand = brand;
                    spoofedThisConnection = true;
                }
            }
            super.write(ctx, msg, promise);
        }
    }
}
