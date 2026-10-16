package com.sagisawa.maidrestaurantcooking.cooktask.kaleidoscope;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.BambooTrayBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.BambooTrayRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils;
import com.mastermarisa.maid_restaurant.api.ICookTask;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.BlockUsageManager;
import com.mastermarisa.maid_restaurant.utils.ItemHandlerUtils;
import com.mastermarisa.maid_restaurant.utils.SearchUtils;
import com.mastermarisa.maid_restaurant.utils.component.RecipeData;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import com.sagisawa.maidrestaurantcooking.utils.RecipeDataFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 森罗厨房「竹匾」（kaleidoscope_cookery:bamboo_tray，28 条：晾晒/淋湿）的女仆烹饪任务。
 *
 * <p>T3 定时容器形态（2026-10-17 实证）：BE 自驱动——serverTick 每 19gt 依环境
 * （淋湿=头顶雨水/滴水石锥；晾晒=无雨+露天或玻璃/竹匾遮罩）自行匹配配方并转化，
 * 女仆只做「放料→等完成→取货」。环境不满足时 BE 不推进，请求将等待（真机注意项）。
 * 无 POI（cookery 只注册炉/锅/案板 POI），走 SearchUtils 盒扫 + 占用过滤。
 */
public class BambooTrayCookTask implements ICookTask {

    @Override
    public String getUID() {
        return "BambooTrayCookTask";
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ModItems.BAMBOO_TRAY.get());
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipes.BAMBOO_TRAY_RECIPE;
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends Recipe<?>> recipeHolder, Level level) {
        BambooTrayRecipe recipe = (BambooTrayRecipe) recipeHolder.value();
        return List.of(StackPredicate.of(recipe.getIngredient()));
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new ArrayList<>();
        if (level.getBlockEntity(pos) instanceof BambooTrayBlockEntity tray) {
            for (ItemStack stack : tray.getItems()) {
                if (!stack.isEmpty()) {
                    ans.add(stack.copy());
                }
            }
        }
        return ans;
    }

    @Nullable
    @Override
    public BlockPos searchWorkBlock(ServerLevel level, EntityMaid maid, int horizontalSearchRange, int verticalSearchRange) {
        BlockPos center = maid.getBrainSearchPos();
        List<BlockPos> found = SearchUtils.search(
                center,
                horizontalSearchRange,
                verticalSearchRange,
                pos -> level.getBlockEntity(pos) instanceof BambooTrayBlockEntity && BlockUsageManager.getUserCount(pos) <= 0
        );
        return !found.isEmpty() ? found.stream().min(Comparator.comparingDouble(p -> p.distSqr(maid.blockPosition()))).get() : null;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof BambooTrayBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        if (!(level.getBlockEntity(pos) instanceof BambooTrayBlockEntity tray)) {
            return;
        }
        RecipeHolder<?> holder = level.getRecipeManager().byKey(request.id).orElse(null);
        if (holder == null || !(holder.value() instanceof BambooTrayRecipe recipe)) {
            MaidRestaurantCooking.LOGGER.error("[xia-de-chu-fang] bamboo_tray 请求配方缺失或类型不符：{}", request.id);
            return;
        }

        // 1) 已有完成品 → 整槽取走（BE 会把结果按输入数量放大）
        for (int slot = 0; slot < tray.getContainerSize(); slot++) {
            ItemStack stored = tray.getItem(slot);
            if (!stored.isEmpty() && ItemStack.isSameItemSameComponents(stored, recipe.getResult())) {
                tray.onTakeOut(level, maid, slot, true);
                maid.swing(net.minecraft.world.InteractionHand.OFF_HAND);
                request.remain--;
                return;
            }
        }

        // 2) 已有本配方原料在匾上 → BE 自行推进，等待
        for (int slot = 0; slot < tray.getContainerSize(); slot++) {
            ItemStack stored = tray.getItem(slot);
            if (!stored.isEmpty() && recipe.getIngredient().test(stored)) {
                return;
            }
        }

        // 3) 放料：先探空槽再抽（F-002 防吞），投入第一个空槽
        boolean hasEmpty = false;
        for (int slot = 0; slot < tray.getContainerSize(); slot++) {
            if (tray.getItem(slot).isEmpty()) {
                hasEmpty = true;
                break;
            }
        }
        if (!hasEmpty) {
            return;
        }
        ItemStack input = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(recipe.getIngredient()), true);
        if (input.isEmpty()) {
            return;
        }
        for (int slot = 0; slot < tray.getContainerSize(); slot++) {
            if (tray.getItem(slot).isEmpty()) {
                tray.onPutItem(level, maid, input, slot);
                maid.swing(net.minecraft.world.InteractionHand.OFF_HAND);
                return;
            }
        }
        ItemUtils.getItemToLivingEntity(maid, input);
        MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] bamboo_tray 满槽放料失败，原料已回包：{}", request.id);
    }

    @Override
    public List<RecipeData> getAllRecipeData(Level level) {
        List<RecipeData> ans = new ArrayList<>();
        for (RecipeHolder<BambooTrayRecipe> holder : level.getRecipeManager().getAllRecipesFor(ModRecipes.BAMBOO_TRAY_RECIPE)) {
            ans.add(new RecipeData(holder.id(), ModRecipes.BAMBOO_TRAY_RECIPE, this.getIcon(), holder.value().getResult()));
        }
        return RecipeDataFilter.apply(ans);
    }
}
