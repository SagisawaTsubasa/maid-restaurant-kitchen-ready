package com.sagisawa.maidrestaurantcooking.cooktask.kaleidoscope;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.ChoppingBoardRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.ItemHandlerUtils;
import com.mastermarisa.maid_restaurant.utils.component.RecipeData;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import com.sagisawa.maidrestaurantcooking.cooktask.timed.AbstractTimedContainerCookTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 森罗厨房「菜板」（kaleidoscope_cookery:chopping_board，46 条，多产物切配）的女仆烹饪任务。
 *
 * <p>实证：IChoppingBoard API——onPutItem 放料 / onCutItem（KITCHEN_KNIFE 标签刀，
 * 不消耗刀）计数 / 计满后下一次 onCutItem 弹出产物（掉落实体）并复位 / onTakeOut 只取回原料。
 * 女仆流程：放料 → 逐刀切（tryGet 非破坏取刀）→ 末刀弹出产物（remain--）；
 * 掉落实体由女仆拾取 AI 收集（真机验证点，见验证记录）。
 */
public class ChoppingBoardCookTask extends AbstractTimedContainerCookTask<ChoppingBoardRecipe> {

    @Override
    public String getUID() {
        return "ChoppingBoardCookTask";
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ModItems.CHOPPING_BOARD.get());
    }

    @Override
    protected RecipeType<?> recipeType() {
        return com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes.CHOPPING_BOARD_RECIPE;
    }

    @Override
    protected Class<ChoppingBoardRecipe> recipeClass() {
        return ChoppingBoardRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(ChoppingBoardRecipe recipe, Level level) {
        return recipe.getResults().isEmpty() ? ItemStack.EMPTY : recipe.getResults().getFirst();
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof ChoppingBoardBlockEntity;
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        ChoppingBoardRecipe recipe = (ChoppingBoardRecipe) recipeHolder.value();
        List<StackPredicate> ans = new ArrayList<>();
        ans.add(StackPredicate.of(recipe.getIngredient()));
        ans.add(StackPredicate.of(s -> s.is(TagMod.KITCHEN_KNIFE)));
        return ans;
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new ArrayList<>();
        if (level.getBlockEntity(pos) instanceof ChoppingBoardBlockEntity board) {
            ItemStack current = board.getCurrentCutStack();
            if (!current.isEmpty()) {
                ans.add(current.copy());
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ChoppingBoardBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        ChoppingBoardBlockEntity board = level.getBlockEntity(pos) instanceof ChoppingBoardBlockEntity b ? b : null;
        if (board == null) {
            return;
        }
        ChoppingBoardRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[xia-de-chu-fang] chopping_board 请求配方缺失或类型不符：{}", request.id);
            return;
        }

        // 无料 → 放料
        if (board.getCurrentCutStack().isEmpty()) {
            ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(recipe.getIngredient()), true);
            if (!take.isEmpty() && board.onPutItem(level, maid, take)) {
                maid.swing(InteractionHand.OFF_HAND);
            }
            return;
        }

        // 切：非破坏取刀；onCutItem 返回 true 且板已复位 = 产物已弹出
        ItemStack knife = ItemHandlerUtils.tryGet(maid.getAvailableInv(false), StackPredicate.of(s -> s.is(TagMod.KITCHEN_KNIFE)));
        if (knife.isEmpty()) {
            return;
        }
        boolean cut = board.onCutItem(level, maid, knife);
        if (cut) {
            maid.swing(InteractionHand.OFF_HAND);
            if (board.getCurrentCutStack().isEmpty()) {
                // F-012：产物以掉落实体落地，语义为"烹饪完成"，拾取由女仆常规拾取行为兜底（真机 sweep C1 专项验证）
                MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] 菜板产物已落地待拾取：{} @ {}", request.id, pos);
                request.remain--;
            }
        }
    }
}
