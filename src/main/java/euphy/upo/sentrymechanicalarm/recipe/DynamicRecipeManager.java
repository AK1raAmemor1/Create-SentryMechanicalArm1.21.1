package euphy.upo.sentrymechanicalarm.recipe;

import com.google.common.collect.Multimap;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.press.PressingRecipe;
import com.simibubi.create.content.kinetics.saw.CuttingRecipe;
import com.simibubi.create.content.kinetics.deployer.DeployerApplicationRecipe;
import com.simibubi.create.content.processing.recipe.StandardProcessingRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipeBuilder;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.crafting.GunSmithTableIngredient;
import com.tacz.guns.crafting.GunSmithTableRecipe;
import euphy.upo.sentrymechanicalarm.SMAServerConfig;
import euphy.upo.sentrymechanicalarm.SentryMechanicalArm;
import euphy.upo.sentrymechanicalarm.registry.SentryRegistry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@EventBusSubscriber(modid = SentryMechanicalArm.MODID)
public class DynamicRecipeManager {

    private static Item taczAmmoItem;

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        RecipeManager recipeManager = event.getServer().getRecipeManager();

        taczAmmoItem = BuiltInRegistries.ITEM.get(
                ResourceLocation.fromNamespaceAndPath("tacz", "ammo"));
        if (taczAmmoItem == null) {
            SentryMechanicalArm.LOGGER.error("Failed to find TacZ ammo item, aborting recipe injection");
            return;
        }

        if (!SMAServerConfig.ENABLE_DYNAMIC_RECIPES.get()) {
            SentryMechanicalArm.LOGGER.info("Dynamic recipes disabled by config");
            return;
        }

        injectCuttingRecipes(recipeManager);
        injectSequencedAssemblyRecipes(recipeManager);
        injectPressingRecipes(recipeManager);
        verifyRecipes(recipeManager);
    }

    public static void injectCuttingRecipes(RecipeManager recipeManager) {
        var ammoEntries = TimelessAPI.getAllCommonAmmoIndex();
        if (ammoEntries.isEmpty()) return;

        List<RecipeHolder<?>> newRecipes = new ArrayList<>();
        for (var entry : ammoEntries) {
            ResourceLocation ammoId = entry.getKey();
            String path = ammoId.getPath().replace("/", "_");
            ResourceLocation recipeId = ResourceLocation.fromNamespaceAndPath(
                    SentryMechanicalArm.MODID, "ammo_cutting/" + path);

            ItemStack output = new ItemStack(SentryRegistry.UNFINISHED_AMMO.get());
            CompoundTag tag = new CompoundTag();
            tag.putString("AmmoId", ammoId.toString());
            tag.putInt("CopperSheets", 0);
            tag.putBoolean("GunpowderAdded", false);
            output.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

            CuttingRecipe recipe = new StandardProcessingRecipe.Builder<>(CuttingRecipe::new, recipeId)
                    .withItemIngredients(Ingredient.of(AllItems.COPPER_SHEET.get()))
                    .withSingleItemOutput(output)
                    .duration(50)
                    .build();

            newRecipes.add(new RecipeHolder<>(recipeId, recipe));
        }

        if (!newRecipes.isEmpty()) {
            injectRecipes(recipeManager, newRecipes);
        }
    }

    public static void injectSequencedAssemblyRecipes(RecipeManager recipeManager) {
        var ammoEntries = TimelessAPI.getAllCommonAmmoIndex();
        if (ammoEntries.isEmpty()) {
            SentryMechanicalArm.LOGGER.warn("No ammo entries found from TimelessAPI");
            return;
        }

        SentryMechanicalArm.LOGGER.info("Injecting {} sequenced assembly recipes", ammoEntries.size());

        List<RecipeHolder<?>> newRecipes = new ArrayList<>();
        for (var entry : ammoEntries) {
            ResourceLocation ammoId = entry.getKey();
            AmmoRecipeConfig.Config config = getOrCreateConfig(ammoId, recipeManager);
            String path = ammoId.getPath().replace("/", "_");
            ResourceLocation recipeId = ResourceLocation.fromNamespaceAndPath(
                    SentryMechanicalArm.MODID, "ammo_assembly/" + path);

            ItemStack inputUnfinished = new ItemStack(SentryRegistry.UNFINISHED_AMMO.get());
            CompoundTag inputTag = new CompoundTag();
            inputTag.putString("AmmoId", ammoId.toString());
            inputUnfinished.set(DataComponents.CUSTOM_DATA, CustomData.of(inputTag));

            ItemStack outputAmmo = new ItemStack(taczAmmoItem, config.outputCount());
            CompoundTag outputTag = new CompoundTag();
            outputTag.putString("AmmoId", ammoId.toString());
            outputAmmo.set(DataComponents.CUSTOM_DATA, CustomData.of(outputTag));

            SequencedAssemblyRecipeBuilder builder =
                    new SequencedAssemblyRecipeBuilder(recipeId);
            builder.require(Ingredient.of(inputUnfinished))
                    .transitionTo(SentryRegistry.UNFINISHED_AMMO.get())
                    .addOutput(outputAmmo, 1)
                    .loops(1);

            for (Item stepItem : config.assemblySteps()) {
                if (stepItem == Items.GUNPOWDER) {
                    builder.addStep(DeployerApplicationRecipe::new,
                            rb -> rb.require(Items.GUNPOWDER));
                } else {
                    builder.addStep(DeployerApplicationRecipe::new,
                            rb -> rb.require(stepItem));
                }
            }

            builder.addStep(PressingRecipe::new, rb -> rb);

            newRecipes.add(builder.build());
        }

        if (!newRecipes.isEmpty()) {
            injectRecipes(recipeManager, newRecipes);
        }
    }

    public static void injectPressingRecipes(RecipeManager recipeManager) {
        var ammoEntries = TimelessAPI.getAllCommonAmmoIndex();
        if (ammoEntries.isEmpty()) return;

        List<RecipeHolder<?>> newRecipes = new ArrayList<>();
        for (var entry : ammoEntries) {
            ResourceLocation ammoId = entry.getKey();
            AmmoRecipeConfig.Config config = getOrCreateConfig(ammoId, recipeManager);
            String path = ammoId.getPath().replace("/", "_");
            ResourceLocation pressRecipeId = ResourceLocation.fromNamespaceAndPath(
                    SentryMechanicalArm.MODID, "ammo_pressing/" + path);

            ItemStack inputComplete = new ItemStack(SentryRegistry.UNFINISHED_AMMO.get());
            CompoundTag inputTag = new CompoundTag();
            inputTag.putString("AmmoId", ammoId.toString());
            long materialSteps = config.assemblySteps().stream()
                    .filter(item -> item != Items.GUNPOWDER)
                    .count();
            inputTag.putInt("CopperSheets", (int) materialSteps);
            inputTag.putBoolean("GunpowderAdded", true);
            inputComplete.set(DataComponents.CUSTOM_DATA, CustomData.of(inputTag));

            ItemStack outputAmmo = new ItemStack(taczAmmoItem, config.outputCount());
            CompoundTag outputTag = new CompoundTag();
            outputTag.putString("AmmoId", ammoId.toString());
            outputAmmo.set(DataComponents.CUSTOM_DATA, CustomData.of(outputTag));

            PressingRecipe recipe = new StandardProcessingRecipe.Builder<>(PressingRecipe::new, pressRecipeId)
                    .withItemIngredients(Ingredient.of(inputComplete))
                    .withSingleItemOutput(outputAmmo)
                    .duration(100)
                    .build();

            newRecipes.add(new RecipeHolder<>(pressRecipeId, recipe));
        }

        if (!newRecipes.isEmpty()) {
            injectRecipes(recipeManager, newRecipes);
        }
    }

    private static AmmoRecipeConfig.Config getOrCreateConfig(ResourceLocation ammoId, RecipeManager recipeManager) {
        if (SMAServerConfig.AUTO_MATCH_FROM_TACZ.get()) {
            GunSmithTableRecipe recipe = findTaCZRecipe(ammoId, recipeManager);
            if (recipe != null) {
                AmmoRecipeConfig.Config fromRecipe = buildConfigFromRecipe(ammoId, recipe);
                if (fromRecipe != null) return fromRecipe;
            }
        }

        AmmoRecipeConfig.Config fromOverride = AmmoRecipeConfig.getOverride(ammoId);
        if (fromOverride != null) return fromOverride;

        return AmmoRecipeConfig.getCategoryDefault(ammoId);
    }

    @SuppressWarnings("unchecked")
    private static GunSmithTableRecipe findTaCZRecipe(ResourceLocation ammoId, RecipeManager recipeManager) {
        try {
            Field byTypeField = RecipeManager.class.getDeclaredField("byType");
            byTypeField.setAccessible(true);
            Multimap<RecipeType<?>, RecipeHolder<?>> byType =
                    (Multimap<RecipeType<?>, RecipeHolder<?>>) byTypeField.get(recipeManager);
            for (var entry : byType.entries()) {
                RecipeHolder<?> holder = entry.getValue();
                if (!(holder.value() instanceof GunSmithTableRecipe recipe)) continue;
                ItemStack output = recipe.getOutput();
                if (output.getItem() != taczAmmoItem) continue;
                IAmmo ia = IAmmo.getIAmmoOrNull(output);
                if (ia != null && ammoId.equals(ia.getAmmoId(output))) {
                    return recipe;
                }
            }
        } catch (Exception e) {
            SentryMechanicalArm.LOGGER.debug("TaCZ recipe scan failed for {}", ammoId, e);
        }
        return null;
    }

    private record RawStep(Item item, int count) {}

    private record ScaledResult(List<RawStep> steps, int outputCount) {}

    private static ScaledResult scaleToFitSteps(List<RawStep> rawSteps, int maxSteps, int outputCount) {
        int totalSteps = 0;
        for (RawStep s : rawSteps) totalSteps += s.count();
        boolean hasGunpowder = rawSteps.stream().anyMatch(s -> 
            s.item() == Items.GUNPOWDER || s.item() == Items.TNT);
        if (!hasGunpowder) totalSteps++;

        if (totalSteps <= maxSteps) return new ScaledResult(rawSteps, outputCount);

        int factor = (int) Math.ceil((double) totalSteps / maxSteps);
        List<RawStep> scaled = new ArrayList<>();
        for (RawStep s : rawSteps) {
            int newCount = Math.max(1, s.count() / factor);
            scaled.add(new RawStep(s.item(), newCount));
        }
        int newOutput = Math.max(1, outputCount / factor);
        return new ScaledResult(scaled, newOutput);
    }

    private static AmmoRecipeConfig.Config buildConfigFromRecipe(ResourceLocation ammoId, GunSmithTableRecipe recipe) {
        try {
            List<RawStep> rawSteps = new ArrayList<>();
            for (GunSmithTableIngredient input : recipe.getInputs()) {
                Ingredient ingredient = input.getIngredient();
                int count = input.getCount();
                ItemStack[] items = ingredient.getItems();
                if (items.length == 0) continue;

                Item rawItem = items[0].getItem();
                if (rawItem == Items.AIR) continue;

                Item mappedItem = mapToAssemblyItem(rawItem);
                rawSteps.add(new RawStep(mappedItem, count));
            }

            int outputCount = recipe.getOutput().getCount();
            if (outputCount <= 0) {
                outputCount = AmmoRecipeConfig.getCategoryDefault(ammoId).outputCount();
            }

            SMAServerConfig.StepScaling mode = SMAServerConfig.STEP_SCALING.get();
            if (mode == SMAServerConfig.StepScaling.GCD) {
                int gcd = outputCount;
                for (RawStep s : rawSteps) gcd = gcd(gcd, s.count());
                if (gcd > 1) {
                    for (int i = 0; i < rawSteps.size(); i++) {
                        RawStep s = rawSteps.get(i);
                        rawSteps.set(i, new RawStep(s.item(), s.count() / gcd));
                    }
                    outputCount /= gcd;
                }
            } else if (mode == SMAServerConfig.StepScaling.FIXED) {
                int factor = SMAServerConfig.FIXED_SCALE_FACTOR.get();
                if (factor > 1) {
                    for (int i = 0; i < rawSteps.size(); i++) {
                        RawStep s = rawSteps.get(i);
                        rawSteps.set(i, new RawStep(s.item(), Math.max(1, s.count() / factor)));
                    }
                    outputCount = Math.max(1, outputCount / factor);
                }
            }

            rawSteps = optimizeToBlocks(rawSteps);
            rawSteps = enforceMaxSteps(rawSteps, 7);
            var scaled = scaleToFitSteps(rawSteps, 7, outputCount);
            rawSteps = scaled.steps();
            outputCount = scaled.outputCount();

            List<Item> assemblySteps = new ArrayList<>();
            for (RawStep s : rawSteps) {
                for (int i = 0; i < s.count(); i++) {
                    assemblySteps.add(s.item());
                }
            }

            List<Item> gunpowderSteps = new ArrayList<>();
            assemblySteps.removeIf(item -> {
                if (item == Items.GUNPOWDER || item == Items.TNT) {
                    gunpowderSteps.add(item);
                    return true;
                }
                return false;
            });
            assemblySteps.addAll(gunpowderSteps);

            if (assemblySteps.stream().noneMatch(item -> item == Items.GUNPOWDER || item == Items.TNT)) {
                assemblySteps.add(Items.GUNPOWDER);
            }

            return new AmmoRecipeConfig.Config(
                    AmmoRecipeConfig.AmmoCategory.DEFAULT,
                    List.copyOf(assemblySteps),
                    outputCount
            );
        } catch (Exception e) {
            SentryMechanicalArm.LOGGER.debug("Failed to build config from recipe", e);
            return null;
        }
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int t = b; b = a % b; a = t; }
        return Math.abs(a);
    }

    private static Item mapToAssemblyItem(Item rawItem) {
        if (rawItem == Items.COPPER_INGOT) return AllItems.COPPER_SHEET.get();
        if (rawItem == Items.IRON_INGOT) return AllItems.IRON_SHEET.get();
        if (rawItem == Items.GOLD_INGOT) return AllItems.GOLDEN_SHEET.get();
        if (rawItem == Items.GUNPOWDER) return Items.GUNPOWDER;
        if (rawItem == AllItems.COPPER_SHEET.get()) return AllItems.COPPER_SHEET.get();
        if (rawItem == AllItems.IRON_SHEET.get()) return AllItems.IRON_SHEET.get();
        if (rawItem == AllItems.GOLDEN_SHEET.get()) return AllItems.GOLDEN_SHEET.get();
        return rawItem;
    }

    private static List<RawStep> enforceMaxSteps(List<RawStep> rawSteps, int maxSteps) {
        int totalSteps = 0;
        for (RawStep s : rawSteps) totalSteps += s.count();
        boolean hasGunpowder = rawSteps.stream().anyMatch(s -> 
            s.item() == Items.GUNPOWDER || s.item() == Items.TNT);
        if (!hasGunpowder) totalSteps++;

        if (totalSteps <= maxSteps) return rawSteps;

        List<RawStep> result = new ArrayList<>();
        for (RawStep step : rawSteps) {
            Item item = step.item();
            int count = step.count();

            if (item == Items.GUNPOWDER && count >= 2) {
                result.add(new RawStep(Items.TNT, 1));
            } else if (item == Items.GLOWSTONE_DUST && count >= 2) {
                result.add(new RawStep(Items.GLOWSTONE, 1));
            } else if (item == Items.SNOWBALL && count >= 2) {
                result.add(new RawStep(Items.SNOW_BLOCK, 1));
            } else if (item == Items.CLAY_BALL && count >= 2) {
                result.add(new RawStep(Items.CLAY, 1));
            } else if (item == Items.BRICK && count >= 2) {
                result.add(new RawStep(Items.BRICKS, 1));
            } else if (item == Items.NETHER_BRICK && count >= 2) {
                result.add(new RawStep(Items.NETHER_BRICKS, 1));
            } else if (item == Items.AMETHYST_SHARD && count >= 2) {
                result.add(new RawStep(Items.AMETHYST_BLOCK, 1));
            } else if (item == Items.QUARTZ && count >= 2) {
                result.add(new RawStep(Items.QUARTZ_BLOCK, 1));
            } else if (item == Items.HONEYCOMB && count >= 2) {
                result.add(new RawStep(Items.HONEYCOMB_BLOCK, 1));
            } else if (item == Items.BONE_MEAL && count >= 2) {
                result.add(new RawStep(Items.BONE_BLOCK, 1));
            } else if (item == Items.LAPIS_LAZULI && count >= 2) {
                result.add(new RawStep(Items.LAPIS_BLOCK, 1));
            } else if (item == Items.REDSTONE && count >= 2) {
                result.add(new RawStep(Items.REDSTONE_BLOCK, 1));
            } else if (item == Items.DIAMOND && count >= 2) {
                result.add(new RawStep(Items.DIAMOND_BLOCK, 1));
            } else if (item == Items.EMERALD && count >= 2) {
                result.add(new RawStep(Items.EMERALD_BLOCK, 1));
            } else if (item == Items.COPPER_INGOT && count >= 2) {
                result.add(new RawStep(Items.COPPER_BLOCK, 1));
            } else if (item == Items.IRON_INGOT && count >= 2) {
                result.add(new RawStep(Items.IRON_BLOCK, 1));
            } else if (item == Items.GOLD_INGOT && count >= 2) {
                result.add(new RawStep(Items.GOLD_BLOCK, 1));
            } else if (item == AllItems.COPPER_SHEET.get() && count >= 2) {
                result.add(new RawStep(Items.COPPER_BLOCK, 1));
            } else if (item == AllItems.IRON_SHEET.get() && count >= 2) {
                result.add(new RawStep(Items.IRON_BLOCK, 1));
            } else if (item == AllItems.GOLDEN_SHEET.get() && count >= 2) {
                result.add(new RawStep(Items.GOLD_BLOCK, 1));
            } else {
                result.add(step);
            }
        }
        return result;
    }

    private static List<RawStep> optimizeToBlocks(List<RawStep> rawSteps) {
        List<RawStep> result = new ArrayList<>();
        for (RawStep step : rawSteps) {
            Item item = step.item();
            int count = step.count();

            if (item == Items.GUNPOWDER) {
                if (count >= 4) {
                    result.add(new RawStep(Items.TNT, count / 4));
                    int remainder = count % 4;
                    if (remainder > 0) result.add(new RawStep(Items.GUNPOWDER, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == Items.GLOWSTONE_DUST) {
                if (count >= 4) {
                    result.add(new RawStep(Items.GLOWSTONE, count / 4));
                    int remainder = count % 4;
                    if (remainder > 0) result.add(new RawStep(Items.GLOWSTONE_DUST, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == Items.SNOWBALL) {
                if (count >= 4) {
                    result.add(new RawStep(Items.SNOW_BLOCK, count / 4));
                    int remainder = count % 4;
                    if (remainder > 0) result.add(new RawStep(Items.SNOWBALL, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == Items.CLAY_BALL) {
                if (count >= 4) {
                    result.add(new RawStep(Items.CLAY, count / 4));
                    int remainder = count % 4;
                    if (remainder > 0) result.add(new RawStep(Items.CLAY_BALL, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == Items.BRICK) {
                if (count >= 4) {
                    result.add(new RawStep(Items.BRICKS, count / 4));
                    int remainder = count % 4;
                    if (remainder > 0) result.add(new RawStep(Items.BRICK, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == Items.NETHER_BRICK) {
                if (count >= 4) {
                    result.add(new RawStep(Items.NETHER_BRICKS, count / 4));
                    int remainder = count % 4;
                    if (remainder > 0) result.add(new RawStep(Items.NETHER_BRICK, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == Items.AMETHYST_SHARD) {
                if (count >= 4) {
                    result.add(new RawStep(Items.AMETHYST_BLOCK, count / 4));
                    int remainder = count % 4;
                    if (remainder > 0) result.add(new RawStep(Items.AMETHYST_SHARD, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == Items.QUARTZ) {
                if (count >= 4) {
                    result.add(new RawStep(Items.QUARTZ_BLOCK, count / 4));
                    int remainder = count % 4;
                    if (remainder > 0) result.add(new RawStep(Items.QUARTZ, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == Items.HONEYCOMB) {
                if (count >= 4) {
                    result.add(new RawStep(Items.HONEYCOMB_BLOCK, count / 4));
                    int remainder = count % 4;
                    if (remainder > 0) result.add(new RawStep(Items.HONEYCOMB, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == Items.BONE_MEAL) {
                if (count >= 9) {
                    result.add(new RawStep(Items.BONE_BLOCK, count / 9));
                    int remainder = count % 9;
                    if (remainder > 0) result.add(new RawStep(Items.BONE_MEAL, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == Items.LAPIS_LAZULI) {
                if (count >= 9) {
                    result.add(new RawStep(Items.LAPIS_BLOCK, count / 9));
                    int remainder = count % 9;
                    if (remainder > 0) result.add(new RawStep(Items.LAPIS_LAZULI, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == Items.REDSTONE) {
                if (count >= 9) {
                    result.add(new RawStep(Items.REDSTONE_BLOCK, count / 9));
                    int remainder = count % 9;
                    if (remainder > 0) result.add(new RawStep(Items.REDSTONE, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == Items.DIAMOND) {
                if (count >= 9) {
                    result.add(new RawStep(Items.DIAMOND_BLOCK, count / 9));
                    int remainder = count % 9;
                    if (remainder > 0) result.add(new RawStep(Items.DIAMOND, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == Items.EMERALD) {
                if (count >= 9) {
                    result.add(new RawStep(Items.EMERALD_BLOCK, count / 9));
                    int remainder = count % 9;
                    if (remainder > 0) result.add(new RawStep(Items.EMERALD, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == Items.COPPER_INGOT) {
                if (count >= 9) {
                    result.add(new RawStep(Items.COPPER_BLOCK, count / 9));
                    int remainder = count % 9;
                    if (remainder > 0) result.add(new RawStep(Items.COPPER_INGOT, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == Items.IRON_INGOT) {
                if (count >= 9) {
                    result.add(new RawStep(Items.IRON_BLOCK, count / 9));
                    int remainder = count % 9;
                    if (remainder > 0) result.add(new RawStep(Items.IRON_INGOT, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == Items.GOLD_INGOT) {
                if (count >= 9) {
                    result.add(new RawStep(Items.GOLD_BLOCK, count / 9));
                    int remainder = count % 9;
                    if (remainder > 0) result.add(new RawStep(Items.GOLD_INGOT, remainder));
                } else {
                    result.add(step);
                }
            } else if (item == AllItems.COPPER_SHEET.get()) {
                if (count >= 9) {
                    result.add(new RawStep(Items.COPPER_BLOCK, count / 9));
                    int remainder = count % 9;
                    if (remainder > 0) result.add(new RawStep(AllItems.COPPER_SHEET.get(), remainder));
                } else {
                    result.add(step);
                }
            } else if (item == AllItems.IRON_SHEET.get()) {
                if (count >= 9) {
                    result.add(new RawStep(Items.IRON_BLOCK, count / 9));
                    int remainder = count % 9;
                    if (remainder > 0) result.add(new RawStep(AllItems.IRON_SHEET.get(), remainder));
                } else {
                    result.add(step);
                }
            } else if (item == AllItems.GOLDEN_SHEET.get()) {
                if (count >= 9) {
                    result.add(new RawStep(Items.GOLD_BLOCK, count / 9));
                    int remainder = count % 9;
                    if (remainder > 0) result.add(new RawStep(AllItems.GOLDEN_SHEET.get(), remainder));
                } else {
                    result.add(step);
                }
            } else {
                result.add(step);
            }
        }
        return result;
    }

    private static void injectRecipes(RecipeManager recipeManager, List<RecipeHolder<?>> newRecipes) {
        try {
            Field byTypeField = RecipeManager.class.getDeclaredField("byType");
            byTypeField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Multimap<RecipeType<?>, RecipeHolder<?>> byType =
                    (Multimap<RecipeType<?>, RecipeHolder<?>>) byTypeField.get(recipeManager);

            Collection<RecipeHolder<?>> allRecipes = new ArrayList<>(byType.values());
            allRecipes.addAll(newRecipes);
            recipeManager.replaceRecipes(allRecipes);

            SentryMechanicalArm.LOGGER.info(
                    "DynamicRecipeManager: Successfully injected {} recipes", newRecipes.size());
        } catch (Exception e) {
            SentryMechanicalArm.LOGGER.error(
                    "DynamicRecipeManager: Failed to inject recipes", e);
        }
    }

    private static void verifyRecipes(RecipeManager recipeManager) {
        int ourRecipes = 0;
        try {
            Field byTypeField = RecipeManager.class.getDeclaredField("byType");
            byTypeField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Multimap<RecipeType<?>, RecipeHolder<?>> byType =
                    (Multimap<RecipeType<?>, RecipeHolder<?>>) byTypeField.get(recipeManager);
            for (RecipeType<?> type : byType.keySet()) {
                for (RecipeHolder<?> holder : byType.get(type)) {
                    if (holder.id().getPath().startsWith("ammo_")) {
                        ourRecipes++;
                    }
                }
            }
            SentryMechanicalArm.LOGGER.info(
                    "DynamicRecipeManager: Found {} our recipes in RecipeManager", ourRecipes);
        } catch (Exception e) {
            SentryMechanicalArm.LOGGER.error(
                    "DynamicRecipeManager: Failed to verify recipes", e);
        }
    }
}
