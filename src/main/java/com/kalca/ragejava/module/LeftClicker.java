package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.settings.ModeSetting;
import com.kalca.ragejava.settings.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

public class LeftClicker extends Module {

    public static final String MODE_NORMAL = "Normal";
    public static final String MODE_TRIGGER = "Trigger";

    // Old configs selected NCP as mode index 2. Without this it would clamp to Trigger.
    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_NORMAL, MODE_TRIGGER}, 0) {
        @Override
        public void fromConfigString(String s) {
            if ("2".equals(s.trim())) {
                setIndex(0);
                return;
            }
            super.fromConfigString(s);
        }
    };
    private final SliderSetting delaySetting = new SliderSetting("Delay", 100, 0, 1000, 10);
    private final SliderSetting cpsSetting = new SliderSetting("CPS", 12, 1, 20, 1);
    private final SliderSetting jitterSetting = new SliderSetting("Jitter", 0, 0, 100, 5);
    private final BooleanSetting blockHitSetting = new BooleanSetting("BlockHit", false);

    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean wasDown;
    private long pressTime;
    private int lastAttackTick = -1;
    private double clickBudget = 1.0;

    public LeftClicker() {
        super("LeftClicker", Category.COMBAT);
        settings.add(modeSetting);
        settings.add(delaySetting);
        settings.add(cpsSetting);
        settings.add(jitterSetting);
        settings.add(blockHitSetting);
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

        boolean blockHit = blockHitSetting.getValue();
        boolean down = Mouse.isButtonDown(0);
        boolean rightDown = Mouse.isButtonDown(1);
        long now = System.currentTimeMillis();

        if (down && !wasDown) {
            pressTime = now;
            lastAttackTick = -1;
            clickBudget = 1.0;
        }
        wasDown = down;

        if (!down) {
            reset();
            return;
        }

        if (blockHit) {
            if (!rightDown) {
                reset();
                return;
            }
            if (mc.thePlayer.isUsingItem()) {
                lastAttackTick = mc.thePlayer.ticksExisted;
                return;
            }

            if (now - pressTime < (long) delaySetting.getValue()) return;
            if (mc.thePlayer.ticksExisted == lastAttackTick) return;

            if (clickDue()) {
                blockHitClick();
                lastAttackTick = mc.thePlayer.ticksExisted;
            }
            return;
        }

        if (mc.thePlayer.isUsingItem()) {
            lastAttackTick = mc.thePlayer.ticksExisted;
            return;
        }

        if (now - pressTime < (long) delaySetting.getValue()) return;
        if (mc.thePlayer.ticksExisted == lastAttackTick) return;

        if (clickDue()) {
            click();
            lastAttackTick = mc.thePlayer.ticksExisted;
        }
    }

    /**
     * Built-in tick spreader. A fraction of a click is banked every tick from the CPS setting, so
     * attacks are paced across ticks instead of being rolled as a burst-prone chance. At most one
     * attack can be released per tick, and leftover credit is capped so lag cannot cause a burst.
     */
    private boolean clickDue() {
        double cps = cpsSetting.getValue();
        if (cps <= 0.0) return false;

        clickBudget += (cps / 20.0) * jitterFactor();
        if (clickBudget < 1.0) return false;

        clickBudget = Math.min(1.0, clickBudget - 1.0);
        return true;
    }

    private void blockHitClick() {
        if (mc.playerController == null || mc.getNetHandler() == null) return;
        if (mc.objectMouseOver == null || mc.objectMouseOver.entityHit == null) return;

        mc.thePlayer.stopUsingItem();
        mc.playerController.onStoppedUsingItem(mc.thePlayer);

        MovingObjectPosition over = mc.objectMouseOver;
        if (over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && over.entityHit != null) {
            mc.thePlayer.swingItem();
            mc.playerController.attackEntity(mc.thePlayer, over.entityHit);
        }

        if (mc.thePlayer.getHeldItem() != null) {
            mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
        }
    }

    private double jitterFactor() {
        return 1.0 + (Math.random() * 2.0 - 1.0) * (jitterSetting.getValue() / 100.0);
    }

    private boolean triggerMode() {
        return MODE_TRIGGER.equals(modeSetting.getValue());
    }

    private void click() {
        if (triggerMode()) {
            clickEntityOnly();
            return;
        }
        MovingObjectPosition over = mc.objectMouseOver;
        if (over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && over.entityHit != null) {
            mc.thePlayer.swingItem();
            mc.playerController.attackEntity(mc.thePlayer, over.entityHit);
        } else {
            mc.thePlayer.swingItem();
            if (over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                mc.playerController.onPlayerDamageBlock(over.getBlockPos(), over.sideHit);
            }
        }
    }

    private void clickEntityOnly() {
        MovingObjectPosition over = mc.objectMouseOver;
        if (over == null) return;
        if (over.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY) return;
        Entity target = over.entityHit;
        if (target == null) return;
        mc.thePlayer.swingItem();
        mc.playerController.attackEntity(mc.thePlayer, target);
    }

    private void reset() {
        wasDown = false;
        pressTime = 0;
        lastAttackTick = -1;
        clickBudget = 1.0;
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