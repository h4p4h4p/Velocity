package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.ModeSetting;
import com.kalca.ragejava.settings.Setting;
import com.kalca.ragejava.settings.SliderSetting;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public class Fullbright extends Module {

    public static final String MODE_GAMMA = "Gamma";
    public static final String MODE_LIGHT = "Light";

    private final ModeSetting modeSetting = new ModeSetting("Mode", new String[]{MODE_GAMMA, MODE_LIGHT}, ModeSetting.UNSET);
    private final SliderSetting gammaSetting = new SliderSetting("Gamma", 100, 0.5, 100, 0.5);

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Map<Block, Integer> savedLight = new HashMap<>();

    private float oldGamma;
    private Field lightField;
    private boolean lightApplied;

    public Fullbright() {
        super("Fullbright", Category.RENDER);
        settings.add(modeSetting);
        settings.add(gammaSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (MODE_GAMMA.equals(mode())) {
            mc.gameSettings.gammaSetting = (float) gammaSetting.getValue();
        }
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == gammaSetting) return MODE_GAMMA.equals(mode());
        return true;
    }

    private String mode() {
        return modeSetting.getValue();
    }

    @Override
    public void onEnable() {
        if (MODE_GAMMA.equals(mode())) {
            oldGamma = mc.gameSettings.gammaSetting;
            mc.gameSettings.gammaSetting = (float) gammaSetting.getValue();
        } else {
            applyLight();
        }
    }

    @Override
    public void onDisable() {
        if (MODE_GAMMA.equals(mode())) {
            mc.gameSettings.gammaSetting = oldGamma;
        } else {
            restoreLight();
        }
    }

    private void applyLight() {
        if (lightApplied) return;
        try {
            if (lightField == null) {
                lightField = Block.class.getDeclaredField("lightValue");
                lightField.setAccessible(true);
            }
            for (Object obj : Block.blockRegistry) {
                if (!(obj instanceof Block)) continue;
                Block block = (Block) obj;
                if (block == null) continue;
                if (!savedLight.containsKey(block)) {
                    savedLight.put(block, (Integer) lightField.get(block));
                }
                lightField.set(block, 15);
            }
            lightApplied = true;
        } catch (Exception ignored) {
        }
    }

    private void restoreLight() {
        if (!lightApplied) return;
        try {
            if (lightField == null) {
                lightField = Block.class.getDeclaredField("lightValue");
                lightField.setAccessible(true);
            }
            for (Map.Entry<Block, Integer> entry : savedLight.entrySet()) {
                lightField.set(entry.getKey(), entry.getValue());
            }
            savedLight.clear();
        } catch (Exception ignored) {
        }
        lightApplied = false;
    }

    @Override
    public String getTag() {
        return modeSetting.getValue();
    }
}