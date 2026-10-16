package com.sagisawa.maidrestaurantcooking.cooktask.furniture;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.ItemHandlerUtils;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import com.sagisawa.maidrestaurantcooking.cooktask.timed.AbstractTimedContainerCookTask;
import com.mrcrayfish.furniture.refurbished.blockentity.CuttingBoardBlockEntity;
import com.mrcrayfish.furniture.refurbished.crafting.CuttingBoardSlicingRecipe;
import net.minecraft.core.BlockPos;
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
 * MrCrayfish 家具「砧板切片」（refurbished_furniture:cutting_board_slicing，41 条）的女仆烹饪任务。
 *
 * <p>实证：CuttingBoardBlockEntity.placeItem(held) 放料 / sliceItem(level, spawnIntoLevel)
 * 一刀一切（产物 spawnIntoLevel=true 落地，女仆拾取 AI 收集）/ removeItem() 取回。
 * combining（6 条）与厨电（微波炉/煎锅/烤箱/烤面包机/冰柜，13 条）挪 M5。
 */
public class FurnitureSlicingCookTask extends AbstractTimedContainerCookTask<CuttingBoardSlicingRecipe> {

    @Override
    public String getUID() {
        return "FurnitureSlicingCookTask";
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(net.minecraft.world.item.Items.IRON_AXE);
    }

    @Override
    protected RecipeType<?> recipeType() {
        // 家具 RegistryEntry 依赖 Framework 库（不在编译类路径），走注册表键查；空值守卫（F-007）
        RecipeType<?> t = net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("refurbished_furniture", "cutting_board_slicing"));
        if (t == null) {
            throw new IllegalStateException("refurbished_furniture:cutting_board_slicing RecipeType 未注册（目标 mod 版本不符？）");
        }
        return t;
    }

    @Override
    protected Class<CuttingBoardSlicingRecipe> recipeClass() {
        return CuttingBoardSlicingRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(CuttingBoardSlicingRecipe recipe, Level level) {
        return recipe.getResult();
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof CuttingBoardBlockEntity;
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        CuttingBoardSlicingRecipe recipe = (CuttingBoardSlicingRecipe) recipeHolder.value();
        return List.of(StackPredicate.of(recipe.getIngredient()));
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        // 家具菜板无公开读取接口（placeItem/sliceItem/removeItem only）；报告空，重复投料由 placeItem 的占位语义兜底
        return List.of();
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof CuttingBoardBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        CuttingBoardBlockEntity board = level.getBlockEntity(pos) instanceof CuttingBoardBlockEntity b ? b : null;
        if (board == null) {
            return;
        }
        CuttingBoardSlicingRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[xia-de-chu-fang] furniture_slicing 请求配方缺失或类型不符：{}", request.id);
            return;
        }

        Ingredient ingredient = recipe.getIngredient();
        ItemStack take = ingredient.isEmpty() ? ItemStack.EMPTY
                : ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(ingredient), true);
        if (take.isEmpty()) {
            return;
        }
        if (board.placeItem(take)) {
            maid.swing(InteractionHand.OFF_HAND);
            // 放上即切（一刀一片，产物落地）
            if (board.sliceItem(level, true)) {
                maid.swing(InteractionHand.OFF_HAND);
                // F-012：产物落地依赖女仆拾取 AI（真机 sweep C3 验证）
                MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] 家具砧板产物已落地待拾取：{} @ {}", request.id, pos);
                request.remain--;
            }
        } else {
            // 板被占（他单在切）：还料
            ItemUtils.getItemToLivingEntity(maid, take);
        }
    }
}
