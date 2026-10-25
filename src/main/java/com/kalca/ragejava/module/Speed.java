package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.settings.SliderSetting;
import com.kalca.ragejava.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class Speed extends Module {

    private final SliderSetting speedSetting = new SliderSetting("Speed", 1.2, 0.5, 5.0, 0.05);
    private final BooleanSetting autoJumpSetting = new BooleanSetting("Auto Jump", true);

    private final Minecraft mc = Minecraft.getMinecraft();

    public Speed() {
        super("Speed", Category.MOVEMENT);
        settings.add(speedSetting);
        settings.add(autoJumpSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;

        EntityPlayer player = event.player;
        if (player == null || player != mc.thePlayer) return;
        if (player.capabilities.isFlying || player.isInWater() || player.isInLava()) return;

        if (autoJumpSetting.getValue() && player.onGround && PlayerUtil.isMoving(player)) {
            player.jump();
        }

        if (PlayerUtil.isMoving(player)) {
            PlayerUtil.strafe(player, 0.28 * speedSetting.getValue());
        }
    }

    @Override
    public String getTag() {
        double value = speedSetting.getValue();
        return (Math.round(value * 100.0) / 100.0) + "x";
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
    }
}