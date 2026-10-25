package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.settings.ModeSetting;
import com.kalca.ragejava.settings.Setting;
import com.kalca.ragejava.settings.SliderSetting;
import com.kalca.ragejava.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class Speed extends Module {

    public static final String MODE_NORMAL = "Normal";
    public static final String MODE_LEGIT_HOP = "LegitHop";

    // Old configs selected HurtBoost as mode index 2. Without this it would clamp to LegitHop.
    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_NORMAL, MODE_LEGIT_HOP}, 0) {
        @Override
        public void fromConfigString(String s) {
            if ("2".equals(s.trim())) {
                setIndex(0);
                return;
            }
            super.fromConfigString(s);
        }
    };
    private final SliderSetting speedSetting = new SliderSetting("Speed", 1.2, 0.5, 5.0, 0.05);
    private final BooleanSetting autoJumpSetting = new BooleanSetting("Auto Jump", true);

    private final Minecraft mc = Minecraft.getMinecraft();

    // VerusHop-style speed: one acceleration on the ground, then decay in the air.
    // Re-strafing to full speed every airborne tick is what the speed checks see.
    private double airSpeed;

    public Speed() {
        super("Speed", Category.MOVEMENT);
        settings.add(modeSetting);
        settings.add(speedSetting);
        settings.add(autoJumpSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == speedSetting) return !legitHop();
        if (setting == autoJumpSetting) return !legitHop();
        return super.isSettingVisible(setting);
    }

    private String mode() {
        return modeSetting.getValue();
    }

    private boolean legitHop() {
        return MODE_LEGIT_HOP.equals(mode());
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;

        EntityPlayer player = event.player;
        if (player == null || player != mc.thePlayer) return;
        if (player.capabilities.isFlying || player.isInWater() || player.isInLava()) return;

        if (legitHop()) {
            // Bunny hop off vanilla movement; no strafe override, so sprint jumps keep their momentum.
            if (player.onGround && PlayerUtil.isMoving(player)) {
                player.jump();
            }
            return;
        }

        if (!PlayerUtil.isMoving(player) || player.isSneaking()) return;

        if (player.onGround) {
            airSpeed = 0.28D * speedSetting.getValue();
            if (autoJumpSetting.getValue()) {
                player.jump();
            }
        } else {
            airSpeed *= 0.98D;
        }

        if (airSpeed > 0.0D) {
            PlayerUtil.strafe(player, airSpeed);
        }
    }

    @Override
    public String getTag() {
        if (legitHop()) return MODE_LEGIT_HOP;
        double value = speedSetting.getValue();
        return (Math.round(value * 100.0) / 100.0) + "x";
    }

    @Override
    public void onEnable() {
        airSpeed = 0.0D;
    }

    @Override
    public void onDisable() {
        airSpeed = 0.0D;
    }
}