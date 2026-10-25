package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.settings.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * Holds sneak while the player walks toward a drop, the way a legit player would
 * edge-guard while bridging. The sneak key state is driven instead of raw packets so
 * vanilla handles the slowdown, the camera height and the client-side C0B packets.
 */
public class LegitScaffold extends Module {

    private final SliderSetting distanceSetting = new SliderSetting("Distance", 0.3, 0.01, 0.30, 0.01);
    private final BooleanSetting whileSneakingSetting = new BooleanSetting("While Sneaking", false);
    private final BooleanSetting whileInAirSetting = new BooleanSetting("While In Air", false);

    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean autoSneaking;

    public LegitScaffold() {
        super("LegitScaffold", Category.MOVEMENT);
        settings.add(distanceSetting);
        settings.add(whileSneakingSetting);
        settings.add(whileInAirSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;

        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) {
            release();
            return;
        }

        // "While Sneaking" only assists a player who is already edge-guarding with shift.
        if (whileSneakingSetting.getValue() && !sneakKeyDown()) {
            release();
            return;
        }

        // "While In Air" carries an edge sneak through a jump instead of dropping it on takeoff.
        if (whileInAirSetting.getValue() && !mc.thePlayer.onGround && !mc.thePlayer.isInWater()
                && !mc.thePlayer.capabilities.isFlying && autoSneaking) {
            forceSneak();
            return;
        }

        if (atEdge()) forceSneak();
        else release();
    }

    /** True when the next bit of walking in the current input direction has no block under it. */
    private boolean atEdge() {
        if (!mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying || mc.thePlayer.isInWater()) return false;

        float forward = mc.thePlayer.movementInput.moveForward;
        float strafe = mc.thePlayer.movementInput.moveStrafe;
        if (forward == 0.0F && strafe == 0.0F) return false;

        float yaw = (float) Math.toRadians(mc.thePlayer.rotationYaw);
        double dirX = forward * -MathHelper.sin(yaw) + strafe * MathHelper.cos(yaw);
        double dirZ = forward * MathHelper.cos(yaw) + strafe * MathHelper.sin(yaw);
        double length = MathHelper.sqrt_double(dirX * dirX + dirZ * dirZ);
        if (length < 1.0E-4D) return false;

        double reach = distanceSetting.getValue() + 0.05D;
        double x = mc.thePlayer.posX + dirX / length * reach;
        double z = mc.thePlayer.posZ + dirZ / length * reach;
        double y = mc.thePlayer.posY - 0.05D;

        BlockPos ahead = new BlockPos(x, y, z);
        return isGap(ahead) && isGap(ahead.down());
    }

    private boolean isGap(BlockPos pos) {
        return mc.theWorld.getBlockState(pos).getBlock().getMaterial().isReplaceable();
    }

    private void forceSneak() {
        autoSneaking = true;
        // Re-assert every tick: a press/release of the real sneak key clears the state.
        if (!mc.gameSettings.keyBindSneak.isKeyDown()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), true);
        }
    }

    private void release() {
        if (!autoSneaking) return;
        autoSneaking = false;
        // Never release a key the player is physically holding.
        if (sneakKeyDown()) return;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
    }

    private boolean sneakKeyDown() {
        int code = mc.gameSettings.keyBindSneak.getKeyCode();
        return code > 0 && Keyboard.isKeyDown(code);
    }

    @Override
    public void onEnable() {
        autoSneaking = false;
    }

    @Override
    public void onDisable() {
        autoSneaking = false;
        if (!sneakKeyDown()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
        }
    }

    @Override
    public String getTag() {
        return String.format("%.2f", distanceSetting.getValue());
    }
}
