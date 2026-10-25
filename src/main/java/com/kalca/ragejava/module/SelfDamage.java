package com.kalca.ragejava.module;

import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C03PacketPlayer;

/**
 * Takes half a heart off the player through the server's own fall-damage check.
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

    private final Minecraft mc = Minecraft.getMinecraft();

    public SelfDamage() {
        super("SelfDamage", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        if (mc.thePlayer == null || mc.theWorld == null || mc.getNetHandler() == null) return;
        if (mc.thePlayer.capabilities.allowFlying) return;

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
    }
}
