package com.sagisawa.maidrestaurantcooking.cooktask.brewing;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils;
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
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.satisfy.brewery.core.block.entity.BrewstationBlockEntity;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * 盛节精酿「酿酒台」（brewery:brewing，16 条）的女仆烹饪任务。
 * 实证：addIngredient(ItemStack) 公开投料（内部校验）、事件式酿造自走、
 * getBeer() 自消耗取酒（每次 1 份，取空自动复位 LIQUID 方块状态）。
 * 配方类型 brewery:brewing 经注册表键查（盛节用 satisfy 体系注册器）。
 */
public class BrewstationCookTask extends AbstractTimedContainerCookTask<net.satisfy.brewery.core.recipe.BrewingRecipe> {

    @Override
    public String getUID() {
        return "BrewstationCookTask";
    }

    @Override
    public ItemStack getIcon() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("brewery", "brewing_barrel"));
        return block.defaultBlockState().isAir() ? ItemStack.EMPTY : new ItemStack(block);
    }

    @Override
    protected RecipeType<?> recipeType() {
        RecipeType<?> t = BuiltInRegistries.RECIPE_TYPE.get(ResourceLocation.fromNamespaceAndPath("brewery", "brewing"));
        if (t == null) {
            throw new IllegalStateException("brewery:brewing RecipeType 未注册（目标 mod 版本不符？）");
        }
        return t;
    }

    @Override
    protected Class<net.satisfy.brewery.core.recipe.BrewingRecipe> recipeClass() {
        return net.satisfy.brewery.core.recipe.BrewingRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(net.satisfy.brewery.core.recipe.BrewingRecipe recipe, Level level) {
        return recipe.getResultItem(level.registryAccess());
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof BrewstationBlockEntity;
    }

    /** 契约（F-201）：本体 MaidStateManager.cookState 原地 addAll（0.2.9 all.java:8713），返回值必须可变——Stream.toList() 违约致 2026-10-20 崩服。 */
    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        net.satisfy.brewery.core.recipe.BrewingRecipe recipe = (net.satisfy.brewery.core.recipe.BrewingRecipe) recipeHolder.value();
        return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).map(StackPredicate::new)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        // 配料槽私有（addIngredient/removeIngredient 封装），无只读口——报空由投料幂等兜底
        return List.of();
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof BrewstationBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        BrewstationBlockEntity station = level.getBlockEntity(pos) instanceof BrewstationBlockEntity s ? s : null;
        if (station == null) {
            return;
        }
        net.satisfy.brewery.core.recipe.BrewingRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] brewstation 请求配方缺失或类型不符：{}", request.id);
            return;
        }

        // 酒成 → getBeer 自消耗取酒
        ItemStack beer = station.getBeer();
        if (beer != null && !beer.isEmpty()) {
            if (beer.getItem() == recipe.getResultItem(level.registryAccess()).getItem()) {
                ItemUtils.getItemToLivingEntity(maid, beer);
                maid.swing(InteractionHand.OFF_HAND);
                request.remain--;
            } else {
                // F-016：他单残留成品酒——交女仆不吞，但响出来便于发现混单
                ItemUtils.getItemToLivingEntity(maid, beer);
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] brewstation 取到非本单成品（可能他单残留）：{} @ {}", beer.getItem(), request.id);
            }
            return;
        }

        // 投料（addIngredient 自校验；配方配料逐个投）
        for (var ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(ingredient), true);
            if (!take.isEmpty()) {
                var result = station.addIngredient(take);
                if (result.consumesAction()) {
                    maid.swing(InteractionHand.OFF_HAND);
                } else {
                    // F-013：投料被拒（槽满/不符）——原料回包
                    ItemUtils.getItemToLivingEntity(maid, take);
                    MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 酿酒台投料被拒，原料回包：{} @ {}", request.id, pos);
                }
            }
        }
    }
}
