package com.sagisawa.maidrestaurantcooking.cooktask.bakeries;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.ItemHandlerUtils;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import com.renyigesai.bakeries.common.blocks.fermentation_box.FermentationBoxBlockEntity;
import com.renyigesai.bakeries.common.init.BakeriesRecipeTypes;
import com.renyigesai.bakeries.common.recipe.FermentationBoxRecipe;
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

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * 烘焙坊「醒发箱」（bakeries:fermentation_box，4 条）的女仆烹饪任务。
 *
 * <p>实证（2026-10-17）：FermentationBoxBlockEntity（BaseContainerBlockEntity，6 格，
 * Container API 公开）；tick 自动醒发——要求 fermentationMaxTime≥430（箱内温度累积，
 * refreshTemperature 自理）；每槽计时到顶转 getAllResults().getFirst()，完美窗口
 * 附加 PERFECT_FERMENTATION 组件。女仆流程：投料 → 等转化 → 取走（物品匹配用
 * isSameItem：完美醒发变体组件不同也能取）。
 */
public class FermentationBoxCookTask extends AbstractTimedContainerCookTask<FermentationBoxRecipe> {

    @Override
    public String getUID() {
        return "FermentationBoxCookTask";
    }

    @Override
    public ItemStack getIcon() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("bakeries", "fermentation_box"));
        return block.defaultBlockState().isAir() ? ItemStack.EMPTY : new ItemStack(block);
    }

    @Override
    protected RecipeType<?> recipeType() {
        return BakeriesRecipeTypes.FERMENTATION_BOX_TYPE.get();
    }

    @Override
    protected Class<FermentationBoxRecipe> recipeClass() {
        return FermentationBoxRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(FermentationBoxRecipe recipe, Level level) {
        return recipe.getAllResults().isEmpty() ? ItemStack.EMPTY : recipe.getAllResults().getFirst();
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof FermentationBoxBlockEntity;
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        FermentationBoxRecipe recipe = (FermentationBoxRecipe) recipeHolder.value();
        return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).map(StackPredicate::new).toList();
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new java.util.ArrayList<>();
        if (level.getBlockEntity(pos) instanceof FermentationBoxBlockEntity box) {
            for (int i = 0; i < box.getContainerSize(); i++) {
                if (!box.getItem(i).isEmpty()) {
                    ans.add(box.getItem(i).copy());
                }
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FermentationBoxBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        FermentationBoxBlockEntity box = level.getBlockEntity(pos) instanceof FermentationBoxBlockEntity b ? b : null;
        if (box == null) {
            return;
        }
        FermentationBoxRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[xia-de-chu-fang] fermentation_box 请求配方缺失或类型不符：{}", request.id);
            return;
        }
        ItemStack result = recipeResult(recipe, level);
        if (result.isEmpty()) {
            return;
        }
        Ingredient ingredient = recipe.getIngredients().isEmpty() ? Ingredient.EMPTY : recipe.getIngredients().get(0);

        // 产物取出（isSameItem：完美醒发变体带附加组件，组件级比较会漏）
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack stored = box.getItem(i);
            if (!stored.isEmpty() && stored.getItem() == result.getItem()) {
                ItemUtils.getItemToLivingEntity(maid, stored.copy());
                box.removeItem(i, stored.getCount());
                box.setChanged();
                maid.swing(InteractionHand.OFF_HAND);
                request.remain--;
                return;
            }
        }

        // 已有原料醒发中 → 等
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack stored = box.getItem(i);
            if (!stored.isEmpty() && ingredient.test(stored)) {
                return;
            }
        }

        // 投料
        ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(ingredient), true);
        if (take.isEmpty()) {
            return;
        }
        for (int i = 0; i < box.getContainerSize(); i++) {
            if (box.getItem(i).isEmpty()) {
                box.setItem(i, take);
                box.setChanged();
                maid.swing(InteractionHand.OFF_HAND);
                return;
            }
        }
    }
}
