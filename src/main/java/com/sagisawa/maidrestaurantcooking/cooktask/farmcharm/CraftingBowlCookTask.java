package com.sagisawa.maidrestaurantcooking.cooktask.farmcharm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.ItemHandlerUtils;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import com.sagisawa.maidrestaurantcooking.cooktask.timed.AbstractTimedContainerCookTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.satisfy.farm_and_charm.core.block.CraftingBowlBlock;
import net.satisfy.farm_and_charm.core.block.entity.CraftingBowlBlockEntity;
import net.satisfy.farm_and_charm.core.recipe.CraftingBowlRecipe;
import net.satisfy.farm_and_charm.core.registry.RecipeTypeRegistry;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * 沉浸农艺「搅拌碗」（farm_and_charm:crafting_bowl，6 条）的女仆烹饪任务。
 * 实证：0-3 配料槽 + 4 输出槽；STIRRING/STIRRED 属性驱动手摇搅拌，stirred=50 且
 * 有配料时转化（配料消耗余量、产物 setItem(4)）。女仆：投料 → 驱动 STIRRING → 取货。
 */
public class CraftingBowlCookTask extends AbstractTimedContainerCookTask<CraftingBowlRecipe> {

    private static final int OUTPUT_SLOT = 4;

    @Override
    public String getUID() {
        return "CraftingBowlCookTask";
    }

    @Override
    public ItemStack getIcon() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("farm_and_charm", "crafting_bowl"));
        return block.defaultBlockState().isAir() ? ItemStack.EMPTY : new ItemStack(block);
    }

    @Override
    protected RecipeType<?> recipeType() {
        return RecipeTypeRegistry.CRAFTING_BOWL_RECIPE_TYPE.get();
    }

    @Override
    protected Class<CraftingBowlRecipe> recipeClass() {
        return CraftingBowlRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(CraftingBowlRecipe recipe, Level level) {
        return recipe.getResultItem(level.registryAccess());
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof CraftingBowlBlockEntity;
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        CraftingBowlRecipe recipe = (CraftingBowlRecipe) recipeHolder.value();
        return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).map(StackPredicate::new).toList();
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new java.util.ArrayList<>();
        if (level.getBlockEntity(pos) instanceof CraftingBowlBlockEntity bowl) {
            for (int i = 0; i < OUTPUT_SLOT; i++) {
                if (!bowl.getItem(i).isEmpty()) {
                    ans.add(bowl.getItem(i).copy());
                }
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof CraftingBowlBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        CraftingBowlBlockEntity bowl = level.getBlockEntity(pos) instanceof CraftingBowlBlockEntity b ? b : null;
        if (bowl == null) {
            return;
        }
        CraftingBowlRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[xia-de-chu-fang] crafting_bowl 请求配方缺失或类型不符：{}", request.id);
            return;
        }

        // 取货（槽 4）
        ItemStack output = bowl.getItem(OUTPUT_SLOT);
        ItemStack expect = recipe.getResultItem(level.registryAccess());
        if (!output.isEmpty() && output.getItem() == expect.getItem()) {
            ItemUtils.getItemToLivingEntity(maid, output.copy());
            bowl.removeItem(OUTPUT_SLOT, output.getCount());
            bowl.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
            request.remain--;
            return;
        }

        // 投料（0-3 空槽）
        boolean supplied = false;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            boolean present = false;
            for (int i = 0; i < OUTPUT_SLOT; i++) {
                if (ingredient.test(bowl.getItem(i))) {
                    present = true;
                    break;
                }
            }
            if (present) {
                continue;
            }
            ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(ingredient), true);
            if (!take.isEmpty()) {
                boolean putIn = false;
                for (int i = 0; i < OUTPUT_SLOT; i++) {
                    if (bowl.getItem(i).isEmpty()) {
                        bowl.setItem(i, take);
                        bowl.setChanged();
                        putIn = true;
                        supplied = true;
                        break;
                    }
                }
                if (!putIn) {
                    ItemUtils.getItemToLivingEntity(maid, take);
                    MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] crafting_bowl 满槽放料失败，原料已回包：{}", request.id);
                }
            }
        }

        // 搅拌驱动：STIRRING 静止且有配料 → 补搅
        BlockState state = level.getBlockState(pos);
        if (state.getValue(CraftingBowlBlock.STIRRING) == 0 && bowl.getItem(OUTPUT_SLOT).isEmpty()) {
            boolean anyInput = false;
            for (int i = 0; i < OUTPUT_SLOT; i++) {
                if (!bowl.getItem(i).isEmpty()) {
                    anyInput = true;
                    break;
                }
            }
            if (anyInput) {
                level.setBlock(pos, state.setValue(CraftingBowlBlock.STIRRING, 8), 3);
            }
        }
        if (supplied) {
            maid.swing(InteractionHand.OFF_HAND);
        }
    }
}
