package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

public class RightClicker extends Module {

    private final SliderSetting delaySetting = new SliderSetting("Delay", 100, 0, 1000, 10);
    private final SliderSetting cpsSetting = new SliderSetting("CPS", 12, 1, 20, 1);
    private final SliderSetting randomSetting = new SliderSetting("Randomization", 0, 0, 100, 5);

    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean wasDown;
    private long pressTime;
    private long lastClick;

    public RightClicker() {
        super("RightClicker", Category.COMBAT);
        settings.add(delaySetting);
        settings.add(cpsSetting);
        settings.add(randomSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) {
            reset();
            return;
        }

        boolean down = Mouse.isButtonDown(1);
        long now = System.currentTimeMillis();

        if (down && !wasDown) {
            pressTime = now;
            lastClick = 0;
        }
        wasDown = down;

        if (!down) {
            reset();
            return;
        }

        long delay = (long) delaySetting.getValue();
        if (now - pressTime < delay) return;
        double interval = nextInterval();
        if (lastClick == 0 || now - lastClick >= interval) {
            rightClick();
            lastClick = now;
        }
    }

    private double nextInterval() {
        double base = 1000.0 / cpsSetting.getValue();
        double jitter = (Math.random() * 2.0 - 1.0) * (randomSetting.getValue() / 100.0);
        double interval = base * (1.0 + jitter);
        // Never let negative randomization push the interval below the 20 CPS ceiling.
        double min = Math.max(50.0, base * 0.5);
        double max = Math.min(2000.0, base * 2.5);
        return Math.min(Math.max(interval, min), max);
    }

    private void rightClick() {
        MovingObjectPosition over = mc.objectMouseOver;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, over.getBlockPos(), over.sideHit, over.hitVec);
        }
        if (held != null) {
            mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, held);
        }
        mc.thePlayer.swingItem();
    }

    private void reset() {
        wasDown = false;
        pressTime = 0;
        lastClick = 0;
    }

    @Override
    public String getTag() {
        return Math.round(cpsSetting.getValue()) + " CPS";
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        reset();
    }
}