package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.init.Items;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Holds the camera at the FOV configured in the video settings.
 *
 * Forge fires FOVModifier after vanilla has applied every dynamic multiplier - sprinting,
 * flying, speed potions, drawing a bow, water and death - so replacing the value there
 * removes the per-frame changes while leaving the player's chosen base FOV alone.
 *
 * Keep Bow Zoom re-applies only the bow-draw multiplier, so the aim zoom still works while
 * all the other sources of FOV movement stay flattened.
 */
public class NoFOV extends Module {

    private final BooleanSetting keepBowZoomSetting = new BooleanSetting("Keep Bow Zoom", false);

    private final Minecraft mc = Minecraft.getMinecraft();

    public NoFOV() {
        super("NoFOV", Category.RENDER);
        settings.add(keepBowZoomSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onFovModifier(EntityViewRenderEvent.FOVModifier event) {
        if (!isEnabled()) return;
        float fov = mc.gameSettings.fovSetting;
        if (keepBowZoomSetting.getValue()) {
            fov *= bowZoomFactor();
        }
        event.setFOV(fov);
    }

    /**
     * Vanilla's bow FOV multiplier, copied from AbstractClientPlayer#getFovModifier.
     * It drops to 0.85 over the first second of the draw, which is the zoom being kept.
     */
    private float bowZoomFactor() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || !player.isUsingItem() || player.getItemInUse().getItem() != Items.bow) {
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
