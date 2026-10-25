package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.settings.ModeSetting;
import com.kalca.ragejava.settings.Setting;
import com.kalca.ragejava.settings.SliderSetting;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Filters attacks the same way the HitSelect modules in FDPClient, Raven and Vape do.
 *
 * In 1.8 PvP, a hit that lands while the opponent is still in their damage cooldown does no
 * damage, but it still consumes your swing, resets your sprint and slows you down. Stopping
 * those clicks keeps you moving faster and stops you feeding the opponent knockback, which is
 * the "burst" behaviour. Waiting until you are hit first is what players mean by hit selecting:
 * the opponent commits to a hit, you take it, and your next hit starts the combo on your terms.
 *
 * Critical mode only lets a click through while you are falling, because 1.8 critical hits
 * require motionY < 0, fall distance > 0 and no ladder, liquid or vehicle. The attack packets
 * are filtered on the outgoing Netty channel, so it works whether the click came from the mouse
 * or from LeftClicker.
 */
public class HitSelect extends Module {

    public static final String MODE_PAUSE = "Pause";
    public static final String MODE_CRITICAL = "Critical";

    private static final String HANDLER_NAME = "ragejava_hitselect";

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_PAUSE, MODE_CRITICAL}, 0);
    private final SliderSetting chanceSetting = new SliderSetting("Chance", 100, 0, 100, 1);
    private final SliderSetting pauseSetting = new SliderSetting("Pause", 500, 0, 1000, 50);
    private final SliderSetting waitForFirstHitSetting =
            new SliderSetting("Wait for First Hit", 0, 0, 500, 50);
    private final BooleanSetting disableDuringKnockbackSetting =
            new BooleanSetting("Disable During Knockback", false);
    private final SliderSetting targetHurtTimeSetting =
            new SliderSetting("Target Hurt Time", 0, 0, 10, 1);

    private final Minecraft mc = Minecraft.getMinecraft();

    private Channel boundChannel;
    private HitSelectHandler handler;

    // Shared with the Netty handler, which runs on the channel thread.
    private volatile boolean gotHit;
    private volatile int lastTargetId = -1;
    private volatile long waitStartedMs;
    private volatile long pauseUntilMs;

    // Client-thread damage tracking.
    private int lastHurtTime;
    private int lastHurtResistantTime;

    public HitSelect() {
        super("HitSelect", Category.COMBAT);
        settings.add(modeSetting);
        settings.add(chanceSetting);
        settings.add(pauseSetting);
        settings.add(waitForFirstHitSetting);
        settings.add(disableDuringKnockbackSetting);
        settings.add(targetHurtTimeSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == pauseSetting) return MODE_PAUSE.equals(mode());
        if (setting == disableDuringKnockbackSetting || setting == targetHurtTimeSetting) {
            return MODE_CRITICAL.equals(mode());
        }
        return true;
    }

    private String mode() {
        String mode = modeSetting.getValue();
        return mode == null ? MODE_PAUSE : mode;
    }

    @Override
    public String getTag() {
        return mode();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        if (!isEnabled()) {
            if (handler != null || boundChannel != null) removeHandler();
            return;
        }

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            if (handler != null || boundChannel != null) removeHandler();
            return;
        }

        // hurtTime resets to maxHurtTime on a new hit; hurtResistantTime catches hits taken
        // while still inside the previous window.
        if (player.hurtTime > lastHurtTime
                || player.hurtResistantTime > lastHurtResistantTime) {
            gotHit = true;
        }
        lastHurtTime = player.hurtTime;
        lastHurtResistantTime = player.hurtResistantTime;

        armChannel();
    }

    /**
     * Decides whether one outgoing attack should be dropped. Called on the Netty thread, so it
     * only reads Minecraft state and the volatile flags above.
     */
    private boolean shouldCancel(C02PacketUseEntity packet) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) return false;
        if (packet.getAction() != C02PacketUseEntity.Action.ATTACK) return false;

        Entity entity = packet.getEntityFromWorld(mc.theWorld);
        if (!(entity instanceof EntityPlayer) || entity == mc.thePlayer) return false;

        EntityPlayer target = (EntityPlayer) entity;
        EntityPlayerSP player = mc.thePlayer;
        long now = System.currentTimeMillis();

        if (target.getEntityId() != lastTargetId) {
            lastTargetId = target.getEntityId();
            gotHit = false;
            waitStartedMs = now;
            pauseUntilMs = 0L;
        }

        boolean blocked = false;
        double waitMs = waitForFirstHitSetting.getValue();
        if (waitMs > 0.0D && !gotHit && now - waitStartedMs < (long) waitMs) {
            blocked = true;
        }

        if (MODE_CRITICAL.equals(mode())) {
            // While being comboed, let the retaliation through instead of waiting for a crit.
            if (!(disableDuringKnockbackSetting.getValue() && player.hurtTime > 0)) {
                if (!canCriticalHit(player) || !targetCanTakeDamage(target)) {
                    blocked = true;
                }
            }
        } else if (now < pauseUntilMs) {
            blocked = true;
        }

        if (!blocked) {
            if (MODE_PAUSE.equals(mode())) {
                pauseUntilMs = now + (long) pauseSetting.getValue();
            }
            return false;
        }

        double chance = chanceSetting.getValue();
        if (chance <= 0.0D) return false;
        if (chance >= 100.0D) return true;
        return Math.random() * 100.0D < chance;
    }

    /** The same conditions vanilla checks before it awards a critical hit. */
    private boolean canCriticalHit(EntityPlayerSP player) {
        return player.motionY < 0.0D
                && !player.onGround
                && player.fallDistance > 0.0F
                && !player.isOnLadder()
                && !player.isInWater()
                && !player.isInLava()
                && !player.isRiding();
    }

    private boolean targetCanTakeDamage(EntityPlayer target) {
        return target.hurtTime <= (int) targetHurtTimeSetting.getValue()
                || target.hurtResistantTime <= 10;
    }

    private void armChannel() {
        if (mc.getNetHandler() == null) return;
        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return;
        Channel channel = manager.channel();
        if (channel == null || !channel.isActive()) return;

        if (boundChannel != channel) {
            removeFrom(boundChannel);
            boundChannel = channel;
            handler = null;
        }
        if (handler != null) return;

        final HitSelectHandler created = new HitSelectHandler();
        handler = created;
        try {
            channel.eventLoop().execute(() -> {
                try {
                    if (isEnabled() && channel.isActive()
                            && channel.pipeline().get(HANDLER_NAME) == null) {
                        channel.pipeline().addBefore("packet_handler", HANDLER_NAME, created);
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
            // The connection closed between the active check and the task submission.
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
            // The connection closed between the active check and the task submission.
        }
    }

    @Override
    public void onEnable() {
        gotHit = false;
        lastTargetId = -1;
        waitStartedMs = 0L;
        pauseUntilMs = 0L;
        if (mc.thePlayer != null) {
            // Do not treat a hit taken before enabling as the first hit of the fight.
            lastHurtTime = mc.thePlayer.hurtTime;
            lastHurtResistantTime = mc.thePlayer.hurtResistantTime;
        }
    }

    @Override
    public void onDisable() {
        gotHit = false;
        lastTargetId = -1;
        waitStartedMs = 0L;
        pauseUntilMs = 0L;
        removeHandler();
    }

    private class HitSelectHandler extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
            if (msg instanceof C02PacketUseEntity
                    && shouldCancel((C02PacketUseEntity) msg)) {
                // Drop the attack before the server sees it; the client-side swing is untouched.
                promise.setSuccess();
                return;
            }
            super.write(ctx, msg, promise);
        }
    }
}
