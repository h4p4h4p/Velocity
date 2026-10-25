package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Damages the player through the server's own fall-damage check, once per tick for a set span.
 *
 * The server builds fall distance from the vertical delta of movement packets, not from the
 * client's rendered position. A burst of tiny up/down steps followed by an on-ground packet
 * therefore registers as a short fall while the player stands still. 65 pairs of 0.049 blocks
 * leave ~3.2 blocks of fall distance, the smallest fall that deals one point of damage.
 *
 * Fall distance only accumulates while a block is within the server's ground probe, so this
 * needs solid ground under the player. Creative and allowFlying players are never hurt by falls.
 */
public class SelfDamage extends Module {

    private static final int STEPS = 65;
    private static final double STEP = 0.049D;

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
        ticksLeft = canDamage() ? (int) Math.round(ticksSetting.getValue()) : 0;
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (event.player == null || event.player != mc.thePlayer) return;
        if (ticksLeft <= 0 || !canDamage()) {
            ticksLeft = 0;
            return;
        }

        sendBurst();
        ticksLeft--;
    }

    private boolean canDamage() {
        return mc.thePlayer != null && mc.theWorld != null && mc.getNetHandler() != null
                && !mc.thePlayer.capabilities.allowFlying && mc.thePlayer.getHealth() > 0.0F;
    }

    private void sendBurst() {
        double x = mc.thePlayer.posX;
        double y = mc.thePlayer.posY;
        double z = mc.thePlayer.posZ;

        for (int i = 0; i < STEPS; i++) {
            send(new C03PacketPlayer.C04PacketPlayerPosition(x, y + STEP, z, false));
            send(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false));
        }
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
