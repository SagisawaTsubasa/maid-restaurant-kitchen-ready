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
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.satisfy.farm_and_charm.core.block.MincerBlock;
import net.satisfy.farm_and_charm.core.block.entity.MincerBlockEntity;
import net.satisfy.farm_and_charm.core.recipe.MincerRecipe;
import net.satisfy.farm_and_charm.core.registry.RecipeTypeRegistry;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * 沉浸农艺「绞肉机」（farm_and_charm:mincer，59 条）的女仆烹饪任务。
 *
 * <p>实证：手摇驱动——BE tick 依 CRANK 方块属性自减，cranked 计满 20 转化 1 输入；
 * 女仆复制摇柄：投料（slot0，CRANK==0 时）→ CRANK 静止且输入在位时一次性补 8 →
 * BE tick 自行推进（每 tick --crank，cranked++ 至 20 转化）→ 输出 slot1 取走。
 * 刀盘分 recipe_type 字符串（MincerRecipe.getRecipeType），全部并入本任务。
 */
public class MincerCookTask extends AbstractTimedContainerCookTask<MincerRecipe> {

    @Override
    public String getUID() {
        return "MincerCookTask";
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(net.minecraft.world.level.block.Blocks.GRINDSTONE);
    }

    @Override
    protected RecipeType<?> recipeType() {
        return RecipeTypeRegistry.MINCER_RECIPE_TYPE.get();
    }

    @Override
    protected Class<MincerRecipe> recipeClass() {
        return MincerRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(MincerRecipe recipe, Level level) {
        return recipe.getResultItem(level.registryAccess());
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof MincerBlockEntity;
    }

    /** 契约（F-201）：本体 MaidStateManager.cookState 原地 addAll（0.2.9 all.java:8713），返回值必须可变——Stream.toList() 违约致 2026-10-20 崩服。 */
    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        MincerRecipe recipe = (MincerRecipe) recipeHolder.value();
        List<StackPredicate> ans = new java.util.ArrayList<>();
        ans.add(StackPredicate.of(recipe.getInput()));
        return ans;
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new java.util.ArrayList<>();
        if (level.getBlockEntity(pos) instanceof MincerBlockEntity mincer && !mincer.getItem(0).isEmpty()) {
            ans.add(mincer.getItem(0).copy());
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof MincerBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        MincerBlockEntity mincer = level.getBlockEntity(pos) instanceof MincerBlockEntity m ? m : null;
        if (mincer == null) {
            return;
        }
        MincerRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] mincer 请求配方缺失或类型不符：{}", request.id);
            return;
        }

        // 输出槽取货（F-121：异物产出响出，防静默停摆+继续投料）
        ItemStack output = mincer.getItem(1);
        ItemStack mincerExpect = recipe.getResultItem(level.registryAccess());
        if (!output.isEmpty() && output.getItem() != mincerExpect.getItem()) {
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 绞肉机输出槽为非本单产物（{}），停摆待处理：{} @ {}", output.getItem(), request.id, pos);
            return;
        }
        if (!output.isEmpty() && output.getItem() == recipe.getResultItem(level.registryAccess()).getItem()) {
            ItemUtils.getItemToLivingEntity(maid, output.copy());
            mincer.removeItem(1, output.getCount());
            mincer.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
            request.remain--;
            return;
        }

        BlockState state = level.getBlockState(pos);
        // 投料（摇柄静止时）
        if (state.getValue(MincerBlock.CRANK) == 0 && mincer.getItem(0).isEmpty()) {
            ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(recipe.getInput()), true);
            if (!take.isEmpty()) {
                mincer.setItem(0, take);
                mincer.setChanged();
                maid.swing(InteractionHand.OFF_HAND);
            }
            return;
        }
        // 摇柄：CRANK 有余量即自动转；静止且输入在位则补摇
        // F-112：槽内物料非本单输入时不动摇柄（防摇异物耗材堵槽）
        if (state.getValue(MincerBlock.CRANK) == 0 && !mincer.getItem(0).isEmpty()) {
            if (!recipe.getInput().test(mincer.getItem(0))) {
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 绞肉机槽内为非本单物料（{}），不动摇柄：{} @ {}", mincer.getItem(0).getItem(), request.id, pos);
                return;
            }
            level.setBlock(pos, state.setValue(MincerBlock.CRANK, 8), 3);
        }
    }
}
