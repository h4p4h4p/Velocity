package com.kalca.ragejava;

import com.kalca.ragejava.command.CommandManager;
import com.kalca.ragejava.config.ConfigManager;
import com.kalca.ragejava.gui.ClickGUI;
import com.kalca.ragejava.gui.Theme;
import com.kalca.ragejava.module.Interface;
import com.kalca.ragejava.module.Module;
import com.kalca.ragejava.module.ModuleManager;
import com.kalca.ragejava.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod(modid = RageJava.MODID, name = RageJava.NAME, version = RageJava.VERSION)
public class RageJava {

    public static final String MODID = "velocity";
    public static final String NAME = "Velocity";
    public static final String VERSION = "0.3";

    public static RageJava INSTANCE;

    public ModuleManager moduleManager;
    public ConfigManager configManager;
    public ClickGUI clickGui;
    public CommandManager commandManager;

    public final KeyBinding clickGuiKey = new KeyBinding("ClickGUI", Keyboard.KEY_K, "Velocity");

    private final Minecraft mc = Minecraft.getMinecraft();
    private final boolean[] moduleKeyStates = new boolean[256];

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        INSTANCE = this;
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        net.minecraftforge.fml.client.registry.ClientRegistry.registerKeyBinding(clickGuiKey);
        MinecraftForge.EVENT_BUS.register(this);
        moduleManager = new ModuleManager();
        clickGui = new ClickGUI();
        commandManager = new CommandManager();
        configManager = new ConfigManager();
        configManager.load(moduleManager);
    }

    public static void scheduleSave() {
        if (INSTANCE != null) INSTANCE.saveConfig();
    }

    public void saveConfig() {
        if (configManager != null && moduleManager != null) {
            configManager.save(moduleManager);
        }
    }

    public void closeGui() {
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        saveConfig();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (clickGuiKey.isPressed()) {
            if (mc.currentScreen != null && mc.currentScreen instanceof ClickGUI) {
                mc.displayGuiScreen(null);
            } else if (mc.currentScreen == null) {
                mc.displayGuiScreen(clickGui);
            }
        }
        updateModuleKeys();
    }

    private void updateModuleKeys() {
        boolean guiOpen = mc.currentScreen != null;
        for (Module module : moduleManager.getModules()) {
            int key = module.getKey();
            if (key == -1 || key < 0 || key >= moduleKeyStates.length) continue;
            boolean down = Keyboard.isKeyDown(key);
            if (!guiOpen && down && !moduleKeyStates[key]) {
                module.toggle();
            }
            moduleKeyStates[key] = down;
        }
    }

    @SubscribeEvent
    public void onRender(RenderGameOverlayEvent event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;

        Module iface = moduleManager.getModule("Interface");
        if (iface == null || !iface.isEnabled()) return;
        Interface hud = (Interface) iface;

        if (hud.shouldShowWatermark()) {
            String playerName = mc.thePlayer != null ? mc.thePlayer.getName() : mc.getSession().getUsername();
            String prefix = "[";
            String suffix = " v" + VERSION + " - " + playerName + "]";
            int x = 2;
            RenderUtil.drawString(prefix, x, 2, 0xFFFFFFFF);
            x += RenderUtil.getTextWidth(prefix);
            RenderUtil.drawString(NAME, x, 2, Theme.ACCENT);
            x += RenderUtil.getTextWidth(NAME);
            RenderUtil.drawString(suffix, x, 2, 0xFFFFFFFF);
        }

        if (!hud.shouldShowModuleList()) return;

        ScaledResolution sr = new ScaledResolution(mc);
        int width = sr.getScaledWidth();

        List<Module> visible = new ArrayList<>(moduleManager.getEnabledModules());
        Map<Module, String> suffixes = new HashMap<>();
        for (Module module : visible) {
            String tag = module.getTag();
            suffixes.put(module, tag == null || tag.isEmpty() ? "" : " " + tag);
        }
        // Longest rows first so the right-aligned list has a clean left edge.
        visible.sort((a, b) -> {
            int widthA = RenderUtil.getTextWidth(a.getName() + suffixes.get(a));
            int widthB = RenderUtil.getTextWidth(b.getName() + suffixes.get(b));
            if (widthA != widthB) return Integer.compare(widthB, widthA);
            return a.getName().compareToIgnoreCase(b.getName());
        });

        int y = 2;
        for (Module module : visible) {
            String suffix = suffixes.get(module);
            int nameWidth = RenderUtil.getTextWidth(module.getName());
            float x = width - 2 - nameWidth - RenderUtil.getTextWidth(suffix);
            RenderUtil.drawString(module.getName(), x, y, Theme.ACCENT);
            if (!suffix.isEmpty()) {
                RenderUtil.drawString(suffix, x + nameWidth, y, Theme.TEXT_GRAY);
            }
            y += RenderUtil.getTextHeight() + 1;
        }
    }
}