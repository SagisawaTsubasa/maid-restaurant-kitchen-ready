package com.sagisawa.maidrestaurantcooking.cooktask.farmcharm;

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
import net.minecraft.world.level.block.state.BlockState;
import net.satisfy.farm_and_charm.core.block.CraftingBowlBlock;
import net.satisfy.farm_and_charm.core.block.entity.CraftingBowlBlockEntity;
import net.satisfy.farm_and_charm.core.recipe.CraftingBowlRecipe;
import net.satisfy.farm_and_charm.core.registry.RecipeTypeRegistry;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * 沉浸农艺「搅拌碗」（farm_and_charm:crafting_bowl，6 条）的女仆烹饪任务。
 * 实证：0-3 配料槽 + 4 输出槽；STIRRING/STIRRED 属性驱动手摇搅拌，stirred=50 且
 * 有配料时转化（配料消耗余量、产物 setItem(4)）。女仆：投料 → 驱动 STIRRING → 取货。
 */
public class CraftingBowlCookTask extends AbstractTimedContainerCookTask<CraftingBowlRecipe> {

    private static final int OUTPUT_SLOT = 4;

    /** F-147：停摆 WARN 上次响出时间戳（30s 时间窗去重）。 */
    private volatile long stallLastWarnMs;

    @Override
    public String getUID() {
        return "CraftingBowlCookTask";
    }

    @Override
    public ItemStack getIcon() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("farm_and_charm", "crafting_bowl"));
        return block.defaultBlockState().isAir() ? ItemStack.EMPTY : new ItemStack(block);
    }

    @Override
    protected RecipeType<?> recipeType() {
        return RecipeTypeRegistry.CRAFTING_BOWL_RECIPE_TYPE.get();
    }

    @Override
    protected Class<CraftingBowlRecipe> recipeClass() {
        return CraftingBowlRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(CraftingBowlRecipe recipe, Level level) {
        return recipe.getResultItem(level.registryAccess());
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof CraftingBowlBlockEntity;
    }

    /** 契约（F-201）：本体 MaidStateManager.cookState 原地 addAll（0.2.9 all.java:8713），返回值必须可变——Stream.toList() 违约致 2026-10-20 崩服。 */
    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        CraftingBowlRecipe recipe = (CraftingBowlRecipe) recipeHolder.value();
        return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).map(StackPredicate::new)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new java.util.ArrayList<>();
        if (level.getBlockEntity(pos) instanceof CraftingBowlBlockEntity bowl) {
            for (int i = 0; i < OUTPUT_SLOT; i++) {
                if (!bowl.getItem(i).isEmpty()) {
                    ans.add(bowl.getItem(i).copy());
                }
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof CraftingBowlBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        CraftingBowlBlockEntity bowl = level.getBlockEntity(pos) instanceof CraftingBowlBlockEntity b ? b : null;
        if (bowl == null) {
            return;
        }
        CraftingBowlRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] crafting_bowl 请求配方缺失或类型不符：{}", request.id);
            return;
        }

        // 取货（槽 4；F-121：异物产出响出）
        ItemStack output = bowl.getItem(OUTPUT_SLOT);
        ItemStack bowlExpect = recipe.getResultItem(level.registryAccess());
        if (!output.isEmpty() && output.getItem() != bowlExpect.getItem()) {
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 搅拌碗输出槽为非本单产物（{}），停摆待处理：{} @ {}", output.getItem(), request.id, pos);
            return;
        }
        ItemStack expect = recipe.getResultItem(level.registryAccess());
        if (!output.isEmpty() && output.getItem() == expect.getItem()) {
            DeliveryUtil.deliverToMaidInv(maid, output.copy());
            bowl.removeItem(OUTPUT_SLOT, output.getCount());
            bowl.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
            request.remain--;
            return;
        }

        // 投料（0-3 空槽）
        boolean supplied = false;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            boolean present = false;
            for (int i = 0; i < OUTPUT_SLOT; i++) {
                if (ingredient.test(bowl.getItem(i))) {
                    present = true;
                    break;
                }
            }
            if (present) {
                continue;
            }
            ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(ingredient), true);
            if (!take.isEmpty()) {
                boolean putIn = false;
                for (int i = 0; i < OUTPUT_SLOT; i++) {
                    if (bowl.getItem(i).isEmpty()) {
                        bowl.setItem(i, take);
                        bowl.setChanged();
                        putIn = true;
                        supplied = true;
                        break;
                    }
                }
                if (!putIn) {
                    DeliveryUtil.deliverToMaidInv(maid, take);
                    MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] crafting_bowl 满槽放料失败，原料已回包：{}", request.id);
                }
            }
        }

        // 搅拌驱动：STIRRING 静止且有配料 → 补搅
        // F-139：碗内存在既非本单配料也非本单产物的遗留物时不驱动（防搅异物错计）
        BlockState state = level.getBlockState(pos);
        if (state.getValue(CraftingBowlBlock.STIRRING) == 0 && bowl.getItem(OUTPUT_SLOT).isEmpty()) {
            for (int i = 0; i < OUTPUT_SLOT; i++) {
                ItemStack scan = bowl.getItem(i);
                if (!scan.isEmpty() && scan.getItem() != bowlExpect.getItem()
                        && recipe.getIngredients().stream().noneMatch(ing -> ing.test(scan))) {
                    if (System.currentTimeMillis() - stallLastWarnMs > 30_000L) {
                        stallLastWarnMs = System.currentTimeMillis();
                        MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 搅拌碗内存在异物（{}），不驱动：{} @ {}", scan.getItem(), request.id, pos);
                    }
                    return;
                }
            }
            boolean anyInput = false;
            for (int i = 0; i < OUTPUT_SLOT; i++) {
                if (!bowl.getItem(i).isEmpty()) {
                    anyInput = true;
                    break;
                }
            }
            if (anyInput) {
                level.setBlock(pos, state.setValue(CraftingBowlBlock.STIRRING, 8), 3);
            }
        }
        if (supplied) {
            maid.swing(InteractionHand.OFF_HAND);
        }
    }
}
