package com.kalca.ragejava.module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Takes exactly half a heart off the local player when the module is enabled.
 *
 * EntityPlayerSP rejects attackEntityFrom on the client, so the hit is applied the same way an
 * incoming damage status packet is: run the hurt animation and sound, then remove one health
 * point. Health is server-authoritative, so the server's next health update restores it; this is
 * a local hit, useful for testing damage-triggered behaviour.
 */
public class SelfDamage extends Module {

    /** Minecraft counts health in half-hearts, so one point is half a heart. */
    private static final float HALF_HEART = 1.0F;

    private final Minecraft mc = Minecraft.getMinecraft();

    public SelfDamage() {
        super("SelfDamage", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) return;
        if (player.getHealth() <= 0.0F) return;

        player.handleStatusUpdate((byte) 2);
        player.setHealth(Math.max(0.0F, player.getHealth() - HALF_HEART));
    }

    @Override
    public void onDisable() {
    }
}
