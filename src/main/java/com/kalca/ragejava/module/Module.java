package com.kalca.ragejava.module;

import com.kalca.ragejava.RageJava;
import com.kalca.ragejava.settings.Setting;

import java.util.ArrayList;
import java.util.List;

public abstract class Module {

    private final String name;
    private final Category category;
    protected final List<Setting> settings = new ArrayList<>();
    private boolean enabled;
    private int key = -1;
    private boolean holdBind;
    private boolean bindDown;

    public Module(String name, Category category) {
        this.name = name;
        this.category = category;
    }

    public String getName() {
        return name;
    }

    public Category getCategory() {
        return category;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getKey() {
        return key;
    }

    public void setKey(int key) {
        this.key = key;
    }

    /** True when the bind should only stay enabled while the key is held. */
    public boolean isHoldBind() {
        return holdBind;
    }

    public void setHoldBind(boolean holdBind) {
        this.holdBind = holdBind;
    }

    /** Runtime state of the bound key for hold binds; never persisted. */
    public boolean isBindDown() {
        return bindDown;
    }

    public void setBindDown(boolean bindDown) {
        this.bindDown = bindDown;
    }

    public boolean canBind() {
        return true;
    }

    public boolean isSettingVisible(Setting setting) {
        return true;
    }

    /** Optional short status string shown on the module row, e.g. a live counter. */
    public String getTag() {
        return null;
    }

    public List<Setting> getSettings() {
        return settings;
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        if (enabled) onEnable();
        else onDisable();
        RageJava.scheduleSave();
    }

    public abstract void onEnable();

    public abstract void onDisable();
}