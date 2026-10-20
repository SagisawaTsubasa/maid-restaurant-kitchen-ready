package com.sagisawa.maidrestaurantcooking.cooktask.dd;

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
import net.yirmiri.dungeonsdelight.common.block.monster_pot.MonsterPotBlockEntity;
import net.yirmiri.dungeonsdelight.common.block.monster_pot.MonsterPotRecipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 地牢乐事「怪物锅」（dungeonsdelight:monster_cooking，70 条）的女仆烹饪任务——T2 首实例。
 *
 * <p>实证：FD CookingPot 同构（inventory 9 格：0-5 配料 / 6 供餐展示 / 7 容器 / 8 输出；
 * isHeated（SpawnerHeatable，热源在下方）+ cookingTick 自煮）。上菜走 useHeldItemOnMeal(容器)
 * ——从女仆包按配方容器谓词抽 1 个碗，交换出餐品交女仆。加热前提与 FD 锅一致（下方热源）。
 */
public class MonsterPotCookTask extends AbstractTimedContainerCookTask<MonsterPotRecipe> {

    /** F-160：站点键控退避（任务实例跨维度共享，退避必须按 维度+坐标 区分；过期条目顺手清理）。 */
    private record StationKey(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim,
                              net.minecraft.core.BlockPos pos) {
    }

    private final java.util.concurrent.ConcurrentHashMap<StationKey, Long> backoffUntil = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean inBackoff(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        StationKey key = new StationKey(level.dimension(), pos.immutable());
        Long until = backoffUntil.get(key);
        if (until != null && System.currentTimeMillis() < until) {
            return true;
        }
        if (backoffUntil.size() > 64) {
            long now = System.currentTimeMillis();
            backoffUntil.entrySet().removeIf(e -> e.getValue() < now);
        }
        return false;
    }

    private void setBackoff(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos, long durationMs) {
        backoffUntil.put(new StationKey(level.dimension(), pos.immutable()),
                System.currentTimeMillis() + durationMs);
    }

    /** F-156：锅满 WARN 上次响出时间戳（30s 时间窗去重）。 */
    private volatile long stallLastWarnMs;

    @Override
    public String getUID() {
        return "MonsterPotCookTask";
    }

    @Override
    public ItemStack getIcon() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("dungeonsdelight", "monster_pot"));
        return block.defaultBlockState().isAir() ? ItemStack.EMPTY : new ItemStack(block);
    }

    @Override
    protected RecipeType<?> recipeType() {
        // DD 注册自己的 monster_cooking 类型（非 FD cooking）；经注册表键查；空值守卫（F-007）
        RecipeType<?> t = BuiltInRegistries.RECIPE_TYPE.get(ResourceLocation.fromNamespaceAndPath("dungeonsdelight", "monster_cooking"));
        if (t == null) {
            throw new IllegalStateException("dungeonsdelight:monster_cooking RecipeType 未注册（目标 mod 版本不符？）");
        }
        return t;
    }

    @Override
    protected Class<MonsterPotRecipe> recipeClass() {
        return MonsterPotRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(MonsterPotRecipe recipe, Level level) {
        return recipe.getResultItem(level.registryAccess());
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof MonsterPotBlockEntity;
    }

    /** 契约（F-201）：本体 MaidStateManager.cookState 原地 addAll（0.2.9 all.java:8713），返回值必须可变——Stream.toList() 违约致 2026-10-20 崩服。 */
    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        MonsterPotRecipe recipe = (MonsterPotRecipe) recipeHolder.value();
        List<StackPredicate> ans = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) {
                ans.add(StackPredicate.of(ingredient));
            }
        }
        ItemStack container = recipe.getOutputContainer();
        if (!container.isEmpty()) {
            ans.add(StackPredicate.of(container));
        }
        return ans;
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new ArrayList<>();
        if (level.getBlockEntity(pos) instanceof MonsterPotBlockEntity pot) {
            for (int i = 0; i <= 5; i++) {
                ItemStack stack = pot.getInventory().getStackInSlot(i);
                if (!stack.isEmpty()) {
                    ans.add(stack.copy());
                }
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof MonsterPotBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        MonsterPotBlockEntity pot = level.getBlockEntity(pos) instanceof MonsterPotBlockEntity p ? p : null;
        if (pot == null) {
            return;
        }
        MonsterPotRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] monster_pot 请求配方缺失或类型不符：{}", request.id);
            return;
        }

        // 餐成 → 取碗上菜（F-109：核对归属，非本单餐品不取防错单）
        ItemStack meal = pot.getMeal();
        if (!meal.isEmpty() && meal.getItem() != recipe.getResultItem(level.registryAccess()).getItem()) {
            // F-111：异物产出静默停摆 → 响出
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 怪物锅内为非本单餐品（{}），请求停摆待人工处理：{} @ {}", meal.getItem(), request.id, pos);
            return;
        }
        if (!meal.isEmpty()) {
            ItemStack container = recipe.getOutputContainer();
            // F-021：容器谓词原样交给 BE 校验（useHeldItemOnMeal→isContainerValid）；无容器配方不打空碗
            ItemStack bowl = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(container), true);
            if (bowl.isEmpty()) {
                return;
            }
            ItemStack served = pot.useHeldItemOnMeal(bowl);
            if (!served.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, served);
                maid.swing(InteractionHand.OFF_HAND);
                request.remain--;
            } else {
                // F-162：容器不符（配方实际用其他容器）——碗退回女仆包并响出
                ItemUtils.getItemToLivingEntity(maid, bowl);
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 怪物锅上菜容器被拒，碗已回包：{} @ {}", request.id, pos);
            }
            return;
        }

        // 投配料（0-5）与容器（7）；F-156/F-160：锅满退避窗内不抽料（按站点键控）
        var inv = pot.getInventory();
        boolean supplied = false;
        if (inBackoff(level, pos)) {
            return;
        }
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            boolean present = false;
            for (int i = 0; i <= 5; i++) {
                if (ingredient.test(inv.getStackInSlot(i))) {
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
                for (int i = 0; i <= 5; i++) {
                    if (inv.getStackInSlot(i).isEmpty()) {
                        inv.setStackInSlot(i, take);
                        putIn = true;
                        supplied = true;
                        break;
                    }
                }
                if (!putIn) {
                    // F-003+F-156/F-160：按本次放入判定归还；锅满设站点退避+响出（30s 窗）
                    ItemUtils.getItemToLivingEntity(maid, take);
                    setBackoff(level, pos, 30_000L);
                    if (System.currentTimeMillis() - stallLastWarnMs > 30_000L) {
                        stallLastWarnMs = System.currentTimeMillis();
                        MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 怪物锅配料槽满，原料回包待重试：{} @ {}", request.id, pos);
                }
                }
            }
        }
        if (!recipe.getOutputContainer().isEmpty() && inv.getStackInSlot(7).isEmpty()) {
            ItemStack bowl = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                    StackPredicate.of(recipe.getOutputContainer()), true);
            if (!bowl.isEmpty()) {
                inv.setStackInSlot(7, bowl);
                supplied = true;
            }
        }
        if (supplied) {
            maid.swing(InteractionHand.OFF_HAND);
        }
    }
}
