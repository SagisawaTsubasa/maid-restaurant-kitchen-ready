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
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.satisfy.vinery.core.block.entity.ApplePressBlockEntity;
import net.satisfy.vinery.core.recipe.ApplePressMashingRecipe;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * 葡园酒香「苹果压榨机」（vinery:apple_mashing 2 / apple_fermenting 2，双阶段自链）的女仆烹饪任务。
 * 实证：ImplementedInventory，槽 0=压榨输入→自进发酵槽→产出；tick 全自走。
 * 女仆：投料到空槽 → 等两段自链 → 产物跨槽匹配取走。
 */
public class ApplePressCookTask extends AbstractTimedContainerCookTask<ApplePressMashingRecipe> {

    @Override
    public String getUID() {
        return "ApplePressCookTask";
    }

    @Override
    public ItemStack getIcon() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("vinery", "apple_press"));
        return block.defaultBlockState().isAir() ? ItemStack.EMPTY : new ItemStack(block);
    }

    @Override
    protected RecipeType<?> recipeType() {
        return net.satisfy.vinery.core.registry.RecipeTypesRegistry.APPLE_PRESS_MASHING_RECIPE_TYPE.get();
    }

    @Override
    protected Class<ApplePressMashingRecipe> recipeClass() {
        return ApplePressMashingRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(ApplePressMashingRecipe recipe, Level level) {
        return recipe.getResultItem(level.registryAccess());
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof ApplePressBlockEntity;
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        ApplePressMashingRecipe recipe = (ApplePressMashingRecipe) recipeHolder.value();
        return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).map(StackPredicate::new).toList();
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new java.util.ArrayList<>();
        if (level.getBlockEntity(pos) instanceof ApplePressBlockEntity press) {
            for (int i = 0; i < press.getContainerSize(); i++) {
                if (!press.getItem(i).isEmpty()) {
                    ans.add(press.getItem(i).copy());
                }
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ApplePressBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        ApplePressBlockEntity press = level.getBlockEntity(pos) instanceof ApplePressBlockEntity p ? p : null;
        if (press == null) {
            return;
        }
        ApplePressMashingRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[xia-de-chu-fang] apple_press 请求配方缺失或类型不符：{}", request.id);
            return;
        }
        ItemStack expect = recipe.getResultItem(level.registryAccess());

        // F-121+F-123：先扫异物（非产物且非本单原料 → 响出停摆）
        for (int i = 0; i < press.getContainerSize(); i++) {
            ItemStack scan = press.getItem(i);
            if (!scan.isEmpty() && scan.getItem() != expect.getItem()
                    && recipe.getIngredients().stream().noneMatch(ing -> ing.test(scan))) {
                MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] 压榨机槽位存在非本单产物（{}），停摆待处理：{} @ {}", scan.getItem(), request.id, pos);
                return;
            }
        }

        // 两段自链，产物跨槽匹配取走
        for (int i = 0; i < press.getContainerSize(); i++) {
            ItemStack stored = press.getItem(i);
            if (!stored.isEmpty() && stored.getItem() == expect.getItem()) {
                ItemUtils.getItemToLivingEntity(maid, stored.copy());
                press.removeItem(i, stored.getCount());
                press.setChanged();
                maid.swing(InteractionHand.OFF_HAND);
                request.remain--;
                return;
            }
        }

        // 投料到空槽（不含产物槽防回吞——槽语义由 BE 自链管理）
        for (var ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            boolean present = false;
            for (int i = 0; i < press.getContainerSize(); i++) {
                if (ingredient.test(press.getItem(i))) {
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
                for (int i = 0; i < press.getContainerSize(); i++) {
                    if (press.getItem(i).isEmpty()) {
                        press.setItem(i, take);
                        press.setChanged();
                        putIn = true;
                        maid.swing(InteractionHand.OFF_HAND);
                        break;
                    }
                }
                if (!putIn) {
                    ItemUtils.getItemToLivingEntity(maid, take);
                    MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] apple_press 满槽放料失败，原料已回包：{}", request.id);
                }
            }
        }
    }
}
