package com.kalca.ragejava.module;

import java.util.ArrayList;
import java.util.List;

public class ModuleManager {

    private final List<Module> modules = new ArrayList<>();

    public ModuleManager() {
        modules.add(new Speed());
        modules.add(new AutoSprint());
        modules.add(new Internet());
        modules.add(new Hitboxes());
        modules.add(new LeftClicker());
        modules.add(new AimAssist());
        modules.add(new Backtrack());
        modules.add(new RightClicker());
        modules.add(new Velocity());
        modules.add(new Breaker());
        modules.add(new Esp());
        modules.add(new Fullbright());
        modules.add(new NoHurtCam());
        modules.add(new Interface());
        modules.add(new NameHider());
        modules.add(new Jetpack());
    }

    public List<Module> getModules() {
        return modules;
    }

    public List<Module> getModulesInCategory(Category category) {
        List<Module> result = new ArrayList<>();
        for (Module module : modules) {
            if (module.getCategory() == category) result.add(module);
        }
        return result;
    }

    public Module getModule(String name) {
        for (Module module : modules) {
            if (module.getName().equalsIgnoreCase(name)) return module;
        }
        return null;
    }

    public List<Module> getEnabledModules() {
        List<Module> result = new ArrayList<>();
        for (Module module : modules) {
            if (module.isEnabled()) result.add(module);
        }
        return result;
    }
}