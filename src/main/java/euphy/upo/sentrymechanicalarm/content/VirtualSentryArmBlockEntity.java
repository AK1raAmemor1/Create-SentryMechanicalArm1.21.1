package euphy.upo.sentrymechanicalarm.content;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Optional;

public class VirtualSentryArmBlockEntity extends SentryArmBlockEntity {
    private BlockPos virtualPos;
    private float contraptionSpeed;

    public VirtualSentryArmBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
        this.virtualPos = pos;
    }

    public void setVirtualLevel(Level level) {
        this.level = level;
    }

    public void setVirtualPos(BlockPos pos) {
        this.virtualPos = pos;
    }

    public void setContraptionSpeed(float speed) {
        this.contraptionSpeed = speed;
    }

    public float getContraptionSpeed() {
        return contraptionSpeed;
    }

    @Override
    public BlockPos getBlockPos() {
        return this.virtualPos;
    }

    @Override
    public void tick() {
    }

    @Override
    public BlockState getBlockState() {
        return super.getBlockState();
    }

    public static VirtualSentryArmBlockEntity fromData(BlockPos pos, BlockState state, CompoundTag data, Level level) {
        VirtualSentryArmBlockEntity be = new VirtualSentryArmBlockEntity(pos, state);
        be.setVirtualLevel(level);
        if (data != null) {
            if (data.contains("Angles")) {
                CompoundTag angles = data.getCompound("Angles");
                be.baseAngle.setValue(angles.getFloat("Base"));
                be.lowerArmAngle.setValue(angles.getFloat("Lower"));
                be.upperArmAngle.setValue(angles.getFloat("Upper"));
                be.headAngle.setValue(angles.getFloat("Head"));
            }
            if (data.contains("Speed")) {
                be.setContraptionSpeed(Math.abs(data.getFloat("Speed")));
            }
            if (data.contains("SentryHeldItem")) {
                be.setHeldItem(ItemStack.parseOptional(level.registryAccess(), data.getCompound("SentryHeldItem")));
            }
            if (data.contains("SentryAmmoBoxes")) {
                net.minecraft.world.ContainerHelper.loadAllItems(data.getCompound("SentryAmmoBoxes"), be.attachedAmmoBoxes, level.registryAccess());
            }
            if (data.contains("color")) {
                int colorId = data.getInt("color");
                be.color = Optional.of(DyeColor.byId(colorId));
            }
        }
        return be;
    }
}