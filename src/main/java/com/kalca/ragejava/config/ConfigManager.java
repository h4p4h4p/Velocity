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

    private static final String CONFIG_VERSION_KEY = "ragejava.configVersion";
    private static final String CONFIG_VERSION = "1";

    private final File file;

    public ConfigManager() {
        this.file = new File(Minecraft.getMinecraft().mcDataDir, "config/ragejava.cfg");
    }

    public void save(ModuleManager modules) {
        List<String> lines = new ArrayList<>();
        lines.add(CONFIG_VERSION_KEY + "=" + CONFIG_VERSION);
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

    /** Converts the old 0..100 Velocity sliders to the current -1..1 multipliers. */
    private static void migrateVelocityScale(Map<String, String> values, String key) {
        String raw = values.get(key);
        if (raw == null) return;
        try {
            double old = Double.parseDouble(raw);
            if (Math.abs(old) <= 1.0D) return;
            double scaled = old / 100.0D;
            double snapped = Math.round(scaled * 10.0D) / 10.0D;
            values.put(key, Double.toString(Math.max(-1.0D, Math.min(1.0D, snapped))));
        } catch (NumberFormatException ignored) {
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

        // Old configs predate the version key and the Reversal mode, so mode 2 is the removed
        // Simulations entry. With the key present, mode 2 is Reversal and must be kept.
        boolean legacyConfig = !values.containsKey(CONFIG_VERSION_KEY);

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

        // BedNuker was renamed to Breaker; carry over its enable state, bind and settings.
        // Its Mode and Speed settings are gone with the Silent mode, so they are not copied.
        if (!values.containsKey("Breaker.enabled") && values.containsKey("BedNuker.enabled")) {
            for (Map.Entry<String, String> entry : new ArrayList<>(values.entrySet())) {
                String key = entry.getKey();
                if (!key.startsWith("BedNuker.")) continue;
                String name = key.substring("BedNuker.".length());
                if (name.equals("Mode") || name.equals("Speed")) continue;
                values.put("Breaker." + name, entry.getValue());
            }
        }

        // Velocity's knockback multipliers used to be stored as 0..100 percentages.
        migrateVelocityScale(values, "Velocity.Horizontal");
        migrateVelocityScale(values, "Velocity.Vertical");
        // Simulations was removed from Velocity; use Modify rather than clamping to Lag.
        if (legacyConfig && "2".equals(values.get("Velocity.Mode"))) {
            values.put("Velocity.Mode", "0");
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