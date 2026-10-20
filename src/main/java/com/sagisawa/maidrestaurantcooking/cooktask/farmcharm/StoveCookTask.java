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
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.satisfy.farm_and_charm.core.block.entity.StoveBlockEntity;
import net.satisfy.farm_and_charm.core.recipe.StoveRecipe;
import net.satisfy.farm_and_charm.core.registry.RecipeTypeRegistry;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * 沉浸农艺「炉灶」（farm_and_charm:stove，9 条）的女仆烹饪任务。
 * 实证：配料槽（getIngredientSlots）+ 燃料槽 4（getTotalBurnTime）+ 输出槽（getOutputSlot），
 * tick 自燃自煮。女仆：投配料+补燃料 → 等熟 → 取货。
 */
public class StoveCookTask extends AbstractTimedContainerCookTask<StoveRecipe> {

    /** F-142：无燃料 WARN 上次响出时间戳（30s 时间窗去重）。 */
    private volatile long noFuelLastWarnMs;

    @Override
    public String getUID() {
        return "FarmcharmStoveCookTask";
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(net.minecraft.world.level.block.Blocks.FURNACE);
    }

    @Override
    protected RecipeType<?> recipeType() {
        return RecipeTypeRegistry.STOVE_RECIPE_TYPE.get();
    }

    @Override
    protected Class<StoveRecipe> recipeClass() {
        return StoveRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(StoveRecipe recipe, Level level) {
        return recipe.getResultItem(level.registryAccess());
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof StoveBlockEntity;
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        StoveRecipe recipe = (StoveRecipe) recipeHolder.value();
        // F-142：燃料进取料单（女仆会主动去取的物品语义，对照 TeaKettle 水/燃料先例）
        /** 契约（F-201）：本体 MaidStateManager.cookState 原地 addAll（0.2.9 all.java:8713），返回值必须可变——Stream.toList() 违约致 2026-10-20 崩服。 */
        java.util.List<StackPredicate> ans = recipe.getIngredients().stream()
                .filter(i -> !i.isEmpty()).map(StackPredicate::new)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        ans.add(StackPredicate.of(AbstractFurnaceFuelCheck::isFuel));
        return ans;
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new java.util.ArrayList<>();
        if (level.getBlockEntity(pos) instanceof StoveBlockEntity stove) {
            for (int slot : stove.getIngredientSlots()) {
                if (!stove.getItem(slot).isEmpty()) {
                    ans.add(stove.getItem(slot).copy());
                }
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof StoveBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        StoveBlockEntity stove = level.getBlockEntity(pos) instanceof StoveBlockEntity s ? s : null;
        if (stove == null) {
            return;
        }
        StoveRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] stove 请求配方缺失或类型不符：{}", request.id);
            return;
        }

        // 取货（F-121：异物产出响出）
        ItemStack output = stove.getItem(stove.getOutputSlot());
        ItemStack stoveExpect = recipe.getResultItem(level.registryAccess());
        if (!output.isEmpty() && output.getItem() != stoveExpect.getItem()) {
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 炉灶输出槽为非本单产物（{}），停摆待处理：{} @ {}", output.getItem(), request.id, pos);
            return;
        }
        ItemStack expect = recipe.getResultItem(level.registryAccess());
        if (!output.isEmpty() && output.getItem() == expect.getItem()) {
            ItemUtils.getItemToLivingEntity(maid, output.copy());
            stove.removeItem(stove.getOutputSlot(), output.getCount());
            stove.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
            request.remain--;
            return;
        }

        // 补燃料（F-142：无燃料 30s 窗 WARN 防订单静默永挂；燃料槽异物对称 F-115 响出）
        if (stove.getItem(4).isEmpty()) {
            ItemStack fuel = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                    StackPredicate.of(AbstractFurnaceFuelCheck::isFuel), true);
            if (!fuel.isEmpty()) {
                stove.setItem(4, fuel);
                stove.setChanged();
                maid.swing(InteractionHand.OFF_HAND);
            } else {
                // F-152：配料已在位（确实在等点火）才响无燃料 WARN；空灶等待属正常巡检不刷屏
                boolean ingredientPresent = false;
                for (int slot : stove.getIngredientSlots()) {
                    if (!stove.getItem(slot).isEmpty()) {
                        ingredientPresent = true;
                        break;
                    }
                }
                if (ingredientPresent && System.currentTimeMillis() - noFuelLastWarnMs > 30_000L) {
                    noFuelLastWarnMs = System.currentTimeMillis();
                    MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 农艺炉灶配料在位但女仆无燃料，订单挂起：{} @ {}", request.id, pos);
                }
            }
        } else if (!AbstractFurnaceFuelCheck.isFuel(stove.getItem(4))) {
            // F-150：异物占燃料槽 → 停摆（return），WARN 带窗
            if (System.currentTimeMillis() - noFuelLastWarnMs > 30_000L) {
                noFuelLastWarnMs = System.currentTimeMillis();
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 农艺炉灶燃料槽为非燃料异物（{}），停摆待处理：{} @ {}", stove.getItem(4).getItem(), request.id, pos);
            }
            return;
        }

        // 投配料
        for (int slot : stove.getIngredientSlots()) {
            if (!stove.getItem(slot).isEmpty()) {
                continue;
            }
            for (var ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty() || ingredient.test(stove.getItem(slot))) {
                    continue;
                }
                boolean present = false;
                for (int s2 : stove.getIngredientSlots()) {
                    if (ingredient.test(stove.getItem(s2))) {
                        present = true;
                        break;
                    }
                }
                if (present) {
                    continue;
                }
                ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(ingredient), true);
                if (!take.isEmpty()) {
                    stove.setItem(slot, take);
                    stove.setChanged();
                    maid.swing(InteractionHand.OFF_HAND);
                }
                break;
            }
        }
    }

    /** 燃料判定：借用原版炉燃料表（农艺炉灶燃料与之重合；差异由真机验证暴露）。 */
    private static final class AbstractFurnaceFuelCheck {
        static boolean isFuel(ItemStack stack) {
            return net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.isFuel(stack);
        }
    }
}
