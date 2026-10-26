package com.sagisawa.maidrestaurantcooking.cooktask.bakeries;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.sagisawa.maidrestaurantcooking.utils.DeliveryUtil;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.ItemHandlerUtils;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import com.renyigesai.bakeries.common.blocks.fermentation_box.FermentationBoxBlockEntity;
import com.renyigesai.bakeries.common.init.BakeriesRecipeTypes;
import com.renyigesai.bakeries.common.recipe.FermentationBoxRecipe;
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

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * 烘焙坊「醒发箱」（bakeries:fermentation_box，4 条）的女仆烹饪任务。
 *
 * <p>实证（2026-10-17）：FermentationBoxBlockEntity（BaseContainerBlockEntity，6 格，
 * Container API 公开）；tick 自动醒发——要求 fermentationMaxTime≥430（箱内温度累积，
 * refreshTemperature 自理）；每槽计时到顶转 getAllResults().getFirst()，完美窗口
 * 附加 PERFECT_FERMENTATION 组件。女仆流程：投料 → 等转化 → 取走（物品匹配用
 * isSameItem：完美醒发变体组件不同也能取）。
 */
public class FermentationBoxCookTask extends AbstractTimedContainerCookTask<FermentationBoxRecipe> {

    @Override
    public String getUID() {
        return "FermentationBoxCookTask";
    }

    @Override
    public ItemStack getIcon() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("bakeries", "fermentation_box"));
        return block.defaultBlockState().isAir() ? ItemStack.EMPTY : new ItemStack(block);
    }

    @Override
    protected RecipeType<?> recipeType() {
        return BakeriesRecipeTypes.FERMENTATION_BOX_TYPE.get();
    }

    @Override
    protected Class<FermentationBoxRecipe> recipeClass() {
        return FermentationBoxRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(FermentationBoxRecipe recipe, Level level) {
        return recipe.getAllResults().isEmpty() ? ItemStack.EMPTY : recipe.getAllResults().getFirst();
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof FermentationBoxBlockEntity;
    }

    /** 契约（F-201）：本体 MaidStateManager.cookState 原地 addAll（0.2.9 all.java:8713），返回值必须可变——Stream.toList() 违约致 2026-10-20 崩服。 */
    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        // F-282 同族：非本类配方返回空表（裸强转在取料面 CCE；可变契约 F-201）
        if (!(recipeHolder.value() instanceof FermentationBoxRecipe recipe)) {
            return new java.util.ArrayList<>();
        }
        return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).map(StackPredicate::new)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new java.util.ArrayList<>();
        if (level.getBlockEntity(pos) instanceof FermentationBoxBlockEntity box) {
            for (int i = 0; i < box.getContainerSize(); i++) {
                if (!box.getItem(i).isEmpty()) {
                    ans.add(box.getItem(i).copy());
                }
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FermentationBoxBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        FermentationBoxBlockEntity box = level.getBlockEntity(pos) instanceof FermentationBoxBlockEntity b ? b : null;
        if (box == null) {
            return;
        }
        FermentationBoxRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] fermentation_box 请求配方缺失或类型不符：{}", request.id);
            return;
        }
        ItemStack result = recipeResult(recipe, level);
        if (result.isEmpty()) {
            return;
        }
        Ingredient ingredient = recipe.getIngredients().isEmpty() ? Ingredient.EMPTY : recipe.getIngredients().get(0);

        // F-121+F-123：先扫异物（非产物且非本单原料 → 响出停摆）
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack scan = box.getItem(i);
            if (!scan.isEmpty() && scan.getItem() != result.getItem() && !ingredient.test(scan)) {
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 醒发箱槽位存在非本单产物（{}），停摆待处理：{} @ {}", scan.getItem(), request.id, pos);
                return;
            }
        }

        // 产物取出（isSameItem：完美醒发变体带附加组件，组件级比较会漏）
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack stored = box.getItem(i);
            if (!stored.isEmpty() && stored.getItem() == result.getItem()) {
                DeliveryUtil.deliverToMaidInv(maid, stored.copy());
                box.removeItem(i, stored.getCount());
                box.setChanged();
                maid.swing(InteractionHand.OFF_HAND);
                request.remain--;
                return;
            }
        }

        // 已有原料醒发中 → 等
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack stored = box.getItem(i);
            if (!stored.isEmpty() && ingredient.test(stored)) {
                return;
            }
        }

        // 投料（先探空槽再抽，F-002 防吞）
        boolean hasEmpty = false;
        for (int i = 0; i < box.getContainerSize(); i++) {
            if (box.getItem(i).isEmpty()) {
                hasEmpty = true;
                break;
            }
        }
        if (!hasEmpty) {
            return;
        }
        ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(ingredient), true);
        if (take.isEmpty()) {
            return;
        }
        for (int i = 0; i < box.getContainerSize(); i++) {
            if (box.getItem(i).isEmpty()) {
                box.setItem(i, take);
                box.setChanged();
                maid.swing(InteractionHand.OFF_HAND);
                return;
            }
        }
        DeliveryUtil.deliverToMaidInv(maid, take);
        MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] fermentation_box 满槽放料失败，原料已回包：{}", request.id);
    }
}
