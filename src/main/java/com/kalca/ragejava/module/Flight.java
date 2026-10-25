package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.settings.Setting;
import com.kalca.ragejava.settings.SliderSetting;
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
 * VerusDamage only moves in the few ticks the server already expects knockback motion in. Earlier
 * versions forced 1.0 blocks/tick for the whole ten-tick hurt flash, which is both far above any
 * knockback velocity and long enough to look like plain flight.
 */
public class Flight extends Module {

    /** Slow vertical rate during the VerusDamage window, in blocks per tick. */
    private static final double VERUS_VERTICAL = 0.08D;

    private final BooleanSetting verusDamageSetting = new BooleanSetting("VerusDamage", false);
    private final SliderSetting verusSpeedSetting = new SliderSetting("Verus Speed", 0.5D, 0.2D, 1.0D, 0.05D);
    private final SliderSetting verusWindowSetting = new SliderSetting("Verus Window", 4, 1, 10, 1);

    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean wasFlying;
    private int verusTicks;
    private int lastHurtTime;

    public Flight() {
        super("Flight", Category.MOVEMENT);
        settings.add(verusDamageSetting);
        settings.add(verusSpeedSetting);
        settings.add(verusWindowSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == verusSpeedSetting || setting == verusWindowSetting) {
            return verusDamageSetting.getValue();
        }
        return super.isSettingVisible(setting);
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (event.player == null || event.player != mc.thePlayer) return;

        EntityPlayerSP player = mc.thePlayer;
        player.capabilities.isFlying = true;

        // A rising hurtTime is a fresh hit, not the rest of the ten-tick flash. The boost is
        // spent over the next few ticks rather than re-applied for the whole flash.
        if (player.hurtTime > lastHurtTime) {
            verusTicks = verusDamageSetting.getValue()
                    ? (int) Math.round(verusWindowSetting.getValue())
                    : 0;
        }
        lastHurtTime = player.hurtTime;

        if (verusTicks > 0) {
            verusTicks--;
            if (PlayerUtil.isMoving(player)) {
                PlayerUtil.strafe(player, verusSpeedSetting.getValue());
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
        verusTicks = 0;
        lastHurtTime = 0;
        if (mc.thePlayer != null) {
            mc.thePlayer.capabilities.isFlying = true;
        }
    }

    @Override
    public void onDisable() {
        verusTicks = 0;
        lastHurtTime = 0;
        if (mc.thePlayer == null) return;
        // Creative and spectator keep the flight state they had; everyone else drops back down.
        mc.thePlayer.capabilities.isFlying = mc.thePlayer.capabilities.allowFlying && wasFlying;
    }
}
