package com.sagisawa.maidrestaurantcooking.cooktask.fid;

import com.flavor_immersed_daily.block.blockentity.FridgeBlockEntity;
import com.flavor_immersed_daily.recipe.FridgeFreezingRecipe;
import com.flavor_immersed_daily.recipe.FridgeTemperingRecipe;
import com.flavor_immersed_daily.recipe.ModRecipes;
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
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 烟火凡人心「冰箱」双任务基类（fridge_freezing/fridge_tempering 共用一个 BE）。
 *
 * <p>实证（2026-10-17）：31 格——0..26 存储、27 冷藏输入 → 28 冷藏输出、29 冰冻输入 →
 * 30 冰冻输出；serverTick 自动处理（无环境条件）。女仆流程：投料到专用输入槽 →
 * BE 自转化 → 从输出槽取走。两类配方类无公共接口，泛型 + 子类访问器桥接。
 */
public abstract class FidFridgeCookTask<R extends Recipe<?>> extends AbstractTimedContainerCookTask<R> {

    /** 冷藏输入 27 / 冰冻输入 29。 */
    protected abstract int inputSlot();

    /** F-141：停摆 WARN 上次响出时间戳（30s 时间窗去重）。 */
    private volatile long stallLastWarnMs;

    /** 冷藏输出 28 / 冰冻输出 30。 */
    protected abstract int outputSlot();

    /** 配方产物访问桥。 */
    protected abstract ItemStack resultOf(R recipe);

    /** 配方原料访问桥。 */
    protected abstract Ingredient ingredientOf(R recipe);

    @Override
    protected ItemStack recipeResult(R recipe, Level level) {
        return resultOf(recipe);
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof FridgeBlockEntity;
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends Recipe<?>> recipeHolder, Level level) {
        List<StackPredicate> ans = new ArrayList<>();
        if (recipeClass().isInstance(recipeHolder.value())) {
            Ingredient ingredient = ingredientOf(recipeClass().cast(recipeHolder.value()));
            if (!ingredient.isEmpty()) {
                ans.add(StackPredicate.of(ingredient));
            }
        }
        return ans;
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new ArrayList<>();
        if (level.getBlockEntity(pos) instanceof FridgeBlockEntity fridge) {
            ItemStack input = fridge.getItem(inputSlot());
            if (!input.isEmpty()) {
                ans.add(input.copy());
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FridgeBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        FridgeBlockEntity fridge = level.getBlockEntity(pos) instanceof FridgeBlockEntity f ? f : null;
        if (fridge == null) {
            return;
        }
        R recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] fridge 请求配方缺失或类型不符：{}（{}）", request.id, getUID());
            return;
        }
        ItemStack result = resultOf(recipe);
        Ingredient ingredient = ingredientOf(recipe);

        // 输出槽有本配方产物 → 取走（F-121：异物产出响出）
        ItemStack output = fridge.getItem(outputSlot());
        if (!output.isEmpty() && output.getItem() != result.getItem()) {
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 冰箱输出槽为非本单产物（{}），停摆待处理：{} @ {}", output.getItem(), request.id, pos);
            return;
        }
        if (!output.isEmpty() && output.getItem() == result.getItem()) {
            ItemUtils.getItemToLivingEntity(maid, output.copy());
            fridge.removeItem(outputSlot(), output.getCount());
            fridge.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
            request.remain--;
            return;
        }

        // 输入槽已有料：本单原料 → 等；异物 → WARN 停摆（F-132/F-141：30s 时间窗防刷屏）
        ItemStack pending = fridge.getItem(inputSlot());
        if (!pending.isEmpty()) {
            if (!ingredient.test(pending) && System.currentTimeMillis() - stallLastWarnMs > 30_000L) {
                stallLastWarnMs = System.currentTimeMillis();
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 冰箱输入槽为非本单原料（{}），停摆待处理：{} @ {}", pending.getItem(), request.id, pos);
            }
            return;
        }

        // 投料到专用输入槽
        ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(ingredient), true);
        if (take.isEmpty()) {
            return;
        }
        fridge.setItem(inputSlot(), take);
        fridge.setChanged();
        maid.swing(InteractionHand.OFF_HAND);
    }

    /** 冰冻（fridge_freezing，6 条）。 */
    public static class Freezing extends FidFridgeCookTask<FridgeFreezingRecipe> {
        @Override
        public String getUID() {
            return "FidFridgeFreezingCookTask";
        }

        @Override
        public ItemStack getIcon() {
            return new ItemStack(com.flavor_immersed_daily.all.ModBlocks.FRIDGE.get().asItem());
        }

        @Override
        protected RecipeType<?> recipeType() {
            return ModRecipes.FRIDGE_FREEZING_TYPE.get();
        }

        @Override
        protected Class<FridgeFreezingRecipe> recipeClass() {
            return FridgeFreezingRecipe.class;
        }

        @Override
        protected int inputSlot() {
            return FridgeBlockEntity.FREEZING_INPUT;
        }

        @Override
        protected int outputSlot() {
            return FridgeBlockEntity.FREEZING_OUTPUT;
        }

        @Override
        protected ItemStack resultOf(FridgeFreezingRecipe recipe) {
            return recipe.getResult();
        }

        @Override
        protected Ingredient ingredientOf(FridgeFreezingRecipe recipe) {
            return recipe.getIngredient();
        }
    }

    /** 冷藏回温（fridge_tempering，1 条）。 */
    public static class Tempering extends FidFridgeCookTask<FridgeTemperingRecipe> {
        @Override
        public String getUID() {
            return "FidFridgeTemperingCookTask";
        }

        @Override
        public ItemStack getIcon() {
            return new ItemStack(com.flavor_immersed_daily.all.ModBlocks.FRIDGE.get().asItem());
        }

        @Override
        protected RecipeType<?> recipeType() {
            return ModRecipes.FRIDGE_TEMPERING_TYPE.get();
        }

        @Override
        protected Class<FridgeTemperingRecipe> recipeClass() {
            return FridgeTemperingRecipe.class;
        }

        @Override
        protected int inputSlot() {
            return FridgeBlockEntity.TEMPERING_INPUT;
        }

        @Override
        protected int outputSlot() {
            return FridgeBlockEntity.TEMPERING_OUTPUT;
        }

        @Override
        protected ItemStack resultOf(FridgeTemperingRecipe recipe) {
            return recipe.getResult();
        }

        @Override
        protected Ingredient ingredientOf(FridgeTemperingRecipe recipe) {
            return recipe.getIngredient();
        }
    }
}
