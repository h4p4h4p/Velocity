package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.FOVUpdateEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Holds the camera at the FOV configured in the video settings.
 *
 * The work is split over two hooks because vanilla computes the world and hand projections
 * differently. FOVUpdateEvent feeds EntityRenderer.fovModifierHand, which is only used by the
 * world pass, so overriding it there removes sprinting, flying, potion and bow changes without
 * touching the hand. FOVModifier then divides out the water and death multipliers that vanilla
 * applies after that stage; dividing preserves whatever base each pass started with instead of
 * forcing the video setting onto the hand.
 *
 * Keep Bow Zoom re-applies only the bow-draw multiplier to the world pass. Vanilla's own
 * fovModifierHand smoothing still runs, so the zoom eases in and out as it normally would.
 */
public class NoFOV extends Module {

    private final BooleanSetting keepBowZoomSetting = new BooleanSetting("Keep Bow Zoom", false);

    private final Minecraft mc = Minecraft.getMinecraft();

    public NoFOV() {
        super("NoFOV", Category.RENDER);
        settings.add(keepBowZoomSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** World-pass multiplier: 1.0 removes every dynamic change, or the bow draw alone. */
    @SubscribeEvent
    public void onFovUpdate(FOVUpdateEvent event) {
        if (!isEnabled()) return;
        event.newfov = keepBowZoomSetting.getValue() ? bowZoomFactor(event.entity) : 1.0F;
    }

    /** Removes the two modifiers vanilla applies after fovModifierHand, without changing the base. */
    @SubscribeEvent
    public void onFovModifier(EntityViewRenderEvent.FOVModifier event) {
        if (!isEnabled()) return;

        float factor = 1.0F;
        Block block = event.block;
        if (block != null && block.getMaterial() == Material.water) {
            factor *= 60.0F / 70.0F;
        }

        Entity entity = event.entity;
        if (entity instanceof EntityLivingBase
                && ((EntityLivingBase) entity).getHealth() <= 0.0F) {
            float deathTime = ((EntityLivingBase) entity).deathTime
                    + (float) event.renderPartialTicks;
            factor *= (1.0F - 500.0F / (deathTime + 500.0F)) * 2.0F + 1.0F;
        }

        event.setFOV(event.getFOV() / factor);
    }

    /**
     * Vanilla's bow FOV multiplier, copied from AbstractClientPlayer#getFovModifier.
     * It drops to 0.85 over the first second of the draw, which is the zoom being kept.
     */
    private float bowZoomFactor(EntityPlayer player) {
        if (player == null || !player.isUsingItem()
                || player.getItemInUse().getItem() != Items.bow) {
            return 1.0F;
        }

        float draw = player.getItemInUseDuration() / 20.0F;
        draw = draw > 1.0F ? 1.0F : draw * draw;
        return 1.0F - draw * 0.15F;
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
    }
}
