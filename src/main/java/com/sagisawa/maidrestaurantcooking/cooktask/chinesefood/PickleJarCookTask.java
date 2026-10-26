package com.sagisawa.maidrestaurantcooking.cooktask.chinesefood;

import com.bmt.kaleidoscope_chinesefood.block.PickleJarBlock;
import com.bmt.kaleidoscope_chinesefood.block.entity.PickleJarBlockEntity;
import com.bmt.kaleidoscope_chinesefood.crafting.PickleJarRecipe;
import com.bmt.kaleidoscope_chinesefood.init.ModBlocks;
import com.bmt.kaleidoscope_chinesefood.init.ModRecipes;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.sagisawa.maidrestaurantcooking.utils.DeliveryUtil;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.FakePlayerUtils;
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

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 国味「腌菜罐」（kaleidoscope_chinesefood:pickle_jar，7 条）的女仆烹饪任务。
 *
 * <p>实证（2026-10-17）：4 格 ItemStackHandler（public 字段）；流程 = 开盖放料 →
 * 关盖（useWithoutItem 语义：OPEN=false + tryStartFermenting 决定 FERMENTING）→
 * tick 发酵 → 完成后槽内变产物（output.count*4 上限 16）。女仆流程：有产物即逐槽取
 * （直读 inventory + DeliveryUtil 交付入包，绕开 extractItem 需 Player 的限制，
 * 插料用 FakePlayer 规避同参）；无产物则投料 + 关盖 + 公共启动口 checkForValidRecipeAndTryStartFermenting。
 */
public class PickleJarCookTask extends AbstractTimedContainerCookTask<PickleJarRecipe> {

    @Override
    public String getUID() {
        return "PickleJarCookTask";
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ModBlocks.PICKLE_JAR.get().asItem());
    }

    @Override
    protected RecipeType<PickleJarRecipe> recipeType() {
        return ModRecipes.PICKLE_JAR_TYPE.get();
    }

    @Override
    protected Class<PickleJarRecipe> recipeClass() {
        return PickleJarRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(PickleJarRecipe recipe, Level level) {
        return recipe.getResultItem(level.registryAccess());
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof PickleJarBlockEntity;
    }

    /** 契约（F-201）：本体 MaidStateManager.cookState 原地 addAll（0.2.9 all.java:8713），返回值必须可变——Stream.toList() 违约致 2026-10-20 崩服。 */
    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        // F-282 同族：非本类配方返回空表（裸强转在取料面 CCE；可变契约 F-201）
        if (!(recipeHolder.value() instanceof PickleJarRecipe recipe)) {
            return new java.util.ArrayList<>();
        }
        List<StackPredicate> ans = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) {
                ans.add(StackPredicate.of(ingredient));
            }
        }
        return ans;
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new ArrayList<>();
        if (level.getBlockEntity(pos) instanceof PickleJarBlockEntity jar) {
            for (int i = 0; i < jar.inventory.getSlots(); i++) {
                ItemStack stack = jar.inventory.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    ans.add(stack.copy());
                }
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof PickleJarBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        PickleJarBlockEntity jar = level.getBlockEntity(pos) instanceof PickleJarBlockEntity j ? j : null;
        if (jar == null) {
            return;
        }
        PickleJarRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] pickle_jar 请求配方缺失或类型不符：{}", request.id);
            return;
        }

        BlockState state = level.getBlockState(pos);
        boolean fermenting = state.getValue(PickleJarBlock.FERMENTING);
        if (fermenting) {
            return;
        }

        // 完成判定：槽内出现产物 → 逐槽取走（一次一槽，每取 remain--）
        ItemStack output = recipe.getResultItem(level.registryAccess());
        // F-121：先扫异物（既非本单产物也非本单原料 → 响出停摆）
        for (int i = 0; i < jar.inventory.getSlots(); i++) {
            ItemStack scan = jar.inventory.getStackInSlot(i);
            if (!scan.isEmpty() && scan.getItem() != output.getItem()
                    && recipe.getIngredients().stream().noneMatch(ing -> ing.test(scan))) {
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 腌菜罐槽位存在异物（{}），停摆待处理：{} @ {}", scan.getItem(), request.id, pos);
                return;
            }
        }
        for (int i = 0; i < jar.inventory.getSlots(); i++) {
            ItemStack stored = jar.inventory.getStackInSlot(i);
            if (!stored.isEmpty() && ItemStack.isSameItemSameComponents(stored, output)) {
                DeliveryUtil.deliverToMaidInv(maid, stored.copy());
                jar.inventory.setStackInSlot(i, ItemStack.EMPTY);
                jar.setChanged();
                maid.swing(InteractionHand.OFF_HAND);
                request.remain--;
                return;
            }
        }

        // 无产物：投料（每 tick 至多一种原料 1 个，槽满自动停）
        boolean inserted = false;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            boolean present = false;
            for (int i = 0; i < jar.inventory.getSlots(); i++) {
                if (ingredient.test(jar.inventory.getStackInSlot(i))) {
                    present = true;
                    break;
                }
            }
            if (present) {
                continue;
            }
            ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(ingredient), true);
            if (!take.isEmpty()) {
                jar.insertItem(take, FakePlayerUtils.getPlayer(level));
                // F-013：insertItem 返回 void 且对传入栈 shrink——核对是否真插入
                boolean landed = false;
                for (int i = 0; i < jar.inventory.getSlots(); i++) {
                    if (ingredient.test(jar.inventory.getStackInSlot(i))) {
                        landed = true;
                        break;
                    }
                }
                if (landed) {
                    maid.swing(InteractionHand.OFF_HAND);
                    inserted = true;
                } else {
                    // F-104：insertItem 未消耗传入栈（totalInserted=0 且无空槽）时回包
                    DeliveryUtil.deliverToMaidInv(maid, take);
                    MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 腌菜罐投料未落槽（槽满或被拒），原料已回包：{} @ {}", request.id, pos);
                }
            }
        }

        // 关盖并尝试启动发酵（BE 公共口；无有效配方则保持待料）
        if (inserted || state.getValue(PickleJarBlock.OPEN)) {
            level.setBlock(pos, state.setValue(PickleJarBlock.OPEN, false), 3);
            jar.checkForValidRecipeAndTryStartFermenting();
        }
    }
}
