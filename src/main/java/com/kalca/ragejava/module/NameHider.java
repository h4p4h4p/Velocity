package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.ModeSetting;
import com.kalca.ragejava.settings.Setting;
import com.kalca.ragejava.settings.StringSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class NameHider extends Module {

    public static final String MODE_HIDE = "Hide";
    public static final String MODE_SPOOF = "Spoof";

    private final ModeSetting modeSetting = new ModeSetting("Mode", new String[]{MODE_HIDE, MODE_SPOOF}, 0);
    private final StringSetting customNameSetting = new StringSetting("Custom Name", "Player");

    private final Minecraft mc = Minecraft.getMinecraft();
    private String originalName = "";
    private boolean wasHiding = false;

    public NameHider() {
        super("NameHider", Category.RENDER);
        settings.add(modeSetting);
        settings.add(customNameSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == customNameSetting) return MODE_SPOOF.equals(modeSetting.getValue());
        return true;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (mc.thePlayer == null) return;

        if (!wasHiding) {
            originalName = mc.thePlayer.getName();
            wasHiding = true;
        }
    }

    @SubscribeEvent
    public void onRenderName(RenderLivingEvent.Specials.Pre event) {
        if (!isEnabled()) return;
        if (!(event.entity instanceof EntityPlayer)) return;

        EntityPlayer player = (EntityPlayer) event.entity;
        String original = player.getName();

        // Only hide self
        if (original.equals(originalName.isEmpty() ? mc.thePlayer.getName() : originalName)) {
            String hidden = getHiddenName();
            player.setCustomNameTag(hidden);
            player.setAlwaysRenderNameTag(true);
        }
    }

    @SubscribeEvent
    public void onRenderLivingPost(RenderLivingEvent.Specials.Post event) {
        if (!isEnabled()) return;
        if (!(event.entity instanceof EntityPlayer)) return;

        EntityPlayer player = (EntityPlayer) event.entity;
        String original = player.getName();

        if (original.equals(originalName.isEmpty() ? mc.thePlayer.getName() : originalName)) {
            player.setCustomNameTag(original);
            player.setAlwaysRenderNameTag(false);
        }
    }

    @SubscribeEvent
    public void onRenderTabList(RenderGameOverlayEvent event) {
        if (!isEnabled()) return;
        if (event.type != RenderGameOverlayEvent.ElementType.PLAYER_LIST) return;
        if (mc.thePlayer == null || mc.getNetHandler() == null) return;

        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (info == null) continue;
            String original = info.getGameProfile().getName();
            if (original.equals(originalName.isEmpty() ? mc.thePlayer.getName() : originalName)) {
                info.setDisplayName(new ChatComponentText(getHiddenName()));
            }
        }
    }

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        if (!isEnabled()) return;
        if (mc.thePlayer == null) return;

        String message = event.message.getUnformattedText();
        String originalSelfName = originalName.isEmpty() ? mc.thePlayer.getName() : originalName;

        if (message.contains(originalSelfName)) {
            String replacement = getHiddenName();
            String newMessage = message.replace(originalSelfName, replacement);
            event.message = new ChatComponentText(newMessage);
        }
    }

    private String getHiddenName() {
        String mode = modeSetting.getValue();

        if (MODE_HIDE.equals(mode)) {
            return "";
        } else if (MODE_SPOOF.equals(mode)) {
            return customNameSetting.getValue();
        }
        return originalName;
    }

    public String getOriginalName() {
        return originalName;
    }

    public boolean isHiding() {
        return wasHiding;
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
        wasHiding = false;
        // Restore original names on disable
        if (mc.theWorld != null) {
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (player != null) {
                    player.setCustomNameTag(player.getName());
                    player.setAlwaysRenderNameTag(false);
                }
            }
        }
    }

    @Override
    public String getTag() {
        return modeSetting.getValue();
    }
}