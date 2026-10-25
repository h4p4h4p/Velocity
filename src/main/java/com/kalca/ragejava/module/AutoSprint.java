package com.kalca.ragejava.module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Always-sprint without holding the sprint key.
 *
 * Sprinting is re-asserted at the end of the player tick, but only when vanilla
 * would allow it. While an item is in use (eating, drinking, blocking with a
 * sword, drawing a bow) the movement input is slowed and vanilla cancels
 * sprint; forcing it back on sent START_SPRINTING during those actions. Using
 * the same gate as EntityPlayerSP#onLivingUpdate also avoids START/STOP packet
 * churn against walls, low hunger, blindness and sneaking.
 */
public class AutoSprint extends Module {

    /** Vanilla only sprints once forward input reaches this much of full input. */
    private static final float SPRINT_FORWARD_THRESHOLD = 0.8F;

    private final Minecraft mc = Minecraft.getMinecraft();

    public AutoSprint() {
        super("AutoSprint", Category.MOVEMENT);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!isEnabled()) return;

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || event.player != player) return;
        if (player.isSprinting() || !canSprint(player)) return;

        player.setSprinting(true);
    }

    /**
     * Mirrors the sprint gate in EntityPlayerSP#onLivingUpdate. Item use is
     * checked explicitly so blocking or eating never re-sends START_SPRINTING.
     */
    private boolean canSprint(EntityPlayerSP player) {
        if (player.movementInput.moveForward < SPRINT_FORWARD_THRESHOLD) return false;
        if (player.isUsingItem() || player.isSneaking() || player.isRiding()) return false;
        if (player.isCollidedHorizontally || player.isPotionActive(Potion.blindness)) return false;
        return player.getFoodStats().getFoodLevel() > 6 || player.capabilities.allowFlying;
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
    }
}