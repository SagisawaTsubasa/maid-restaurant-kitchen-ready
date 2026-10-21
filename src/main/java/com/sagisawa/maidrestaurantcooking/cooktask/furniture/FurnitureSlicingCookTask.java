package com.sagisawa.maidrestaurantcooking.cooktask.furniture;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.sagisawa.maidrestaurantcooking.utils.DeliveryUtil;
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

    /** F-144：砧板被占 WARN 上次响出时间戳（30s 时间窗去重）。 */
    private volatile long busyLastWarnMs;

    /** F-160：站点键控退避（任务实例跨维度共享，退避必须按 维度+坐标 区分；过期条目顺手清理）。 */
    private record StationKey(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim,
                              net.minecraft.core.BlockPos pos) {
    }

    private final java.util.concurrent.ConcurrentHashMap<StationKey, Long> backoffUntil = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean inBackoff(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        StationKey key = new StationKey(level.dimension(), pos.immutable());
        Long until = backoffUntil.get(key);
        if (until != null && System.currentTimeMillis() < until) {
            return true;
        }
        if (backoffUntil.size() > 64) {
            long now = System.currentTimeMillis();
            backoffUntil.entrySet().removeIf(e -> e.getValue() < now);
        }
        return false;
    }

    private void setBackoff(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos, long durationMs) {
        backoffUntil.put(new StationKey(level.dimension(), pos.immutable()),
                System.currentTimeMillis() + durationMs);
    }



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

    /** 契约（F-201）：本体 MaidStateManager.cookState 原地 addAll（0.2.9 all.java:8713），返回值必须可变——Stream.toList() 违约致 2026-10-20 崩服。 */
    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        CuttingBoardSlicingRecipe recipe = (CuttingBoardSlicingRecipe) recipeHolder.value();
        List<StackPredicate> ans = new java.util.ArrayList<>();
        ans.add(StackPredicate.of(recipe.getIngredient()));
        return ans;
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
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] furniture_slicing 请求配方缺失或类型不符：{}", request.id);
            return;
        }
        // F-153/F-160：被占退避窗内直接返回——不抽料不放板，杜绝每 tick 抽还抖动（按站点键控）
        if (inBackoff(level, pos)) {
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
            // 放上即切（一刀一片，产物落地）；F-161：切片失败原料留板（在制态），WARN 记录
            boolean sliced = board.sliceItem(level, true);
            if (!sliced) {
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 家具砧板切片未成（原料留板待重试）：{} @ {}", request.id, pos);
            }
            if (sliced) {
                maid.swing(InteractionHand.OFF_HAND);
                // F-012：产物落地依赖女仆拾取 AI（真机 sweep C3 验证）
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 家具砧板产物已落地待拾取：{} @ {}", request.id, pos);
                request.remain--;
            }
        } else {
            // F-144/F-153：板被占（他单在切）——还料+WARN（30s 窗）+设 30s 退避（窗内 cookTick 直接返回）
            DeliveryUtil.deliverToMaidInv(maid, take);
            setBackoff(level, pos, 30_000L);
            if (System.currentTimeMillis() - busyLastWarnMs > 30_000L) {
                busyLastWarnMs = System.currentTimeMillis();
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 家具砧板被占，原料回包待重试：{} @ {}", request.id, pos);
        }
        }
    }
}
