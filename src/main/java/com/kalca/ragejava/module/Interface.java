package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.settings.SliderSetting;

public class Interface extends Module {

    private final BooleanSetting watermarkSetting = new BooleanSetting("Watermark", true);
    private final BooleanSetting moduleListSetting = new BooleanSetting("Module List", true);
    private final SliderSetting bgDimSetting = new SliderSetting("BG Dim", 60, 0, 100, 5);

    public Interface() {
        super("Interface", Category.INTERFACE);
        settings.add(watermarkSetting);
        settings.add(moduleListSetting);
        settings.add(bgDimSetting);
    }

    public boolean shouldShowWatermark() {
        return watermarkSetting.getValue();
    }

    public boolean shouldShowModuleList() {
        return moduleListSetting.getValue();
    }

    public int getBgDimAlpha() {
        return (int) (bgDimSetting.getValue() / 100.0 * 255);
    }

    @Override
    public boolean canBind() {
        return false;
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
    }
}