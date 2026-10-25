package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.ModeSetting;
import com.kalca.ragejava.settings.Setting;
import com.kalca.ragejava.settings.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class Jetpack extends Module {

    public static final String MODE_MOTION = "Motion";
    public static final String MODE_VELOCITY = "Velocity";
    public static final String MODE_INFJUMP = "InfJump";

    private final ModeSetting modeSetting = new ModeSetting("Mode", new String[]{MODE_MOTION, MODE_VELOCITY, MODE_INFJUMP}, 0);
    private final SliderSetting speedSetting = new SliderSetting("Speed", 0.5, 0.1, 2.0, 0.05);
    private final Minecraft mc = Minecraft.getMinecraft();

    public Jetpack() {
        super("Jetpack", Category.MOVEMENT);
        settings.add(modeSetting);
        settings.add(speedSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        return true;
    }

    private String mode() {
        return modeSetting.getValue();
    }

    private boolean motion() {
        return MODE_MOTION.equals(mode());
    }

    private boolean velocity() {
        return MODE_VELOCITY.equals(mode());
    }

    private boolean infJump() {
        return MODE_INFJUMP.equals(mode());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (mc.thePlayer == null) return;

        if (infJump()) {
            if (mc.gameSettings.keyBindJump.isKeyDown() && !mc.thePlayer.onGround) {
                mc.thePlayer.motionY = speedSetting.getValue();
                mc.thePlayer.velocityChanged = true;
            }
        } else {
            if (mc.gameSettings.keyBindJump.isKeyDown()) {
                double speed = speedSetting.getValue();
                if (motion()) {
                    mc.thePlayer.motionY = speed;
                } else if (velocity()) {
                    mc.thePlayer.motionY = speed;
                    mc.thePlayer.velocityChanged = true;
                }
            }
        }
    }

    @Override
    public String getTag() {
        return modeSetting.getValue();
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
    }
}