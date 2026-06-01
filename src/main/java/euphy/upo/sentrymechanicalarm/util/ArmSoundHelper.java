package euphy.upo.sentrymechanicalarm.util;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.sound.SoundPlayManager;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.sound.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Optional;

@OnlyIn(Dist.CLIENT)
public class ArmSoundHelper {

    private static volatile ArmorStand cachedDummyEntity;
    private static volatile Level cachedLevel;

    private static ArmorStand getOrCreateDummyEntity(Level level, Vec3 pos) {
        ArmorStand entity = cachedDummyEntity;
        if (entity == null || entity.isRemoved() || cachedLevel != level) {
            synchronized (ArmSoundHelper.class) {
                entity = cachedDummyEntity;
                if (entity == null || entity.isRemoved() || cachedLevel != level) {
                    entity = new ArmorStand(level, pos.x, pos.y, pos.z);
                    entity.setInvisible(true);
                    cachedDummyEntity = entity;
                    cachedLevel = level;
                    return entity;
                }
            }
        }
        entity.setPos(pos.x, pos.y, pos.z);
        return entity;
    }

    public static boolean isSilenced(ItemStack stack) {
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun == null) {
            return false;
        }
        ResourceLocation muzzleId = iGun.getAttachmentId(stack, AttachmentType.MUZZLE);

        return TimelessAPI.getCommonAttachmentIndex(muzzleId)
                .map(index -> index.getData())
                .map(data -> data.getModifier())
                .map(modifier -> modifier.containsKey("silence"))
                .orElse(false);
    }

    public static void playFireEffects(Level level, Vec3 pos, ItemStack stack, GunData gunData) {
        Optional<GunDisplayInstance> displayOpt = TimelessAPI.getGunDisplay(stack);
        if (displayOpt.isEmpty()) {
            return;
        }
        GunDisplayInstance display = displayOpt.get();
        ArmorStand dummyEntity = getOrCreateDummyEntity(level, pos);

        if (isSilenced(stack)) {
            SoundPlayManager.playSilenceSound(dummyEntity, display, gunData);
        } else {
            SoundPlayManager.playShootSound(dummyEntity, display, gunData);
        }
    }

    public static void playChargeSound(Level level, Vec3 pos, ItemStack stack, GunDisplayInstance display) {
        ArmorStand dummyEntity = getOrCreateDummyEntity(level, pos);

        ResourceLocation soundId = display.getSounds("charge");
        if (soundId == null) soundId = display.getSounds("warmup");
        if (soundId == null) soundId = display.getSounds("build");
        if (soundId == null) soundId = display.getSounds(SoundManager.BOLT_SOUND);

        if (soundId != null) {
            SoundPlayManager.playClientSound(dummyEntity, soundId, 1.0f, 1.0f, 32);
        }
    }
}
