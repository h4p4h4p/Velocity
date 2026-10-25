package com.kalca.ragejava.module;

import net.minecraft.client.Minecraft;
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
 */
public class Flight extends Module {

    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean wasFlying;

    public Flight() {
        super("Flight", Category.MOVEMENT);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (event.player == null || event.player != mc.thePlayer) return;

        mc.thePlayer.capabilities.isFlying = true;
        mc.thePlayer.motionY = 0.0D;
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
