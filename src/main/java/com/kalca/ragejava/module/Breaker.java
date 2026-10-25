package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.BooleanSetting;
import com.kalca.ragejava.settings.Setting;
import com.kalca.ragejava.settings.SliderSetting;
import com.kalca.ragejava.util.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;

public class Breaker extends Module {

    private final SliderSetting rangeSetting = new SliderSetting("Range", 6.0, 1.0, 12.0, 0.5);
    private final SliderSetting delaySetting = new SliderSetting("Delay (ms)", 0, 0, 500, 10);
    private final BooleanSetting rotateSetting = new BooleanSetting("Rotate", true);
    private final BooleanSetting swingSetting = new BooleanSetting("Swing Hand", true);
    private final BooleanSetting autoSwitchSetting = new BooleanSetting("Auto Switch Tool", true);
    private final BooleanSetting onlySwordSetting = new BooleanSetting("Only With Sword", false);
    private final BooleanSetting bedsSetting = new BooleanSetting("Beds", true);
    private final BooleanSetting cakesSetting = new BooleanSetting("Cakes", false);
    private final BooleanSetting tntSetting = new BooleanSetting("TNT", false);

    private final Minecraft mc = Minecraft.getMinecraft();
    private long lastBreakTime = 0;
    private BlockPos currentTarget = null;
    private EnumFacing targetFace = null;

    public Breaker() {
        super("Breaker", Category.COMBAT);
        settings.add(rangeSetting);
        settings.add(delaySetting);
        settings.add(rotateSetting);
        settings.add(swingSetting);
        settings.add(autoSwitchSetting);
        settings.add(onlySwordSetting);
        settings.add(bedsSetting);
        settings.add(cakesSetting);
        settings.add(tntSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == delaySetting) return true;
        if (setting == autoSwitchSetting) return true;
        if (setting == onlySwordSetting) return true;
        return super.isSettingVisible(setting);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        if (System.currentTimeMillis() - lastBreakTime < delaySetting.getValue()) return;

        findAndBreakTarget();
    }

    private void findAndBreakTarget() {
        List<BlockPos> targets = new ArrayList<>();
        double range = rangeSetting.getValue();
        double rangeSq = range * range;

        int playerX = (int) Math.floor(mc.thePlayer.posX);
        int playerY = (int) Math.floor(mc.thePlayer.posY);
        int playerZ = (int) Math.floor(mc.thePlayer.posZ);
        int rangeInt = (int) Math.ceil(range);

        for (int x = -rangeInt; x <= rangeInt; x++) {
            for (int y = -rangeInt; y <= rangeInt; y++) {
                for (int z = -rangeInt; z <= rangeInt; z++) {
                    BlockPos pos = new BlockPos(playerX + x, playerY + y, playerZ + z);
                    double distSq = mc.thePlayer.getDistanceSq(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (distSq > rangeSq) continue;

                    Block block = mc.theWorld.getBlockState(pos).getBlock();
                    if (isTargetBlock(block)) {
                        targets.add(pos);
                    }
                }
            }
        }

        if (targets.isEmpty()) {
            currentTarget = null;
            targetFace = null;
            return;
        }

        // Sort by distance
        targets.sort((a, b) -> Double.compare(
                mc.thePlayer.getDistanceSq(a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5),
                mc.thePlayer.getDistanceSq(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5)
        ));

        for (BlockPos pos : targets) {
            EnumFacing face = getBestFace(pos);
            if (face != null) {
                currentTarget = pos;
                targetFace = face;
                breakBlock(pos, face);
                return;
            }
        }
    }

    private boolean isTargetBlock(Block block) {
        if (bedsSetting.getValue() && block instanceof BlockBed) return true;
        if (cakesSetting.getValue() && block == Blocks.cake) return true;
        if (tntSetting.getValue() && block == Blocks.tnt) return true;
        return false;
    }

    private EnumFacing getBestFace(BlockPos pos) {
        for (EnumFacing face : EnumFacing.values()) {
            BlockPos offset = pos.offset(face);
            if (mc.theWorld.getBlockState(offset).getBlock().getMaterial().isReplaceable()) {
                MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(
                        mc.thePlayer.getPositionEyes(1.0f),
                        new Vec3(offset.getX() + 0.5, offset.getY() + 0.5, offset.getZ() + 0.5)
                );
                if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && mop.getBlockPos().equals(pos)) {
                    return face;
                }
            }
        }
        return EnumFacing.UP;
    }

    private void breakBlock(BlockPos pos, EnumFacing face) {
        if (onlySwordSetting.getValue() && !isHoldingSword()) {
            if (autoSwitchSetting.getValue()) switchToSword();
            else return;
        } else if (autoSwitchSetting.getValue() && !isHoldingTool()) {
            switchToTool();
        }

        if (rotateSetting.getValue()) {
            Vec3 hitVec = new Vec3(pos.getX() + 0.5 + face.getDirectionVec().getX() * 0.5,
                    pos.getY() + 0.5 + face.getDirectionVec().getY() * 0.5,
                    pos.getZ() + 0.5 + face.getDirectionVec().getZ() * 0.5);
            float[] rotations = RotationUtil.getRotations(hitVec);
            mc.thePlayer.rotationYaw = rotations[0];
            mc.thePlayer.rotationPitch = rotations[1];
        }

        mc.playerController.onPlayerDamageBlock(pos, face);

        if (swingSetting.getValue()) {
            mc.thePlayer.swingItem();
        }

        lastBreakTime = System.currentTimeMillis();
    }

    private boolean isHoldingSword() {
        ItemStack stack = mc.thePlayer.getHeldItem();
        return stack != null && stack.getItem() instanceof net.minecraft.item.ItemSword;
    }

    private boolean isHoldingTool() {
        ItemStack stack = mc.thePlayer.getHeldItem();
        if (stack == null) return false;
        Item item = stack.getItem();
        return item instanceof net.minecraft.item.ItemPickaxe
                || item instanceof net.minecraft.item.ItemAxe
                || item instanceof net.minecraft.item.ItemSword
                || item instanceof net.minecraft.item.ItemSpade;
    }

    private void switchToSword() {
        int bestSlot = -1;
        int bestDamage = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (stack != null && stack.getItem() instanceof net.minecraft.item.ItemSword) {
                net.minecraft.item.ItemSword sword = (net.minecraft.item.ItemSword) stack.getItem();
                int damage = (int) sword.getDamageVsEntity();
                if (damage > bestDamage) {
                    bestDamage = damage;
                    bestSlot = i;
                }
            }
        }
        if (bestSlot != -1 && bestSlot != mc.thePlayer.inventory.currentItem) {
            mc.thePlayer.inventory.currentItem = bestSlot;
            mc.getNetHandler().addToSendQueue(new C09PacketHeldItemChange(bestSlot));
        }
    }

    private void switchToTool() {
        int bestSlot = -1;
        int bestSpeed = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (stack != null) {
                Item item = stack.getItem();
                if (item instanceof net.minecraft.item.ItemPickaxe
                        || item instanceof net.minecraft.item.ItemAxe
                        || item instanceof net.minecraft.item.ItemSpade) {
                    float speed = item.getStrVsBlock(stack, mc.theWorld.getBlockState(currentTarget).getBlock());
                    if (speed > bestSpeed) {
                        bestSpeed = (int) speed;
                        bestSlot = i;
                    }
                }
            }
        }
        if (bestSlot != -1 && bestSlot != mc.thePlayer.inventory.currentItem) {
            mc.thePlayer.inventory.currentItem = bestSlot;
            mc.getNetHandler().addToSendQueue(new C09PacketHeldItemChange(bestSlot));
        }
    }

    @Override
    public void onEnable() {
        lastBreakTime = 0;
        currentTarget = null;
        targetFace = null;
    }

    @Override
    public void onDisable() {
        currentTarget = null;
        targetFace = null;
    }

    @Override
    public String getTag() {
        if (currentTarget == null) return null;
        return currentTarget.getX() + ", " + currentTarget.getY() + ", " + currentTarget.getZ();
    }
}