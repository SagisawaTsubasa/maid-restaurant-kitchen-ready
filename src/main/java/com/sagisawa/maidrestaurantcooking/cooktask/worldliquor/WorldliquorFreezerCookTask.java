package com.sagisawa.maidrestaurantcooking.cooktask.worldliquor;

import com.bmt.kaleidoscope_world_liquor.block.entity.FreezerBlockEntity;
import com.bmt.kaleidoscope_world_liquor.block.FreezerBlock;
import com.bmt.kaleidoscope_world_liquor.crafting.FreezerRecipe;
import com.bmt.kaleidoscope_world_liquor.init.ModRecipes;
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
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 森罗酒馆：世界名酒「冰柜」（kaleidoscope_world_liquor:freezer，5 条）的女仆烹饪任务。
 *
 * <p>实证（2026-10-17）：inputInventory(4)+tank(1000)+outputInventory(1) 全 public；
 * tank/inputInventory 的 onContentsChanged 自动 checkForMatchingRecipe，配方命中且
 * 关盖且有输出空位时 autoStartCrafting（tryStartCrafting 起始即耗液+清空输入槽）。
 * 女仆流程：关盖 → 注液（从流体容器精确抽取配方需求量）→ 投料 → 等完成 →
 * outputInventory.extractItem 取货（该方法自身维护 outputCount 复位）。
 */
public class WorldliquorFreezerCookTask extends AbstractTimedContainerCookTask<FreezerRecipe> {

    @Override
    public String getUID() {
        return "WorldliquorFreezerCookTask";
    }

    @Override
    public ItemStack getIcon() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("kaleidoscope_world_liquor", "freezer"));
        return block.defaultBlockState().isAir() ? ItemStack.EMPTY : new ItemStack(block);
    }

    @Override
    protected RecipeType<?> recipeType() {
        return ModRecipes.FREEZER_TYPE.get();
    }

    @Override
    protected Class<FreezerRecipe> recipeClass() {
        return FreezerRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(FreezerRecipe recipe, Level level) {
        return recipe.getResultItem();
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof FreezerBlockEntity;
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        FreezerRecipe recipe = (FreezerRecipe) recipeHolder.value();
        List<StackPredicate> ans = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) {
                ans.add(StackPredicate.of(ingredient));
            }
        }
        // 流体需求以「装着该流体的容器」形式进女仆取料单
        ans.add(StackPredicate.of(stack -> {
            if (stack.isEmpty()) {
                return false;
            }
            IFluidHandlerItem cap = stack.getCapability(Capabilities.FluidHandler.ITEM);
            FluidStack inTank = cap == null ? null : cap.getFluidInTank(0);
            return inTank != null && inTank.getFluid() == recipe.getInputFluid().getFluid()
                    && inTank.getAmount() >= recipe.getInputFluid().getAmount();
        }));
        return ans;
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new ArrayList<>();
        if (level.getBlockEntity(pos) instanceof FreezerBlockEntity freezer) {
            for (int i = 0; i < freezer.inputInventory.getSlots(); i++) {
                if (!freezer.inputInventory.getStackInSlot(i).isEmpty()) {
                    ans.add(freezer.inputInventory.getStackInSlot(i).copy());
                }
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FreezerBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        FreezerBlockEntity freezer = level.getBlockEntity(pos) instanceof FreezerBlockEntity f ? f : null;
        if (freezer == null) {
            return;
        }
        FreezerRecipe recipe = resolveRecipe(level, request);
        if (recipe == null || recipe.isEmpty()) {
            MaidRestaurantCooking.LOGGER.error("[xia-de-chu-fang] worldliquor freezer 请求配方缺失或类型不符：{}", request.id);
            return;
        }

        // 输出槽有货 → 取走
        ItemStack output = freezer.outputInventory.getStackInSlot(0);
        if (!output.isEmpty()) {
            ItemStack taken = freezer.outputInventory.extractItem(0, output.getCount(), false);
            if (!taken.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, taken);
                maid.swing(InteractionHand.OFF_HAND);
                request.remain--;
            }
            return;
        }

        BlockState state = freezer.getBlockState();
        // 关盖（autoStart 要求 OPEN=false）
        if (state.getValue(FreezerBlock.OPEN)) {
            level.setBlock(pos, state.setValue(FreezerBlock.OPEN, false), 3);
            return;
        }

        // 注液：不足则从女仆容器抽
        FluidStack need = recipe.getInputFluid();
        if (freezer.tank.getFluidAmount() < need.getAmount() || !freezer.tank.getFluid().getFluid().equals(need.getFluid())) {
            ItemStack take = extractFluidContainer(maid, need);
            if (take.isEmpty()) {
                return;
            }
            IFluidHandlerItem cap = take.getCapability(Capabilities.FluidHandler.ITEM);
            if (cap == null) {
                return;
            }
            FluidStack drained = cap.drain(need.copy(), IFluidHandler.FluidAction.EXECUTE);
            if (!drained.isEmpty()) {
                int accepted = freezer.tank.fill(drained, IFluidHandler.FluidAction.EXECUTE);
                if (accepted < drained.getAmount()) {
                    // 容器内剩余流体无处安放：剩余部分随容器留女仆包（drain 后容器自动回填空罐）
                    ItemUtils.getItemToLivingEntity(maid, take);
                }
                maid.swing(InteractionHand.OFF_HAND);
            }
            return;
        }

        // 投料：inputInventory.onContentsChanged 自动触发匹配+autoStart
        var ingredients = recipe.getIngredients();
        boolean inserted = false;
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;
            }
            ItemStack material = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(ingredient), true);
            if (!material.isEmpty()) {
                for (int i = 0; i < freezer.inputInventory.getSlots(); i++) {
                    if (freezer.inputInventory.getStackInSlot(i).isEmpty()) {
                        freezer.inputInventory.setStackInSlot(i, material);
                        inserted = true;
                        maid.swing(InteractionHand.OFF_HAND);
                        break;
                    }
                }
                if (!inserted) {
                    // 槽满放不下：还给女仆
                    ItemUtils.getItemToLivingEntity(maid, material);
                }
            }
        }
    }

    private static ItemStack extractFluidContainer(EntityMaid maid, FluidStack need) {
        var inv = maid.getAvailableInv(false);
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            IFluidHandlerItem cap = stack.getCapability(Capabilities.FluidHandler.ITEM);
            if (cap == null) {
                continue;
            }
            FluidStack inTank = cap.getFluidInTank(0);
            if (inTank.getFluid().equals(need.getFluid()) && inTank.getAmount() >= need.getAmount()) {
                return inv.extractItem(i, 1, false);
            }
        }
        return ItemStack.EMPTY;
    }
}
