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
import net.minecraft.client.settings.KeyBinding;
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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Creative-style flight plus the Verus fly modes ported from FDPClient.
 *
 * Normal keeps the original behaviour: isFlying makes vanilla use its flying branch, and
 * motionY is cleared so the player hovers.
 *
 * Verus, VerusDamage and VerusGlide never use creative flight. They drive motion directly,
 * and Verus/VerusDamage rewrite outgoing movement packets to claim the player is grounded.
 *
 * KeepY records the feet height when the mode is turned on and treats it as a floor, so
 * walking off a block leaves the player hovering at that height while vanilla gravity and
 * jumping still work - the player can hop in the air and drops back to the recorded Y.
 */
public class Flight extends Module {

    public static final String MODE_NORMAL = "Normal";
    public static final String MODE_VERUS = "Verus";
    public static final String MODE_VERUS_DAMAGE = "VerusDamage";
    public static final String MODE_VERUS_GLIDE = "VerusGlide";
    public static final String MODE_KEEP_Y = "KeepY";

    public static final String BOOST1 = "Boost1";
    public static final String BOOST2 = "Boost2";
    public static final String BOOST3 = "Boost3";

    private static final String HANDLER_NAME = "ragejava_verusdamage";

    // KeepY is appended last so saved config indices keep pointing at the same modes.
    private final ModeSetting flyModeSetting = new ModeSetting("Fly Mode",
            new String[]{MODE_NORMAL, MODE_VERUS, MODE_VERUS_DAMAGE, MODE_VERUS_GLIDE,
                    MODE_KEEP_Y}, 0);

    // KeepY mode: claim the floor under the player so the server stops correcting the hover.
    private final BooleanSetting keepYGroundSpoofSetting =
            new BooleanSetting("Ground Spoof", true);

    // Verus mode (FDP: Damage, TimerSlow, BoostTicks, BoostMotion, YBoost).
    private final BooleanSetting verusSelfDamageSetting = new BooleanSetting("Damage", false);
    private final BooleanSetting verusTimerSlowSetting = new BooleanSetting("Timer Slow", true);
    private final SliderSetting verusBoostTicksSetting =
            new SliderSetting("Boost Ticks", 20, 1, 30, 1);
    private final SliderSetting verusBoostMotionSetting =
            new SliderSetting("Boost Motion", 6.5D, 1.0D, 9.85D, 0.05D);
    private final SliderSetting verusYBoostSetting =
            new SliderSetting("Y Boost", 0.42D, 0.0D, 10.0D, 0.01D);

    // VerusDamage mode (FDP: VerusDamageBoostMode, VerusDamageSpeed, Boost3-ReDamage).
    private final ModeSetting boostModeSetting = new ModeSetting("Boost",
            new String[]{BOOST1, BOOST2, BOOST3}, 0);
    private final SliderSetting verusDamageSpeedSetting =
            new SliderSetting("Speed", 1.5D, 0.0D, 10.0D, 0.05D);
    private final BooleanSetting reDamageSetting = new BooleanSetting("ReDamage", true);

    private static Field timerField;

    /** C03 packets that must keep the onGround value they were built with (the Verus setup). */
    private final Set<C03PacketPlayer> exemptPackets =
            Collections.synchronizedSet(Collections.newSetFromMap(
                    new IdentityHashMap<C03PacketPlayer, Boolean>()));

    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean wasFlying;
    /** Feet height captured when KeepY was activated; null until the first in-world tick. */
    private Double keepY;
    private boolean keepYJumpHeld;
    /** True while the player rests on the KeepY floor, which is when the spoof is honest. */
    private boolean keepYHovering;
    private int ticks;
    private int ticks2;
    private int boostTicks;
    private String lastMode = MODE_NORMAL;
    private VerusHandler handler;
    private Channel boundChannel;

    public Flight() {
        super("Flight", Category.MOVEMENT);
        settings.add(flyModeSetting);
        settings.add(keepYGroundSpoofSetting);
        settings.add(verusSelfDamageSetting);
        settings.add(verusTimerSlowSetting);
        settings.add(verusBoostTicksSetting);
        settings.add(verusBoostMotionSetting);
        settings.add(verusYBoostSetting);
        settings.add(boostModeSetting);
        settings.add(verusDamageSpeedSetting);
        settings.add(reDamageSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        String mode = mode();
        if (setting == verusSelfDamageSetting || setting == verusTimerSlowSetting
                || setting == verusBoostTicksSetting || setting == verusBoostMotionSetting
                || setting == verusYBoostSetting) {
            return MODE_VERUS.equals(mode);
        }
        if (setting == boostModeSetting || setting == verusDamageSpeedSetting) {
            return MODE_VERUS_DAMAGE.equals(mode);
        }
        if (setting == reDamageSetting) {
            return MODE_VERUS_DAMAGE.equals(mode) && BOOST3.equals(boostMode());
        }
        if (setting == keepYGroundSpoofSetting) {
            return MODE_KEEP_Y.equals(mode);
        }
        return super.isSettingVisible(setting);
    }

    private String mode() {
        String mode = flyModeSetting.getValue();
        return mode == null ? MODE_NORMAL : mode;
    }

    private String boostMode() {
        String mode = boostModeSetting.getValue();
        return mode == null ? BOOST1 : mode;
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (event.player == null || event.player != mc.thePlayer) return;

        EntityPlayerSP player = mc.thePlayer;
        String currentMode = mode();

        // Changing the mode in the GUI has no callback, so pick it up on the next tick.
        if (!currentMode.equals(lastMode)) {
            enterMode(currentMode);
        }

        if (MODE_NORMAL.equals(currentMode)) {
            player.capabilities.isFlying = true;
            player.motionY = 0.0D;
            return;
        }

        if (MODE_KEEP_Y.equals(currentMode)) {
            handleKeepY(player);
            // Only needed when the spoof is on, but arming every tick lets the option be
            // toggled from the GUI while the mode is running.
            armChannel();
            return;
        }

        if (!player.capabilities.allowFlying) {
            player.capabilities.isFlying = false;
        }

        if (MODE_VERUS.equals(currentMode)) {
            handleVerus(player);
        } else if (MODE_VERUS_GLIDE.equals(currentMode)) {
            handleVerusGlide(player);
        } else if (BOOST3.equals(boostMode())) {
            handleBoost3(player);
        } else {
            handleBoost(player);
        }
        armChannel();
    }

    private void enterMode(String newMode) {
        setTimerSpeed(1.0F);
        ticks = 0;
        ticks2 = 1;
        boostTicks = 0;
        keepYHovering = false;
        lastMode = newMode;

        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return;

        if (MODE_NORMAL.equals(newMode)) {
            player.capabilities.isFlying = true;
            return;
        }
        if (MODE_KEEP_Y.equals(newMode)) {
            // The floor is whatever height the player stood at when the mode was switched on.
            keepY = player.posY;
            keepYJumpHeld = false;
            player.capabilities.isFlying = false;
            return;
        }
        if (!player.capabilities.allowFlying) {
            player.capabilities.isFlying = false;
        }
        if (MODE_VERUS.equals(newMode)) {
            enableVerus(player);
        }
    }

    /**
     * KeepY floor. Gravity and jumping are left alone; the player simply never ends a tick
     * below the height captured on activation.
     *
     * The clamp happens before vanilla applies gravity, so the motion is solved backwards:
     * the tick ends with motionY = (motionY - 0.08) * 0.98, and picking
     * (floor - posY) / 0.98 + 0.08 lands exactly on the floor instead of dropping through
     * and being teleported back, which the server would read as an invalid move. At rest the
     * same formula yields 0.08, which gravity cancels to zero, so hovering does not jitter.
     */
    private void handleKeepY(EntityPlayerSP player) {
        if (keepY == null) keepY = player.posY;
        final double floor = keepY;

        player.capabilities.isFlying = false;

        boolean jumpDown = mc.gameSettings.keyBindJump.isKeyDown();
        if (jumpDown && !keepYJumpHeld) {
            player.motionY = 0.42D;
        }
        keepYJumpHeld = jumpDown;

        if (player.posY < floor - 0.001D) {
            // Safety net for anything that moved the player without going through the clamp.
            player.setPosition(player.posX, floor, player.posZ);
            player.motionY = 0.0D;
        } else if (player.motionY <= 0.0D
                && player.posY + (player.motionY - 0.08D) * 0.98D < floor) {
            player.motionY = (floor - player.posY) / 0.98D + 0.08D;
        }

        // Only claim the ground while actually resting on the floor. A hop has to leave the
        // ground normally, otherwise the server sees upward motion from a grounded player.
        keepYHovering = player.posY <= floor + 0.001D && player.motionY <= 0.1D;
        player.fallDistance = 0.0F;
    }

    /**
     * FDP's Verus mode enable: optionally spoof the three-block fall to get the damage window,
     * then move the client up by YBoost so the flight starts airborne.
     */
    private void enableVerus(EntityPlayerSP player) {
        if (mc.theWorld == null) return;

        if (mc.theWorld.getCollidingBoundingBoxes(player,
                player.getEntityBoundingBox().offset(0.0D, 3.0001D, 0.0D)).isEmpty()) {
            if (verusSelfDamageSetting.getValue()) {
                sendSetup(new C03PacketPlayer.C04PacketPlayerPosition(
                        player.posX, player.posY + 3.0001D, player.posZ, false));
            }
            sendSetup(new C03PacketPlayer.C06PacketPlayerPosLook(player.posX, player.posY,
                    player.posZ, player.rotationYaw, player.rotationPitch, false));
            sendSetup(new C03PacketPlayer.C06PacketPlayerPosLook(player.posX, player.posY,
                    player.posZ, player.rotationYaw, player.rotationPitch, true));
        }

        player.setPosition(player.posX, player.posY + verusYBoostSetting.getValue(), player.posZ);
    }

    private void handleVerus(EntityPlayerSP player) {
        player.motionX = 0.0D;
        player.motionY = 0.0D;
        player.motionZ = 0.0D;

        if (boostTicks == 0 && player.hurtTime > 0) {
            boostTicks = (int) Math.round(verusBoostTicksSetting.getValue());
        }
        boostTicks--;

        if (verusTimerSlowSetting.getValue()) {
            setTimerSpeed(player.ticksExisted % 3 == 0 ? 0.15F : 0.08F);
        }

        // FDP cancels the jump event in this mode; holding jump would replace motionY.
        suppressJump();

        if (PlayerUtil.isMoving(player)) {
            PlayerUtil.strafe(player, verusBoostMotionSetting.getValue());
        }
    }

    private void handleVerusGlide(EntityPlayerSP player) {
        if (player.isInWater() || player.isInLava() || player.isSneaking()) return;
        if (player.onGround || player.fallDistance <= 1.0F) return;

        player.motionY = -0.098D;
        if (PlayerUtil.isMoving(player)) {
            boolean diagonal = player.movementInput.moveForward != 0.0F
                    && player.movementInput.moveStrafe != 0.0F;
            PlayerUtil.strafe(player, diagonal ? 0.334D : 0.3345D);
        }
    }

    private void handleBoost(EntityPlayerSP player) {
        boolean boost1 = BOOST1.equals(boostMode());
        ticks++;

        if (boost1) {
            suppressJump();

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
                    PlayerUtil.strafe(player, verusDamageSpeedSetting.getValue());
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
                PlayerUtil.strafe(player, verusDamageSpeedSetting.getValue());
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
                PlayerUtil.strafe(player, verusDamageSpeedSetting.getValue());
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

    private void suppressJump() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
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

    /** Setup packets keep their authored onGround flag even if the spoofing handler is armed. */
    private void sendSetup(Packet<?> packet) {
        if (packet instanceof C03PacketPlayer) {
            exemptPackets.add((C03PacketPlayer) packet);
        }
        send(packet);
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
            final VerusHandler created = new VerusHandler();
            handler = created;
            boundChannel = channel;
            if (channel.pipeline().get(HANDLER_NAME) == null) {
                channel.eventLoop().execute(() -> {
                    try {
                        // The module may have been disabled before this task ran.
                        if (handler == created
                                && channel.pipeline().get(HANDLER_NAME) == null) {
                            channel.pipeline().addBefore("packet_handler", HANDLER_NAME, created);
                        }
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
            if (msg instanceof C03PacketPlayer) {
                C03PacketPlayer packet = (C03PacketPlayer) msg;
                if (exemptPackets.remove(packet)) {
                    // Written exactly as built; this is the Verus setup burst.
                } else if (shouldSpoofGround()) {
                    msg = forceGround(packet);
                }
            }
            super.write(ctx, msg, promise);
        }
    }

    private boolean shouldSpoofGround() {
        if (!isEnabled()) return false;
        String mode = mode();
        if (MODE_VERUS.equals(mode)) return true;
        if (MODE_KEEP_Y.equals(mode)) {
            return keepYGroundSpoofSetting.getValue() && keepYHovering;
        }
        if (!MODE_VERUS_DAMAGE.equals(mode)) return false;
        return ticks < 3 && (BOOST1.equals(boostMode()) || BOOST2.equals(boostMode()));
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
        enterMode(mode());
        armChannel();
    }

    @Override
    public void onDisable() {
        ticks = 0;
        ticks2 = 1;
        boostTicks = 0;
        keepY = null;
        keepYJumpHeld = false;
        keepYHovering = false;
        if (mc.thePlayer != null) {
            mc.thePlayer.motionX = 0.0D;
            mc.thePlayer.motionY = 0.0D;
            mc.thePlayer.motionZ = 0.0D;
            mc.thePlayer.capabilities.isFlying = wasFlying;
        }
        setTimerSpeed(1.0F);
        exemptPackets.clear();
        removeChannel();
    }
}
