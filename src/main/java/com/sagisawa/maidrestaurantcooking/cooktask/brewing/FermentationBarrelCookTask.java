package com.sagisawa.maidrestaurantcooking.cooktask.brewing;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.sagisawa.maidrestaurantcooking.utils.DeliveryUtil;
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
import net.satisfy.vinery.core.block.entity.FermentationBarrelBlockEntity;
import net.satisfy.vinery.core.recipe.FermentationBarrelRecipe;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * 葡园酒香「发酵桶」（vinery:wine_fermentation，26 条）的女仆烹饪任务。
 * 实证：槽 0=葡萄汁输入、4=酒瓶、5=输出；液位/果汁类型 BE 自管；tick 自发酵。
 * 女仆：投汁+投瓶 → 等 → 取酒（瓶需求由配方 isWineBottleRequired 判定）。
 */
public class FermentationBarrelCookTask extends AbstractTimedContainerCookTask<FermentationBarrelRecipe> {

    /** F-147：停摆 WARN 上次响出时间戳（30s 时间窗去重）。 */
    private volatile long stallLastWarnMs;

    @Override
    public String getUID() {
        return "FermentationBarrelCookTask";
    }

    @Override
    public ItemStack getIcon() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("vinery", "fermentation_barrel"));
        return block.defaultBlockState().isAir() ? ItemStack.EMPTY : new ItemStack(block);
    }

    @Override
    protected RecipeType<?> recipeType() {
        return FermentationBarrelRecipe.Type;
    }

    @Override
    protected Class<FermentationBarrelRecipe> recipeClass() {
        return FermentationBarrelRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(FermentationBarrelRecipe recipe, Level level) {
        return recipe.getResultItem(level.registryAccess());
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof FermentationBarrelBlockEntity;
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        FermentationBarrelRecipe recipe = (FermentationBarrelRecipe) recipeHolder.value();
        // F-148：酒瓶是必需辅料（isWineBottleRequired），进取料单——女仆才会主动去仓库取瓶
        /** 契约（F-201）：本体 MaidStateManager.cookState 原地 addAll（0.2.9 all.java:8713），返回值必须可变——Stream.toList() 违约致 2026-10-20 崩服。 */
        java.util.List<StackPredicate> ans = recipe.getIngredients().stream()
                .filter(i -> !i.isEmpty()).map(StackPredicate::new)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        if (recipe.isWineBottleRequired()) {
            ans.add(StackPredicate.of(s -> s.getItem()
                    == net.satisfy.vinery.core.registry.ObjectRegistry.WINE_BOTTLE.get()));
        }
        return ans;
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new java.util.ArrayList<>();
        if (level.getBlockEntity(pos) instanceof FermentationBarrelBlockEntity barrel) {
            for (int i = 0; i < barrel.getContainerSize(); i++) {
                if (!barrel.getItem(i).isEmpty()) {
                    ans.add(barrel.getItem(i).copy());
                }
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FermentationBarrelBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        FermentationBarrelBlockEntity barrel = level.getBlockEntity(pos) instanceof FermentationBarrelBlockEntity b ? b : null;
        if (barrel == null) {
            return;
        }
        FermentationBarrelRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] fermentation_barrel 请求配方缺失或类型不符：{}", request.id);
            return;
        }
        ItemStack expect = recipe.getResultItem(level.registryAccess());

        // 取酒（输出槽 5；F-121：异物产出响出）
        ItemStack output = barrel.getItem(FermentationBarrelBlockEntity.OUTPUT_SLOT_GENERAL);
        ItemStack barrelExpect = recipe.getResultItem(level.registryAccess());
        if (!output.isEmpty() && output.getItem() != barrelExpect.getItem()) {
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 发酵桶输出槽为非本单产物（{}），停摆待处理：{} @ {}", output.getItem(), request.id, pos);
            return;
        }
        if (!output.isEmpty() && output.getItem() == expect.getItem()) {
            DeliveryUtil.deliverToMaidInv(maid, output.copy());
            barrel.removeItem(FermentationBarrelBlockEntity.OUTPUT_SLOT_GENERAL, output.getCount());
            barrel.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
            request.remain--;
            return;
        }

        // 投汁（槽 0）与瓶（槽 4，配方要求时）；F-138/F-149：槽 0 异物 → 停摆（return 无条件），WARN 带窗
        if (!barrel.getItem(0).isEmpty()
                && recipe.getIngredients().stream().noneMatch(ing -> ing.test(barrel.getItem(0)))) {
            if (System.currentTimeMillis() - stallLastWarnMs > 30_000L) {
                stallLastWarnMs = System.currentTimeMillis();
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 发酵桶汁槽为非本单原料（{}），停摆待处理：{} @ {}", barrel.getItem(0).getItem(), request.id, pos);
            }
            return;
        }
        for (var ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty() || ingredient.test(barrel.getItem(0))) {
                continue;
            }
            if (barrel.getItem(0).isEmpty()) {
                ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(ingredient), true);
                if (!take.isEmpty()) {
                    barrel.setItem(0, take);
                    barrel.setChanged();
                    maid.swing(InteractionHand.OFF_HAND);
                }
                break;
            }
        }
        // F-143/F-149：酒瓶槽异物 → 停摆（return 无条件），WARN 带窗；缺瓶且女仆无瓶 → WARN 带窗（F-148 闭环）
        ItemStack bottleSlot = barrel.getItem(FermentationBarrelBlockEntity.WINE_BOTTLE_SLOT);
        if (!bottleSlot.isEmpty() && recipe.isWineBottleRequired()
                && bottleSlot.getItem() != net.satisfy.vinery.core.registry.ObjectRegistry.WINE_BOTTLE.get()) {
            if (System.currentTimeMillis() - stallLastWarnMs > 30_000L) {
                stallLastWarnMs = System.currentTimeMillis();
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 发酵桶酒瓶槽为非酒瓶物品（{}），停摆待处理：{} @ {}", bottleSlot.getItem(), request.id, pos);
            }
            return;
        }
        if (recipe.isWineBottleRequired() && barrel.getItem(FermentationBarrelBlockEntity.WINE_BOTTLE_SLOT).isEmpty()) {
            // 酒瓶槽：vinery:wine_bottle（ObjectRegistry.WINE_BOTTLE，2026-10-17 实测注册名）——从女仆包抽
            ItemStack bottle = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                    StackPredicate.of(s -> s.getItem() == net.satisfy.vinery.core.registry.ObjectRegistry.WINE_BOTTLE.get()), true);
            if (!bottle.isEmpty()) {
                barrel.setItem(FermentationBarrelBlockEntity.WINE_BOTTLE_SLOT, bottle);
                barrel.setChanged();
                maid.swing(InteractionHand.OFF_HAND);
            } else if (System.currentTimeMillis() - stallLastWarnMs > 30_000L) {
                stallLastWarnMs = System.currentTimeMillis();
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 发酵桶等酒瓶但女仆无瓶，订单挂起：{} @ {}", request.id, pos);
            }
        }
    }
}
