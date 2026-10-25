package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Damages the player through the server's own fall-damage check, once per tick for a set span.
 *
 * The server builds fall distance from the vertical delta of movement packets, not from the
 * client's rendered position. Reporting a spot 3.2 blocks up, then back down, then on-ground
 * registers as a short fall while the player stands still. It is three packets per hit instead
 * of the old 131-packet bob, which tripped packet-rate checks long before any movement check.
 *
 * Fall distance only accumulates while a block is within the server's ground probe, so this
 * needs solid ground under the player and enough ceiling clearance to report the upper position.
 * Creative and allowFlying players are never hurt by falls.
 */
public class SelfDamage extends Module {

    /** Fall distance reported by one burst. Just over three blocks deals one point. */
    private static final double FALL_DISTANCE = 3.2D;

    private final SliderSetting ticksSetting = new SliderSetting("Ticks", 20, 1, 60, 1);

    private final Minecraft mc = Minecraft.getMinecraft();
    private int ticksLeft;

    public SelfDamage() {
        super("SelfDamage", Category.COMBAT);
        settings.add(ticksSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public void onEnable() {
        // Outside a world the span is spent, so a module left on in the config does not fire
        // the moment the player joins. Inside a world it waits for a hit it can actually land.
        ticksLeft = mc.thePlayer != null && mc.theWorld != null
                ? (int) Math.round(ticksSetting.getValue()) : 0;
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (event.player == null || event.player != mc.thePlayer) return;
        if (ticksLeft <= 0) return;

        // A ceiling lower than the reported fall would make the server reject the packet and
        // reset the player, so hold the span rather than send a flagged burst that cannot land.
        if (!canDamage() || !hasHeadroom()) return;

        sendBurst();
        ticksLeft--;
    }

    private boolean canDamage() {
        return mc.thePlayer != null && mc.theWorld != null && mc.getNetHandler() != null
                && mc.thePlayer.onGround && !mc.thePlayer.capabilities.allowFlying
                && mc.thePlayer.getHealth() > 0.0F;
    }

    private boolean hasHeadroom() {
        EntityPlayerSP player = mc.thePlayer;
        return mc.theWorld.getCollidingBoundingBoxes(player,
                player.getEntityBoundingBox().offset(0.0D, FALL_DISTANCE, 0.0D)).isEmpty();
    }

    private void sendBurst() {
        EntityPlayerSP player = mc.thePlayer;
        double x = player.posX;
        double y = player.posY;
        double z = player.posZ;

        // Top of the fall, the landing spot, then the on-ground packet that applies it.
        send(new C03PacketPlayer.C04PacketPlayerPosition(x, y + FALL_DISTANCE, z, false));
        send(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false));
        send(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, true));
    }

    private void send(C03PacketPlayer packet) {
        mc.getNetHandler().addToSendQueue(packet);
    }

    @Override
    public void onDisable() {
        ticksLeft = 0;
    }
}
