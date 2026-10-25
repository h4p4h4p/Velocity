package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Creative-style flight without touching the allowFlying capability.
 *
 * Setting isFlying makes vanilla use its flying movement branch: jump ascends, sneak descends,
 * and the fly speed from the player's capabilities drives horizontal movement. Vertical motion is
 * cleared every tick so the player hovers when no key is held. Re-applying it each tick matters
 * because vanilla clears isFlying when a flying player touches the ground.
 *
 * VerusDamage turns the short window after taking a hit into controlled movement: the knockback
 * velocity is replaced with fast horizontal travel in the input direction and a deliberately slow
 * vertical rate, which is the window servers allow for knockback.
 */
public class Flight extends Module {

    /** Horizontal speed during the damage window, in blocks per tick. */
    private static final double VERUS_HORIZONTAL = 1.0D;
    /** Vertical speed during the damage window, kept slow on purpose. */
    private static final double VERUS_VERTICAL = 0.08D;

    private final BooleanSetting verusDamageSetting = new BooleanSetting("VerusDamage", false);

    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean wasFlying;

    public Flight() {
        super("Flight", Category.MOVEMENT);
        settings.add(verusDamageSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (event.player == null || event.player != mc.thePlayer) return;

        EntityPlayerSP player = mc.thePlayer;
        player.capabilities.isFlying = true;

        if (verusDamageSetting.getValue() && player.hurtTime > 0) {
            if (PlayerUtil.isMoving(player)) {
                PlayerUtil.strafe(player, VERUS_HORIZONTAL);
            }
            if (mc.gameSettings.keyBindJump.isKeyDown()) {
                player.motionY = VERUS_VERTICAL;
            } else if (mc.gameSettings.keyBindSneak.isKeyDown()) {
                player.motionY = -VERUS_VERTICAL;
            } else {
                player.motionY = 0.0D;
            }
            return;
        }

        player.motionY = 0.0D;
    }

    @Override
    public void onEnable() {
        wasFlying = mc.thePlayer != null && mc.thePlayer.capabilities.isFlying;
        if (mc.thePlayer != null) {
            mc.thePlayer.capabilities.isFlying = true;
        }
    }

    @Override
    public void onDisable() {
        if (mc.thePlayer == null) return;
        // Creative and spectator keep the flight state they had; everyone else drops back down.
        mc.thePlayer.capabilities.isFlying = mc.thePlayer.capabilities.allowFlying && wasFlying;
    }
}
