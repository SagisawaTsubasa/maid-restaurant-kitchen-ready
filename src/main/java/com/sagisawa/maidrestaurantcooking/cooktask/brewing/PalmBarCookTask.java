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
import net.satisfy.beachparty.core.block.entity.PalmBarBlockEntity;
import net.satisfy.beachparty.core.recipe.PalmBarRecipe;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * 沙滩派对「棕榈吧台」（beachparty:palm_bar_mixing，6 条）的女仆烹饪任务。
 * 实证：WorldlyContainer 5 格——槽 0=输出、1-4=配料；摇制自走（shakingTime）。
 * 女仆：投配料 → 等 → 取槽 0。
 */
public class PalmBarCookTask extends AbstractTimedContainerCookTask<PalmBarRecipe> {

    @Override
    public String getUID() {
        return "PalmBarCookTask";
    }

    @Override
    public ItemStack getIcon() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("beachparty", "palm_bar"));
        return block.defaultBlockState().isAir() ? ItemStack.EMPTY : new ItemStack(block);
    }

    @Override
    protected RecipeType<?> recipeType() {
        return net.satisfy.beachparty.core.registry.RecipeTypeRegistry.PALM_BAR_RECIPE_TYPE.get();
    }

    @Override
    protected Class<PalmBarRecipe> recipeClass() {
        return PalmBarRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(PalmBarRecipe recipe, Level level) {
        return recipe.getResultItem(level.registryAccess());
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof PalmBarBlockEntity;
    }

    /** 契约（F-201）：本体 MaidStateManager.cookState 原地 addAll（0.2.9 all.java:8713），返回值必须可变——Stream.toList() 违约致 2026-10-20 崩服。 */
    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        PalmBarRecipe recipe = (PalmBarRecipe) recipeHolder.value();
        return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).map(StackPredicate::new)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new java.util.ArrayList<>();
        if (level.getBlockEntity(pos) instanceof PalmBarBlockEntity bar) {
            for (int i = 1; i < bar.getContainerSize(); i++) {
                if (!bar.getItem(i).isEmpty()) {
                    ans.add(bar.getItem(i).copy());
                }
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof PalmBarBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        PalmBarBlockEntity bar = level.getBlockEntity(pos) instanceof PalmBarBlockEntity b ? b : null;
        if (bar == null) {
            return;
        }
        PalmBarRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] palm_bar 请求配方缺失或类型不符：{}", request.id);
            return;
        }
        ItemStack expect = recipe.getResultItem(level.registryAccess());

        // 取货（槽 0；F-121：异物产出响出）
        ItemStack output = bar.getItem(0);
        ItemStack barExpect = recipe.getResultItem(level.registryAccess());
        if (!output.isEmpty() && output.getItem() != barExpect.getItem()) {
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 吧台输出槽为非本单产物（{}），停摆待处理：{} @ {}", output.getItem(), request.id, pos);
            return;
        }
        if (!output.isEmpty() && output.getItem() == expect.getItem()) {
            ItemUtils.getItemToLivingEntity(maid, output.copy());
            bar.removeItem(0, output.getCount());
            bar.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
            request.remain--;
            return;
        }

        // 投配料（1-4 空槽）
        for (var ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            boolean present = false;
            for (int i = 1; i < bar.getContainerSize(); i++) {
                if (ingredient.test(bar.getItem(i))) {
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
                for (int i = 1; i < bar.getContainerSize(); i++) {
                    if (bar.getItem(i).isEmpty()) {
                        bar.setItem(i, take);
                        bar.setChanged();
                        putIn = true;
                        maid.swing(InteractionHand.OFF_HAND);
                        break;
                    }
                }
                if (!putIn) {
                    ItemUtils.getItemToLivingEntity(maid, take);
                    MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] palm_bar 满槽放料失败，原料已回包：{}", request.id);
                }
            }
        }
    }
}
