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
    public static final String MODE_HURT_BOOST = "HurtBoost";

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_NORMAL, MODE_LEGIT_HOP, MODE_HURT_BOOST}, 0);
    private final SliderSetting speedSetting = new SliderSetting("Speed", 1.2, 0.5, 5.0, 0.05);
    private final BooleanSetting autoJumpSetting = new BooleanSetting("Auto Jump", true);
    private final SliderSetting boostSetting = new SliderSetting("Boost", 1.6, 1.0, 5.0, 0.05);
    private final SliderSetting durationSetting = new SliderSetting("Duration", 10, 5, 40, 1);

    private final Minecraft mc = Minecraft.getMinecraft();

    private int hurtTicks;
    private float lastHealth = -1.0F;
    private int lastHurtTime;

    public Speed() {
        super("Speed", Category.MOVEMENT);
        settings.add(modeSetting);
        settings.add(speedSetting);
        settings.add(autoJumpSetting);
        settings.add(boostSetting);
        settings.add(durationSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == speedSetting) return !legitHop() && !hurtBoost();
        if (setting == autoJumpSetting) return !legitHop();
        if (setting == boostSetting || setting == durationSetting) return hurtBoost();
        return super.isSettingVisible(setting);
    }

    private String mode() {
        return modeSetting.getValue();
    }

    private boolean legitHop() {
        return MODE_LEGIT_HOP.equals(mode());
    }

    private boolean hurtBoost() {
        return MODE_HURT_BOOST.equals(mode());
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

        if (hurtBoost()) {
            detectHurt(player);
            if (hurtTicks <= 0) return;

            // The server's movement allowance is briefly raised after knockback; spend that
            // window steering the leftover velocity into the player's input direction.
            if (autoJumpSetting.getValue() && player.onGround && PlayerUtil.isMoving(player)) {
                player.jump();
            }
            if (PlayerUtil.isMoving(player)) {
                PlayerUtil.strafe(player, 0.28 * boostSetting.getValue());
            }
            hurtTicks--;
            return;
        }

        if (autoJumpSetting.getValue() && player.onGround && PlayerUtil.isMoving(player)) {
            player.jump();
        }

        if (PlayerUtil.isMoving(player)) {
            PlayerUtil.strafe(player, 0.28 * speedSetting.getValue());
        }
    }

    /**
     * Opens the post-damage window. A health drop covers every hit that costs health, while the
     * hurtTime edge also catches damage soaked by absorption hearts; healing never reopens it.
     */
    private void detectHurt(EntityPlayer player) {
        float health = player.getHealth();
        if (lastHealth < 0.0F) lastHealth = health;

        boolean damaged = health < lastHealth - 0.01F || (player.hurtTime > 0 && lastHurtTime == 0);
        lastHealth = health;
        lastHurtTime = player.hurtTime;

        if (damaged) {
            hurtTicks = (int) Math.round(durationSetting.getValue());
        }
    }

    @Override
    public String getTag() {
        if (legitHop()) return MODE_LEGIT_HOP;
        if (hurtBoost()) return MODE_HURT_BOOST;
        double value = speedSetting.getValue();
        return (Math.round(value * 100.0) / 100.0) + "x";
    }

    @Override
    public void onEnable() {
        hurtTicks = 0;
        lastHealth = -1.0F;
        lastHurtTime = 0;
    }

    @Override
    public void onDisable() {
        hurtTicks = 0;
    }
}