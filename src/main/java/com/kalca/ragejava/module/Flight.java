package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.settings.ModeSetting;
import com.kalca.ragejava.settings.Setting;
import com.kalca.ragejava.settings.SliderSetting;
import com.kalca.ragejava.util.PlayerUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Timer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Creative-style flight without touching the allowFlying capability.
 *
 * Setting isFlying makes vanilla use its flying branch: jump ascends, sneak descends, and the
 * fly speed from the player's capabilities drives horizontal movement. Vertical motion is cleared
 * every tick so the player hovers when no key is held. Re-applying it each tick matters because
 * vanilla clears isFlying when a flying player touches the ground.
 *
 * VerusDamage replaces that with FDPClient's three VerusDamage boost variants. They do not use
 * creative flight: they drive motion directly, slow the client timer, and (for Boost1/Boost2)
 * rewrite every outgoing movement packet to claim the player is on the ground.
 */
public class Flight extends Module {

    public static final String MODE_BOOST1 = "Boost1";
    public static final String MODE_BOOST2 = "Boost2";
    public static final String MODE_BOOST3 = "Boost3";

    private static final String HANDLER_NAME = "ragejava_verusdamage";

    private final BooleanSetting verusDamageSetting = new BooleanSetting("VerusDamage", false);
    private final ModeSetting verusModeSetting = new ModeSetting("Mode",
            new String[]{MODE_BOOST1, MODE_BOOST2, MODE_BOOST3}, 0);
    private final SliderSetting verusSpeedSetting =
            new SliderSetting("Speed", 1.5D, 0.0D, 10.0D, 0.05D);
    private final BooleanSetting reDamageSetting = new BooleanSetting("ReDamage", true);

    private static Field timerField;

    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean wasFlying;
    private int ticks;
    private int ticks2;
    private VerusHandler handler;
    private Channel boundChannel;

    public Flight() {
        super("Flight", Category.MOVEMENT);
        settings.add(verusDamageSetting);
        settings.add(verusModeSetting);
        settings.add(verusSpeedSetting);
        settings.add(reDamageSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == verusModeSetting || setting == verusSpeedSetting) {
            return verusDamageSetting.getValue();
        }
        if (setting == reDamageSetting) {
            return verusDamageSetting.getValue() && MODE_BOOST3.equals(mode());
        }
        return super.isSettingVisible(setting);
    }

    private String mode() {
        String mode = verusModeSetting.getValue();
        return mode == null ? MODE_BOOST1 : mode;
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (event.player == null || event.player != mc.thePlayer) return;

        EntityPlayerSP player = mc.thePlayer;

        if (!verusDamageSetting.getValue()) {
            player.capabilities.isFlying = true;
            player.motionY = 0.0D;
            return;
        }

        // The normal Flight branch is not allowed to leak into the VerusDamage variants: in
        // survival they must not be flying, while a creative player keeps the state they had.
        if (!player.capabilities.allowFlying) {
            player.capabilities.isFlying = false;
        }

        if (MODE_BOOST3.equals(mode())) {
            handleBoost3(player);
        } else {
            handleBoost(player);
        }
        armChannel();
    }

    private void handleBoost(EntityPlayerSP player) {
        boolean boost1 = MODE_BOOST1.equals(mode());
        ticks++;

        if (boost1) {
            if (ticks == 3) {
                double x = player.posX;
                double y = player.posY;
                double z = player.posZ;
                send(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 3.25D, z, false));
                send(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false));
                send(new C03PacketPlayer(true));
                setTimerSpeed(0.4F);
                player.jump();
            } else if (ticks == 4) {
                player.motionY += 0.3D;
            }

            sendPlace(player);

            if (ticks < 3) {
                cancelMovement(player);
            } else if (ticks > 4) {
                player.motionY = 0.0684D;
            }

            if (ticks <= 25) {
                setTimerSpeed(0.8F);
                player.motionX = 0.0D;
                player.motionZ = 0.0D;
                if (PlayerUtil.isMoving(player)) {
                    PlayerUtil.strafe(player, verusSpeedSetting.getValue());
                }
            } else if (PlayerUtil.isMoving(player)) {
                PlayerUtil.strafe(player, 0.29D);
            }
            return;
        }

        if (ticks < 3) cancelMovement(player);

        if (ticks == 3) {
            sendPlace(player);
            double x = player.posX;
            double y = player.posY;
            double z = player.posZ;
            double up = y + ThreadLocalRandom.current().nextDouble(3.0D, 3.07D);
            send(new C03PacketPlayer.C06PacketPlayerPosLook(x, up, z,
                    player.rotationYaw, player.rotationPitch, false));
            send(new C03PacketPlayer.C06PacketPlayerPosLook(x, y, z,
                    player.rotationYaw, player.rotationPitch, false));
            send(new C03PacketPlayer.C06PacketPlayerPosLook(x, y, z,
                    player.rotationYaw, player.rotationPitch, true));
            setTimerSpeed(0.25F);
        }

        if (player.hurtTime > 2) {
            player.motionY += 0.4D;
            setTimerSpeed(1.0F);
            if (PlayerUtil.isMoving(player)) {
                PlayerUtil.strafe(player, verusSpeedSetting.getValue());
            }
        }
        if (player.hurtTime == 3) {
            player.motionY = 0.42D;
        }
        if (player.hurtTime == 0) {
            if (PlayerUtil.isMoving(player)) {
                PlayerUtil.strafe(player, 0.36D);
            }
            if (player.fallDistance > 0.0F) {
                player.motionY = 0.0D;
            }
        }
    }

    private void handleBoost3(EntityPlayerSP player) {
        if (ticks2 == 1) {
            send(new C0BPacketEntityAction(player, C0BPacketEntityAction.Action.START_SPRINTING));
            double x = player.posX;
            double y = player.posY;
            double z = player.posZ;
            send(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, true));
            send(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 3.42D, z, false));
            send(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false));
            send(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, true));
            setTimerSpeed(0.15F);
            player.jump();
            player.onGround = true;
        } else if (ticks2 == 2) {
            setTimerSpeed(1.0F);
        }

        if (player.onGround) {
            player.jump();
        }
        if (player.fallDistance > 1.0F) {
            player.motionY = -(player.posY - Math.floor(player.posY));
        }
        if (player.motionY == 0.0D) {
            player.jump();
            player.onGround = true;
            player.fallDistance = 0.0F;
        }

        if (ticks2 < 25) {
            if (PlayerUtil.isMoving(player)) {
                PlayerUtil.strafe(player, verusSpeedSetting.getValue());
            }
        } else {
            if (ticks2 == 25 && PlayerUtil.isMoving(player)) {
                PlayerUtil.strafe(player, 0.48D);
            }
            if (reDamageSetting.getValue()) {
                ticks2 = 1;
            }
            if (PlayerUtil.isMoving(player)) {
                double speed = Math.sqrt(player.motionX * player.motionX
                        + player.motionZ * player.motionZ);
                PlayerUtil.strafe(player, speed);
            }
        }
        ticks2++;
    }

    private void cancelMovement(EntityPlayerSP player) {
        player.motionX = 0.0D;
        player.motionY = 0.0D;
        player.motionZ = 0.0D;
        player.movementInput.moveForward = 0.0F;
        player.movementInput.moveStrafe = 0.0F;
        player.movementInput.jump = false;
    }

    /** The Minecraft timer field is private in the MCP mappings, so reach it through Forge. */
    private void setTimerSpeed(float speed) {
        try {
            if (timerField == null) {
                timerField = ReflectionHelper.findField(Minecraft.class, "timer", "field_71428_T");
            }
            Timer timer = (Timer) timerField.get(mc);
            if (timer != null) timer.timerSpeed = speed;
        } catch (Exception ignored) {
        }
    }

    private void sendPlace(EntityPlayerSP player) {
        BlockPos pos = player.getPosition().add(0.0D, -1.5D, 0.0D);
        send(new C08PacketPlayerBlockPlacement(pos, 1, new ItemStack(Blocks.stone), 0.0F,
                (float) ThreadLocalRandom.current().nextDouble(0.5D, 0.94D), 0.0F));
    }

    private void send(Packet<?> packet) {
        mc.getNetHandler().addToSendQueue(packet);
    }

    private void armChannel() {
        if (mc.getNetHandler() == null) return;
        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return;
        Channel channel = manager.channel();
        if (channel == null || !channel.isActive()) return;

        if (handler == null) {
            handler = new VerusHandler();
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

    private void removeChannel() {
        VerusHandler current = handler;
        handler = null;
        Channel channel = boundChannel;
        boundChannel = null;
        if (channel != null && channel.isActive()) {
            channel.eventLoop().execute(() -> {
                try {
                    if (channel.pipeline().get(HANDLER_NAME) != null) {
                        channel.pipeline().remove(HANDLER_NAME);
                    }
                } catch (Exception ignored) {
                }
            });
        }
    }

    private class VerusHandler extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
            if (msg instanceof C03PacketPlayer && shouldSpoofGround()) {
                msg = forceGround((C03PacketPlayer) msg);
            }
            super.write(ctx, msg, promise);
        }
    }

    private boolean shouldSpoofGround() {
        return isEnabled() && verusDamageSetting.getValue() && ticks < 3
                && (MODE_BOOST1.equals(mode()) || MODE_BOOST2.equals(mode()));
    }

    private static C03PacketPlayer forceGround(C03PacketPlayer packet) {
        if (packet.isOnGround()) return packet;
        if (packet instanceof C03PacketPlayer.C06PacketPlayerPosLook) {
            return new C03PacketPlayer.C06PacketPlayerPosLook(packet.getPositionX(),
                    packet.getPositionY(), packet.getPositionZ(), packet.getYaw(),
                    packet.getPitch(), true);
        }
        if (packet instanceof C03PacketPlayer.C05PacketPlayerLook) {
            return new C03PacketPlayer.C05PacketPlayerLook(packet.getYaw(),
                    packet.getPitch(), true);
        }
        if (packet instanceof C03PacketPlayer.C04PacketPlayerPosition) {
            return new C03PacketPlayer.C04PacketPlayerPosition(packet.getPositionX(),
                    packet.getPositionY(), packet.getPositionZ(), true);
        }
        return new C03PacketPlayer(true);
    }

    @Override
    public void onEnable() {
        wasFlying = mc.thePlayer != null && mc.thePlayer.capabilities.isFlying;
        ticks = 0;
        ticks2 = 1;
        if (!verusDamageSetting.getValue() && mc.thePlayer != null) {
            mc.thePlayer.capabilities.isFlying = true;
        }
        armChannel();
    }

    @Override
    public void onDisable() {
        ticks = 0;
        ticks2 = 1;
        if (mc.thePlayer != null) {
            mc.thePlayer.motionX = 0.0D;
            mc.thePlayer.motionY = 0.0D;
            mc.thePlayer.motionZ = 0.0D;
            mc.thePlayer.capabilities.isFlying = wasFlying;
        }
        setTimerSpeed(1.0F);
        removeChannel();
    }
}
