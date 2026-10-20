package com.sagisawa.maidrestaurantcooking.cooktask.farmcharm;

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
import java.util.List;
import java.util.function.Predicate;

/**
 * 沉浸农艺「加热锅族」基类（炖锅 pot_cooking 10 条 / 烘烤器 roaster 9 条）。
 * 实证：ImplementedInventory + isBeingBurned（下方热源）+ tick 自煮，产物匹配走
 * 结果物品跨槽检测（输出槽位无需硬编码）。requiresLearning 的解锁门控由 BE 自逻辑把关。
 */
public abstract class FarmcharmHeatedCookTask<R extends Recipe<?>> extends AbstractTimedContainerCookTask<R> {

    protected abstract List<Ingredient> ingredientsOf(R recipe);

    /** 契约（F-201）：本体 MaidStateManager.cookState 原地 addAll（0.2.9 all.java:8713），返回值必须可变——Stream.toList() 违约致 2026-10-20 崩服。 */
    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends Recipe<?>> recipeHolder, Level level) {
        return ingredientsOf(recipeClass().cast(recipeHolder.value())).stream()
                .filter(i -> !i.isEmpty()).map(StackPredicate::new)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.Container container)) {
            return;
        }
        var be = (net.minecraft.world.level.block.entity.BlockEntity) container;
        R recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] 加热锅请求配方缺失或类型不符：{}（{}）", request.id, getUID());
            return;
        }
        ItemStack expect = recipe.getResultItem(level.registryAccess());
        // F-136：一次取配料清单，扫描白名单与投料共用（避免逐 tick 重复构建）
        List<Ingredient> ingredients = ingredientsOf(recipe);

        // F-121+F-123：先扫异物——非空且非本单产物、也非本单配料的槽 → 响出停摆
        List<Ingredient> scanIngredients = ingredients;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack scan = container.getItem(i);
            if (!scan.isEmpty() && scan.getItem() != expect.getItem()
                    && scanIngredients.stream().noneMatch(ing -> ing.test(scan))) {
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] {} 槽位存在非本单产物（{}），停摆待处理：{} @ {}", getUID(), scan.getItem(), request.id, pos);
                return;
            }
        }

        // 产物取出（跨槽匹配）
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stored = container.getItem(i);
            if (!stored.isEmpty() && stored.getItem() == expect.getItem()) {
                ItemUtils.getItemToLivingEntity(maid, stored.copy());
                container.removeItem(i, stored.getCount());
                be.setChanged();
                maid.swing(InteractionHand.OFF_HAND);
                request.remain--;
                return;
            }
        }

        // 投料：空槽依配方配料补齐
        boolean supplied = false;
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;
            }
            boolean present = false;
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (ingredient.test(container.getItem(i))) {
                    present = true;
                    break;
                }
            }
            if (present) {
                continue;
            }
            ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(ingredient), true);
            if (take.isEmpty()) {
                continue;
            }
            boolean putIn = false;
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (container.getItem(i).isEmpty()) {
                    container.setItem(i, take);
                    be.setChanged();
                    putIn = true;
                    break;
                }
            }
            if (putIn) {
                maid.swing(InteractionHand.OFF_HAND);
            } else {
                ItemUtils.getItemToLivingEntity(maid, take);
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] {} 满槽放料失败，原料已回包：{}", getUID(), request.id);
            }
        }
    }

    /** 炖锅（pot_cooking）。 */
    public static class CookingPot extends FarmcharmHeatedCookTask<net.satisfy.farm_and_charm.core.recipe.CookingPotRecipe> {
        @Override
        public String getUID() {
            return "FarmcharmCookingPotCookTask";
        }

        @Override
        public ItemStack getIcon() {
            return new ItemStack(net.minecraft.world.item.Items.CAULDRON);
        }

        @Override
        protected RecipeType<?> recipeType() {
            return net.satisfy.farm_and_charm.core.registry.RecipeTypeRegistry.COOKING_POT_RECIPE_TYPE.get();
        }

        @Override
        protected Class<net.satisfy.farm_and_charm.core.recipe.CookingPotRecipe> recipeClass() {
            return net.satisfy.farm_and_charm.core.recipe.CookingPotRecipe.class;
        }

        @Override
        protected ItemStack recipeResult(net.satisfy.farm_and_charm.core.recipe.CookingPotRecipe recipe, Level level) {
            return recipe.getResultItem(level.registryAccess());
        }

        @Override
        protected List<Ingredient> ingredientsOf(net.satisfy.farm_and_charm.core.recipe.CookingPotRecipe recipe) {
            return recipe.getIngredients();
        }

        @Override
        protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
            return pos -> level.getBlockEntity(pos) instanceof net.satisfy.farm_and_charm.core.block.entity.CookingPotBlockEntity;
        }

        @Override
        public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
            return level.getBlockEntity(pos) instanceof net.satisfy.farm_and_charm.core.block.entity.CookingPotBlockEntity;
        }

        @Override
        public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
            return readInputs(level, pos);
        }
    }

    /** 烘烤器（roaster）。 */
    public static class Roaster extends FarmcharmHeatedCookTask<net.satisfy.farm_and_charm.core.recipe.RoasterRecipe> {
        @Override
        public String getUID() {
            return "FarmcharmRoasterCookTask";
        }

        @Override
        public ItemStack getIcon() {
            return new ItemStack(net.minecraft.world.item.Items.CAMPFIRE);
        }

        @Override
        protected RecipeType<?> recipeType() {
            return net.satisfy.farm_and_charm.core.registry.RecipeTypeRegistry.ROASTER_RECIPE_TYPE.get();
        }

        @Override
        protected Class<net.satisfy.farm_and_charm.core.recipe.RoasterRecipe> recipeClass() {
            return net.satisfy.farm_and_charm.core.recipe.RoasterRecipe.class;
        }

        @Override
        protected ItemStack recipeResult(net.satisfy.farm_and_charm.core.recipe.RoasterRecipe recipe, Level level) {
            return recipe.getResultItem(level.registryAccess());
        }

        @Override
        protected List<Ingredient> ingredientsOf(net.satisfy.farm_and_charm.core.recipe.RoasterRecipe recipe) {
            return recipe.getIngredients();
        }

        @Override
        protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
            return pos -> level.getBlockEntity(pos) instanceof net.satisfy.farm_and_charm.core.block.entity.RoasterBlockEntity;
        }

        @Override
        public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
            return level.getBlockEntity(pos) instanceof net.satisfy.farm_and_charm.core.block.entity.RoasterBlockEntity;
        }

        @Override
        public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
            return readInputs(level, pos);
        }
    }

    private static List<ItemStack> readInputs(Level level, BlockPos pos) {
        List<ItemStack> ans = new java.util.ArrayList<>();
        if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.BlockEntity be
                && be instanceof net.minecraft.world.Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (!container.getItem(i).isEmpty()) {
                    ans.add(container.getItem(i).copy());
                }
            }
        }
        return ans;
    }
}
