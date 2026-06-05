package euphy.upo.sentrymechanicalarm.network;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.tacz.guns.api.client.animation.statemachine.LuaAnimationStateMachine;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.animation.statemachine.GunAnimationStateContext;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.pojo.display.gun.GunDisplay;
import euphy.upo.sentrymechanicalarm.content.SentryArmBlock;
import euphy.upo.sentrymechanicalarm.content.SentryArmBlockEntity;
import euphy.upo.sentrymechanicalarm.content.VirtualSentryArmBlockEntity;
import euphy.upo.sentrymechanicalarm.mixin.GunDisplayInstanceAccessor;
import euphy.upo.sentrymechanicalarm.util.ArmSoundHelper;
import euphy.upo.sentrymechanicalarm.util.SentryFakePlayer;
import euphy.upo.sentrymechanicalarm.util.SentryTrailManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ClientPacketHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<ResourceLocation, GunDisplayInstance> SENTRY_DISPLAYS = new HashMap<>();

    private static Vec3 calculateExactMuzzle(SentryArmBlockEntity sentry, ItemStack gunStack) {
        Optional<GunDisplayInstance> displayOpt = TimelessAPI.getGunDisplay(gunStack);
        if (displayOpt.isEmpty()) return null;
        GunDisplayInstance display = displayOpt.get();
        BedrockGunModel gunModel = display.getGunModel();
        if (gunModel == null) return null;

        List<BedrockPart> handPath = gunModel.getThirdPersonHandOriginPath();
        List<BedrockPart> muzzlePath = gunModel.getMuzzleFlashPosPath();
        if (muzzlePath == null || muzzlePath.isEmpty()) return null;

        boolean isCeiling = sentry.getBlockState().hasProperty(SentryArmBlock.CEILING) && sentry.getBlockState().getValue(SentryArmBlock.CEILING);
        BlockPos pos = sentry.getBlockPos();
        float baseAngle = sentry.baseAngle.getValue();
        float lowerArmAngle = sentry.lowerArmAngle.getValue() - 135.0F;
        float upperArmAngle = sentry.upperArmAngle.getValue() - 90.0F;
        float headAngle = sentry.headAngle.getValue();

        String gunPath = gunStack.getItem() instanceof IGun iGun2 ? iGun2.getGunId(gunStack).getPath().toLowerCase() : "";

        PoseStack ps = new PoseStack();
        Matrix4f worldMatrix = new Matrix4f();
        worldMatrix.translate((float) pos.getX() + 0.5f, (float) pos.getY() + 0.5f, (float) pos.getZ() + 0.5f);

        ps.last().pose().set(worldMatrix);
        ps.translate(0, 0.25f, 0);
        ps.mulPose(Axis.YP.rotationDegrees(baseAngle));
        ps.translate(0, 0.125f, 0);
        ps.mulPose(Axis.XP.rotationDegrees(lowerArmAngle + 135));
        ps.translate(0, 0, -0.875f);
        ps.mulPose(Axis.XP.rotationDegrees(upperArmAngle - 90));
        ps.translate(0, 0, -0.9375f);
        ps.mulPose(Axis.XP.rotationDegrees(headAngle - 45));
        if (isCeiling) ps.mulPose(Axis.ZP.rotationDegrees(180));
        ps.translate(0, 0, -0.375f);

        ps.mulPose(Axis.XP.rotationDegrees(90.0F));
        ps.translate(0, -0.625f, 0);
        ps.mulPose(Axis.XP.rotationDegrees(-90));
        ps.translate(0, 0.18f, 0);

        if (gunPath.contains("minigun")) {
            ps.mulPose(Axis.XP.rotationDegrees(-90));
            ps.translate(0, -0.7f, 0.2f);
        }

        float armScale = 1.5f;
        ps.scale(armScale, armScale, armScale);
        ps.translate(0, 1.5f, 0);
        ps.scale(-1.0f, -1.0f, 1.0f);

        Vector3f displayScale = new Vector3f(1.0F, 1.0F, 1.0F);
        if (display.getTransform() != null && display.getTransform().getScale() != null) {
            Vector3f ts = display.getTransform().getScale().getThirdPerson();
            if (ts != null) displayScale = ts;
        }

        if (handPath != null && !handPath.isEmpty()) {
            ps.translate(0.0F, 1.5F, 0.0F);
            for (int i = handPath.size() - 1; i >= 0; --i) {
                BedrockPart t = handPath.get(i);
                ps.mulPose(Axis.XN.rotation(t.xRot));
                ps.mulPose(Axis.YN.rotation(t.yRot));
                ps.mulPose(Axis.ZN.rotation(t.zRot));
                if (t.getParent() != null) {
                    ps.translate(-t.x * displayScale.x() / 16.0F, -t.y * displayScale.y() / 16.0F, -t.z * displayScale.z() / 16.0F);
                } else {
                    ps.translate(-t.x * displayScale.x() / 16.0F, (1.5F - t.y / 16.0F) * displayScale.y(), -t.z * displayScale.z() / 16.0F);
                }
            }
            ps.translate(0.0F, -1.5F, 0.0F);
        }

        ps.translate(0.0F, 1.5F, 0.0F);
        ps.scale(displayScale.x(), displayScale.y(), displayScale.z());
        ps.translate(0.0F, -1.5F, 0.0F);

        for (BedrockPart part : muzzlePath) {
            part.translateAndRotateAndScale(ps);
        }

        Vector4f result = new Vector4f(0, 0, 0, 1);
        ps.last().pose().transform(result);
        return new Vec3(result.x(), result.y(), result.z());
    }

    private static void triggerSentryAnimation(ItemStack gunStack, String input) {
        IGun iGun = IGun.getIGunOrNull(gunStack);
        if (iGun == null) return;
        ResourceLocation gunId = iGun.getGunId(gunStack);
        if (gunId == null) return;

        GunDisplayInstance sentryDisplay = SENTRY_DISPLAYS.computeIfAbsent(gunId, id -> {
            Optional<GunDisplayInstance> original = TimelessAPI.getGunDisplay(gunStack);
            if (original.isEmpty()) return null;
            GunDisplay display = ((GunDisplayInstanceAccessor) (Object) original.get()).sentrymechanicalarm$getDisplay();
            if (display == null) return null;
            return GunDisplayInstance.create(id, display);
        });

        if (sentryDisplay != null) {
            LuaAnimationStateMachine<GunAnimationStateContext> sm = sentryDisplay.getAnimationStateMachine();
            if (sm != null) sm.trigger(input);
        }
    }

    public static void handleSentryShoot(SentryShootPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        BlockEntity be = mc.level.getBlockEntity(msg.pos());
        if (!(be instanceof SentryArmBlockEntity sentry)) return;

        sentry.updateAmmoFromPacket(msg.slotIndex(), msg.itemTag());
        ItemStack gunStack = sentry.getHeldItem();

        if (gunStack.isEmpty() || !(gunStack.getItem() instanceof IGun iGun)) return;

        Level level = mc.level;
        Vec3 center = sentry.getBlockPos().getCenter();

        Optional<GunDisplayInstance> displayOpt = TimelessAPI.getGunDisplay(gunStack);
        if (displayOpt.isEmpty()) return;
        GunDisplayInstance display = displayOpt.get();

        switch (msg.actionType()) {
            case CHARGE -> {
                triggerSentryAnimation(gunStack, "bolt");
                ArmSoundHelper.playChargeSound(level, center, gunStack, display);
            }
            case BOLT -> ArmSoundHelper.playBoltSound(level, center, display);
            case RELOAD_EMPTY -> ArmSoundHelper.playReloadSound(level, center, display, true);
            case RELOAD_TACTICAL -> ArmSoundHelper.playReloadSound(level, center, display, false);
            case SHOOT -> {
                sentry.triggerShootEffects();
                triggerSentryAnimation(gunStack, "shoot");
                TimelessAPI.getCommonGunIndex(iGun.getGunId(gunStack)).ifPresent(index ->
                    ArmSoundHelper.playFireEffects(level, center, gunStack, index.getGunData())
                );

                Vec3 modelMuzzle = calculateExactMuzzle(sentry, gunStack);
                Vec3 realStart = modelMuzzle != null ? modelMuzzle : msg.realStart();

                if (!SentryFakePlayer.hasEntityBullet(gunStack)) {
                    Vec3 direction = msg.realEnd().subtract(msg.realStart()).normalize();
                    double totalDistance = realStart.distanceTo(msg.realEnd());
                    double offsetDistance = 0.8;
                    Vec3 adjustedStart = totalDistance > offsetDistance
                            ? realStart.add(direction.scale(offsetDistance))
                            : realStart;
                    double adjustedDist = totalDistance > offsetDistance
                            ? totalDistance - offsetDistance
                            : totalDistance;

                    SentryTrailManager.addTracer(adjustedStart, direction, 8.0, 2.0, adjustedDist);
                }
            }
        }
    }

    public static void handleSentryContraptionShoot(SentryContraptionShootPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return;

        Entity entity = level.getEntity(msg.contraptionId());
        if (entity instanceof AbstractContraptionEntity ace) {
            Contraption contraption = ace.getContraption();
            if (contraption != null) {
                for (var actor : contraption.getActors()) {
                    if (actor.getKey().pos().equals(msg.localPos())) {
                        MovementContext context = actor.getValue();
                        SentryArmBlockEntity be = null;
                        LOGGER.info("[ContraptionShellPacket] processing pos=({}) tempData={} blockData={}", context.localPos, context.temporaryData != null ? context.temporaryData.getClass().getSimpleName() : "null", context.blockEntityData != null ? "present" : "null");
                        if (context.temporaryData instanceof SentryArmBlockEntity s) {
                            be = s;
                        } else if (context.blockEntityData != null) {
                            VirtualSentryArmBlockEntity vbe = VirtualSentryArmBlockEntity.fromData(
                                    context.localPos, context.state, context.blockEntityData, context.world);
                            context.temporaryData = vbe;
                            be = vbe;
                        }
                        if (be != null) {
                            be.setLastShootTime(System.currentTimeMillis());
                            be.triggerShootEffects();
                        }
                        break;
                    }
                }
            }
        }

        if (!SentryFakePlayer.hasEntityBullet(msg.gunStack())) {
            Vec3 direction = msg.realEnd().subtract(msg.realStart()).normalize();
            double totalDistance = msg.realStart().distanceTo(msg.realEnd());
            double offsetDistance = 0.6;
            Vec3 adjustedStart = totalDistance > offsetDistance
                    ? msg.realStart().add(direction.scale(offsetDistance))
                    : msg.realStart();
            double adjustedDist = totalDistance > offsetDistance
                    ? totalDistance - offsetDistance
                    : totalDistance;
            SentryTrailManager.addTracer(adjustedStart, direction, 8.0, 2.0, adjustedDist);
        }

        if (msg.gunStack().getItem() instanceof IGun iGun) {
            triggerSentryAnimation(msg.gunStack(), "shoot");
            TimelessAPI.getCommonGunIndex(iGun.getGunId(msg.gunStack())).ifPresent(index ->
                ArmSoundHelper.playFireEffects(level, msg.realStart(), msg.gunStack(), index.getGunData())
            );
        }
    }
}
