package euphy.upo.sentrymechanicalarm.client;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import euphy.upo.sentrymechanicalarm.SentryMechanicalArm;
import euphy.upo.sentrymechanicalarm.content.BlazeFireControlRenderer;
import euphy.upo.sentrymechanicalarm.content.SentryArmRenderer;
import euphy.upo.sentrymechanicalarm.ponder.SMAPonderPlugin;
import net.createmod.ponder.foundation.PonderIndex;
import euphy.upo.sentrymechanicalarm.registry.SentryPartialModels;
import euphy.upo.sentrymechanicalarm.registry.SentryRegistry;
import euphy.upo.sentrymechanicalarm.util.SentrySpriteShifts;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import org.slf4j.Logger;

import java.util.Map;

@EventBusSubscriber(modid = SentryMechanicalArm.MODID, value = Dist.CLIENT)
public class SentryMechanicalArmClient {
    private static final Logger LOGGER = SentryMechanicalArm.LOGGER;

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        SentryPartialModels.init();
        LOGGER.info("SentryPartialModels initialized");

        event.enqueueWork(() -> {
            SentrySpriteShifts.init();
            SMATooltips.init();
            PonderIndex.addPlugin(new SMAPonderPlugin());
            LOGGER.info("Client setup completed, BlockEntityType: {}", SentryRegistry.SENTRY_ARM_BE.get());
        });
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(SentryRegistry.SENTRY_ARM_BE.get(), SentryArmRenderer::new);
        event.registerBlockEntityRenderer(SentryRegistry.BLAZE_FIRE_CONTROL_BE.get(), BlazeFireControlRenderer::new);
        LOGGER.info("SentryArmRenderer registered for SENTRY_ARM_BE");
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(SentryRegistry.FIRE_CONTROL_MENU.get(), FireControlScreen::new);
    }

    private static final PartialModel[] ALL_PARTIALS = {
            SentryPartialModels.SENTRU_COG,
            SentryPartialModels.SENTRU_BASE,
            SentryPartialModels.ARM_LOWER_BODY,
            SentryPartialModels.ARM_UPPER_BODY,
            SentryPartialModels.ARM_CLAW_BASE,
            SentryPartialModels.ARM_CLAW_GRIP_UPPER,
            SentryPartialModels.ARM_CLAW_GRIP_LOWER,
            SentryPartialModels.BLAZE_FIRE_CONTROLLER_HEAD,
            SentryPartialModels.RING,
            SentryPartialModels.CLIPBOARD,
            SentryPartialModels.BASE,
    };

    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        for (PartialModel partial : ALL_PARTIALS) {
            event.register(ModelResourceLocation.standalone(partial.modelLocation()));
        }
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        BakedModel missingModel = models.get(ModelResourceLocation.standalone(ResourceLocation.withDefaultNamespace("builtin/missing")));
        for (PartialModel partial : ALL_PARTIALS) {
            ModelResourceLocation loc = ModelResourceLocation.standalone(partial.modelLocation());
            if (!models.containsKey(loc) || models.get(loc) == null) {
                LOGGER.warn("PartialModel {} was overridden/broken by a resource pack. Injecting fallback model.", partial.modelLocation());
                if (missingModel != null) {
                    models.put(loc, missingModel);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onModelBakingCompleted(ModelEvent.BakingCompleted event) {
        net.createmod.catnip.render.SuperByteBufferCache.getInstance().invalidate();
        for (PartialModel partial : ALL_PARTIALS) {
            if (partial.get() == null) {
                LOGGER.warn("PartialModel {} is still null after baking and fallback injection!", partial.modelLocation());
            }
        }
    }
}