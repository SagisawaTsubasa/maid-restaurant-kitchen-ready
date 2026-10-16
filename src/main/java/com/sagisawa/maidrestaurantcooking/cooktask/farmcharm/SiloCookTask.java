package com.sagisawa.maidrestaurantcooking.cooktask.farmcharm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.ItemHandlerUtils;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import com.sagisawa.maidrestaurantcooking.cooktask.timed.AbstractTimedContainerCookTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.satisfy.farm_and_charm.core.block.entity.SiloBlockEntity;
import net.satisfy.farm_and_charm.core.recipe.SiloRecipe;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * 沉浸农艺「木制筒仓」（farm_and_charm:drying，15 条）的女仆烹饪任务。
 *
 * <p>实证（2026-10-17）：SiloBlockEntity 为多联方块（controller 模式，容量 81×2）——
 * 0..cap-1 鲜料槽、81..81+cap 干料槽；controller 的 dry() 每 200gt 自转化。
 * 女仆流程：解析 controller BE → tryAddItem 投料 / tryRemoveItem 取货。
 * 配方类型键 farm_and_charm:drying 经注册表查得（farmcharm 用 architectury
 * RegistrySupplier，避免引入其编译期依赖）。
 */
public class SiloCookTask extends AbstractTimedContainerCookTask<SiloRecipe> {

    private static volatile RecipeType<?> typeCache;

    @Override
    public String getUID() {
        return "SiloCookTask";
    }

    @Override
    public ItemStack getIcon() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("farm_and_charm", "silo"));
        return block.defaultBlockState().isAir() ? ItemStack.EMPTY : new ItemStack(block);
    }

    @Override
    protected RecipeType<?> recipeType() {
        RecipeType<?> t = typeCache;
        if (t == null) {
            t = BuiltInRegistries.RECIPE_TYPE.get(ResourceLocation.fromNamespaceAndPath("farm_and_charm", "drying"));
            if (t == null) {
                throw new IllegalStateException("farm_and_charm:drying RecipeType 未注册（目标 mod 版本不符？）");
            }
            typeCache = t;
        }
        return t;
    }

    @Override
    protected Class<SiloRecipe> recipeClass() {
        return SiloRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(SiloRecipe recipe, Level level) {
        return recipe.getOutput();
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof SiloBlockEntity;
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        SiloRecipe recipe = (SiloRecipe) recipeHolder.value();
        return List.of(StackPredicate.of(recipe.getInput()));
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        SiloBlockEntity controller = controllerAt(level, pos);
        if (controller == null) {
            return List.of();
        }
        // 只读上报鲜料槽（0..cap-1）；输出侧 81+ 不属"当前输入"
        List<ItemStack> ans = new java.util.ArrayList<>();
        NonNullList<ItemStack> items = controller.getItems();
        int cap = controller.getCapacity();
        for (int i = 0; i < Math.min(cap, items.size()); i++) {
            if (!items.get(i).isEmpty()) {
                ans.add(items.get(i).copy());
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof SiloBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        SiloBlockEntity controller = controllerAt(level, pos);
        if (controller == null) {
            return;
        }
        SiloRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[xia-de-chu-fang] silo 请求配方缺失或类型不符：{}", request.id);
            return;
        }

        // 干料槽（输出侧）有货 → 取走
        ItemStack out = controller.tryRemoveItem();
        if (!out.isEmpty()) {
            if (out.getItem() == recipe.getOutput().getItem()) {
                ItemUtils.getItemToLivingEntity(maid, out);
                maid.swing(InteractionHand.OFF_HAND);
                request.remain--;
                return;
            }
            // 非本配方产物（其他来源残留）：放回不做，避免吞货
            controller.tryAddItem(out);
        }

        // 投 1 个鲜料（BE 每 200gt 自转化）；tryAddItem 返回 false=容量满（F-013）
        ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(recipe.getInput()), true);
        if (!take.isEmpty()) {
            if (controller.tryAddItem(take)) {
                maid.swing(InteractionHand.OFF_HAND);
            } else {
                ItemUtils.getItemToLivingEntity(maid, take);
                MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] 筒仓已满，鲜料回包：{} @ {}", request.id, pos);
            }
        }
    }

    /** 取 pos 处筒仓的 controller BE；自身即 controller 或多联头部。 */
    @Nullable
    private static SiloBlockEntity controllerAt(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof SiloBlockEntity be) {
            if (be.isController()) {
                return be;
            }
            SiloBlockEntity controller = be.getControllerBE();
            return controller != null ? controller : be;
        }
        return null;
    }
}
