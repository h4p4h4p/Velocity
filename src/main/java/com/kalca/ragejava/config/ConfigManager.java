package com.kalca.ragejava.config;

import com.kalca.ragejava.command.CommandManager;
import com.kalca.ragejava.gui.Theme;
import com.kalca.ragejava.module.Module;
import com.kalca.ragejava.module.ModuleManager;
import com.kalca.ragejava.settings.Setting;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private final File file;

    public ConfigManager() {
        this.file = new File(Minecraft.getMinecraft().mcDataDir, "config/ragejava.cfg");
    }

    public void save(ModuleManager modules) {
        List<String> lines = new ArrayList<>();
        lines.add("ragejava.accent=" + String.format("%06X", Theme.ACCENT & 0xFFFFFF));
        lines.add("ragejava.rainbow=" + CommandManager.rainbow);
        for (Module module : modules.getModules()) {
            lines.add(module.getName() + ".enabled=" + module.isEnabled());
            lines.add(module.getName() + ".key=" + module.getKey());
            for (Setting setting : module.getSettings()) {
                lines.add(module.getName() + "." + setting.getName() + "=" + setting.toConfigString());
            }
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Files.write(file.toPath(), lines, Charset.forName("UTF-8"));
        } catch (IOException ignored) {
        }
    }

    public void load(ModuleManager modules) {
        if (!file.exists()) return;
        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file.toPath(), Charset.forName("UTF-8"))) {
                int idx = line.indexOf('=');
                if (idx < 0) continue;
                values.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        } catch (IOException e) {
            return;
        }

        String accent = values.get("ragejava.accent");
        if (accent != null) {
            try {
                Theme.ACCENT = 0xFF000000 | (Integer.parseInt(accent, 16) & 0xFFFFFF);
            } catch (NumberFormatException ignored) {
            }
        }
        String rainbow = values.get("ragejava.rainbow");
        if (rainbow != null) {
            CommandManager.rainbow = rainbow.equalsIgnoreCase("true");
        }

        // Sprint was renamed to AutoSprint; carry over its enabled state and key bind.
        if (!values.containsKey("AutoSprint.enabled") && values.containsKey("Sprint.enabled")) {
            values.put("AutoSprint.enabled", values.get("Sprint.enabled"));
            if (values.containsKey("Sprint.key")) {
                values.put("AutoSprint.key", values.get("Sprint.key"));
            }
        }

        for (Module module : modules.getModules()) {
            String enabled = values.get(module.getName() + ".enabled");
            if (enabled != null) module.setEnabled(enabled.equalsIgnoreCase("true"));
            String key = values.get(module.getName() + ".key");
            if (key != null) {
                try {
                    module.setKey(Integer.parseInt(key));
                } catch (NumberFormatException ignored) {
                }
            }
            for (Setting setting : module.getSettings()) {
                String value = values.get(module.getName() + "." + setting.getName());
                if (value != null) setting.fromConfigString(value);
            }
        }
    }
}