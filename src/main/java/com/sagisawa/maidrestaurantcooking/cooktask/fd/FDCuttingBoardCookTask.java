package com.sagisawa.maidrestaurantcooking.cooktask.fd;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.ItemHandlerUtils;
import com.mastermarisa.maid_restaurant.utils.component.RecipeData;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import com.sagisawa.maidrestaurantcooking.cooktask.timed.AbstractTimedContainerCookTask;
import com.sagisawa.maidrestaurantcooking.utils.RecipeDataFilter;
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
import vectorwing.farmersdelight.common.block.entity.CuttingBoardBlockEntity;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.registry.ModItems;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 农夫乐事「砧板」（farmersdelight:cutting，259 条，工具右键型）的女仆烹饪任务。
 *
 * <p>实证：addItem 放料 / processStoredItemUsingTool(工具, player) 一刀消耗 1 输入、
 * 产物掉落实体喷出、工具 hurtAndBreak 损耗。女仆流程：放料 → 按配方工具谓词抽刀 →
 * 一刀一转化（damaged tool 回女仆包）→ remain--。掉落实体靠女仆拾取 AI 收集（真机验证点）。
 * 「普通任务接入 + 排除清单可滤」路线（v5 §六.2 倾向案）。
 */
public class FDCuttingBoardCookTask extends AbstractTimedContainerCookTask<CuttingBoardRecipe> {

    @Override
    public String getUID() {
        return "FDCuttingBoardCookTask";
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ModItems.CUTTING_BOARD.get());
    }

    @Override
    protected RecipeType<?> recipeType() {
        return ModRecipeTypes.CUTTING.get();
    }

    @Override
    protected Class<CuttingBoardRecipe> recipeClass() {
        return CuttingBoardRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(CuttingBoardRecipe recipe, Level level) {
        List<ItemStack> results = recipe.getResults();
        return results.isEmpty() ? ItemStack.EMPTY : results.getFirst();
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof CuttingBoardBlockEntity;
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        CuttingBoardRecipe recipe = (CuttingBoardRecipe) recipeHolder.value();
        List<StackPredicate> ans = new ArrayList<>();
        for (var ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) {
                ans.add(StackPredicate.of(ingredient));
            }
        }
        ans.add(StackPredicate.of(recipe.getTool()));
        return ans;
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new ArrayList<>();
        if (level.getBlockEntity(pos) instanceof CuttingBoardBlockEntity board && !board.getStoredItem().isEmpty()) {
            ans.add(board.getStoredItem().copy());
        }
        return ans;
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
        CuttingBoardRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[xia-de-chu-fang] fd_cutting 请求配方缺失或类型不符：{}", request.id);
            return;
        }

        // 放料
        if (board.getStoredItem().isEmpty()) {
            var ingredients = recipe.getIngredients();
            Ingredient input = ingredients.isEmpty() ? Ingredient.EMPTY : ingredients.getFirst();
            ItemStack take = input.isEmpty() ? ItemStack.EMPTY
                    : ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(input), true);
            if (!take.isEmpty()) {
                ItemStack remain = board.addItem(take);
                if (!remain.isEmpty()) {
                    // F-013：addItem 返回未接纳部分——退回女仆包
                    ItemUtils.getItemToLivingEntity(maid, remain);
                } else {
                    maid.swing(InteractionHand.OFF_HAND);
                }
            }
            return;
        }

        // 抽刀（destructive：刀要损耗）→ 一刀一转化
        ItemStack tool = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(recipe.getTool()), true);
        if (tool.isEmpty()) {
            return;
        }
        boolean processed = board.processStoredItemUsingTool(tool, null);
        if (!tool.isEmpty()) {
            // 未损耗完的工具回女仆包
            ItemUtils.getItemToLivingEntity(maid, tool);
        }
        if (processed) {
            maid.swing(InteractionHand.OFF_HAND);
            // F-012：产物落地依赖女仆拾取 AI（真机 sweep C2 验证）
            MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] FD 砧板产物已落地待拾取：{} @ {}", request.id, pos);
            request.remain--;
        }
    }
}
