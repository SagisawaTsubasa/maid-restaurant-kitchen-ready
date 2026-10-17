package com.sagisawa.maidrestaurantcooking.cooktask.chinesefood;

import com.bmt.kaleidoscope_chinesefood.block.entity.FreezerBlockEntity;
import com.bmt.kaleidoscope_chinesefood.crafting.BaseProcessingRecipe;
import com.bmt.kaleidoscope_chinesefood.init.ModBlocks;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.ItemHandlerUtils;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import com.sagisawa.maidrestaurantcooking.cooktask.timed.AbstractTimedContainerCookTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 国味「冰箱」双任务基类（freezing/refrigerating 两个 RecipeType 共用一个 BE）。
 *
 * <p>实证（2026-10-17）：FreezerBlockEntity 为双联方块，isTop()=上半（REFRIGERATING 冷藏）
 * /下半（FREEZING 冰冻），36/54 格逐槽自动处理（serverTick 自匹配配方转化，常开无环境条件）。
 * 女仆流程：找到对应半区的空槽 → 投 1 组原料 → BE 自转化 → 槽内变产物 → 取走。
 * 两个子类仅 recipeType()/wantsTop() 不同。容器操作走 vanilla Container API
 * （RandomizableContainerBlockEntity 的 getItem/setItem/removeItem 公开）。
 */
public abstract class ChinesefoodFreezerCookTask extends AbstractTimedContainerCookTask<BaseProcessingRecipe> {

    /** 冷藏任务取上半，冰冻任务取下半。 */
    protected abstract boolean wantsTop();

    @Override
    protected Class<BaseProcessingRecipe> recipeClass() {
        return BaseProcessingRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(BaseProcessingRecipe recipe, Level level) {
        return recipe.getResultItem(level.registryAccess());
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> {
            if (!(level.getBlockEntity(pos) instanceof FreezerBlockEntity be)) {
                return false;
            }
            // 只判上/下半（多色变体共用 BE）
            return be.isTop() == wantsTop();
        };
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        BaseProcessingRecipe recipe = (BaseProcessingRecipe) recipeHolder.value();
        List<StackPredicate> ans = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) {
                ans.add(StackPredicate.of(ingredient));
            }
        }
        return ans;
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new ArrayList<>();
        if (level.getBlockEntity(pos) instanceof FreezerBlockEntity be) {
            for (int i = 0; i < be.getContainerSize(); i++) {
                ItemStack stack = be.getItem(i);
                if (!stack.isEmpty()) {
                    ans.add(stack.copy());
                }
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FreezerBlockEntity be && be.isTop() == wantsTop();
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        FreezerBlockEntity be = level.getBlockEntity(pos) instanceof FreezerBlockEntity f ? f : null;
        if (be == null || be.isTop() != wantsTop()) {
            return;
        }
        BaseProcessingRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[xia-de-chu-fang] freezer 请求配方缺失或类型不符：{}（{}）", request.id, getUID());
            return;
        }
        // BaseProcessingRecipe 的 input/output 为 protected：走公开访问器
        ItemStack output = recipe.getResultItem(level.registryAccess());
        Ingredient inputIngredient = recipe.getIngredients().isEmpty() ? Ingredient.EMPTY : recipe.getIngredients().get(0);

        // F-121：先扫异物（非空且非本单产物 → 响出停摆）
        for (int i = 0; i < be.getContainerSize(); i++) {
            ItemStack scan = be.getItem(i);
            if (!scan.isEmpty() && scan.getItem() != output.getItem()) {
                MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] 冰箱槽位存在非本单产物（{}），停摆待处理：{} @ {}", scan.getItem(), request.id, pos);
                return;
            }
        }

        // 产物取出（BE 转化保留输入数量：result.count=输入.count）
        for (int i = 0; i < be.getContainerSize(); i++) {
            ItemStack stored = be.getItem(i);
            if (!stored.isEmpty() && ItemStack.isSameItemSameComponents(stored, output)) {
                ItemUtils.getItemToLivingEntity(maid, stored.copy());
                be.removeItem(i, stored.getCount());
                be.setChanged();
                maid.swing(InteractionHand.OFF_HAND);
                request.remain--;
                return;
            }
        }

        // 已有原料在处理中 → 等
        for (int i = 0; i < be.getContainerSize(); i++) {
            ItemStack stored = be.getItem(i);
            if (!stored.isEmpty() && inputIngredient.test(stored)) {
                return;
            }
        }

        // 投料：先探空槽再抽（F-002 防吞）
        boolean hasEmpty = false;
        for (int i = 0; i < be.getContainerSize(); i++) {
            if (be.getItem(i).isEmpty()) {
                hasEmpty = true;
                break;
            }
        }
        if (!hasEmpty) {
            return;
        }
        ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(inputIngredient), true);
        if (take.isEmpty()) {
            return;
        }
        for (int i = 0; i < be.getContainerSize(); i++) {
            if (be.getItem(i).isEmpty()) {
                be.setItem(i, take);
                be.setChanged();
                maid.swing(InteractionHand.OFF_HAND);
                return;
            }
        }
        ItemUtils.getItemToLivingEntity(maid, take);
        MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] freezer 满槽放料失败，原料已回包：{}", request.id);
    }

    /** 冰冻（下半）。 */
    public static class Freezing extends ChinesefoodFreezerCookTask {
        @Override
        public String getUID() {
            return "FreezerFreezingCookTask";
        }

        @Override
        public ItemStack getIcon() {
            return new ItemStack(ModBlocks.FREEZER.get().asItem());
        }

        @Override
        protected RecipeType<BaseProcessingRecipe> recipeType() {
            @SuppressWarnings("unchecked")
            RecipeType<BaseProcessingRecipe> type = (RecipeType<BaseProcessingRecipe>) (RecipeType<?>) com.bmt.kaleidoscope_chinesefood.init.ModRecipes.FREEZING_TYPE.get();
            return type;
        }

        @Override
        protected boolean wantsTop() {
            return false;
        }
    }

    /** 冷藏（上半）。 */
    public static class Refrigerating extends ChinesefoodFreezerCookTask {
        @Override
        public String getUID() {
            return "FreezerRefrigeratingCookTask";
        }

        @Override
        public ItemStack getIcon() {
            return new ItemStack(ModBlocks.FREEZER.get().asItem());
        }

        @Override
        protected RecipeType<BaseProcessingRecipe> recipeType() {
            @SuppressWarnings("unchecked")
            RecipeType<BaseProcessingRecipe> type = (RecipeType<BaseProcessingRecipe>) (RecipeType<?>) com.bmt.kaleidoscope_chinesefood.init.ModRecipes.REFRIGERATING_TYPE.get();
            return type;
        }

        @Override
        protected boolean wantsTop() {
            return true;
        }
    }
}
