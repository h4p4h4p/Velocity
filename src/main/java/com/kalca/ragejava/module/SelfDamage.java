package com.kalca.ragejava.module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Takes exactly half a heart off the local player and knocks them back when the module is enabled.
 *
 * EntityPlayerSP rejects attackEntityFrom on the client, so the hit is applied the same way an
 * incoming damage status packet is: run the hurt animation and sound, remove one health point,
 * then apply the knockback impulse a hit from one block ahead would produce. Health is
 * server-authoritative, so the server's next health update restores it; the velocity is a normal
 * client-side impulse, useful for testing damage- and velocity-triggered behaviour.
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

        // Vanilla knocks the victim away from the attacker: model an attacker one block in front,
        // so the impulse is backwards and up.
        double yaw = Math.toRadians(player.rotationYaw);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        player.knockBack(player, 0.4F, forwardX, forwardZ);
    }

    @Override
    public void onDisable() {
    }
}
