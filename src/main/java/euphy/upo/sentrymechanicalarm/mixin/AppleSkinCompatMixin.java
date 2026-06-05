package euphy.upo.sentrymechanicalarm.mixin;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// DO NOT DELETE: Required for AppleSkin compatibility.
// Prevents AppleSkin from sending sync packets to FakePlayers (sentry arm gunners),
// which have null/invalid connections and would otherwise cause log spam or crashes.
// The target method name may vary across AppleSkin versions; require=0 makes this
// mixin optional so it silently skips when unsupported.
@Mixin(targets = "squeek.appleskin.network.SyncHandler")
public class AppleSkinCompatMixin {

    @Inject(method = "sendOptionalPayloadToPlayer", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void sentrymechanicalarm$onSendOptionalPayloadToPlayer(ServerPlayer player, CustomPacketPayload payload, CallbackInfo ci) {
        if (player.connection == null || player.connection.getConnection() == null || player.connection.getConnection().channel() == null) {
            ci.cancel();
        }
    }
}
