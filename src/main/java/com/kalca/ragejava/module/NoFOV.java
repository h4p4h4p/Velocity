package com.kalca.ragejava.module;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Holds the camera at the FOV configured in the video settings.
 *
 * Forge fires FOVModifier after vanilla has applied every dynamic multiplier - sprinting,
 * flying, speed potions, drawing a bow, water and death - so replacing the value there
 * removes the per-frame changes while leaving the player's chosen base FOV alone.
 */
public class NoFOV extends Module {

    private final Minecraft mc = Minecraft.getMinecraft();

    public NoFOV() {
        super("NoFOV", Category.RENDER);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onFovModifier(EntityViewRenderEvent.FOVModifier event) {
        if (!isEnabled()) return;
        event.setFOV(mc.gameSettings.fovSetting);
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
    }
}
