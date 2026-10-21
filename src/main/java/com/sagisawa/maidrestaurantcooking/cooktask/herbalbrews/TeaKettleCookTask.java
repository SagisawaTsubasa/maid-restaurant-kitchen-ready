package com.sagisawa.maidrestaurantcooking.cooktask.herbalbrews;

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
import net.satisfy.herbalbrews.core.blocks.entity.TeaKettleBlockEntity;
import net.satisfy.herbalbrews.core.recipe.TeaKettleRecipe;
import net.satisfy.herbalbrews.core.registry.TagsRegistry;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 煨茶酝露「煮茶壶」（herbalbrews:kettle_brewing，9 条）的女仆烹饪任务。
 *
 * <p>实证（2026-10-17）：TeaKettleBlockEntity 实现 ImplementedInventory（Container API
 * 公开）——槽位 0 出品 / 1-4 配料 / 5 容器 / 6 注水（SMALL/LARGE_WATER_FILL 标签）/
 * 7 燃料（HEAT_ITEMS 标签）；tick 自注水→自烧水→自匹配→出品。女仆流程全容器化：
 * 投水+投燃料+投配料 → 等 → 取槽 0。水位/热值由 BE 私有自管理，无需干预。
 */
public class TeaKettleCookTask extends AbstractTimedContainerCookTask<TeaKettleRecipe> {

    /** F-155：停摆 WARN 上次响出时间戳（30s 时间窗去重）。 */
    private volatile long stallLastWarnMs;

    private static final int SLOT_OUTPUT = 0;
    private static final int SLOT_INGREDIENT_FIRST = 1;
    private static final int SLOT_INGREDIENT_LAST = 4;
    private static final int SLOT_WATER = 6;
    private static final int SLOT_HEAT = 7;

    @Override
    public String getUID() {
        return "TeaKettleCookTask";
    }

    @Override
    public ItemStack getIcon() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("herbalbrews", "tea_kettle"));
        return block.defaultBlockState().isAir() ? ItemStack.EMPTY : new ItemStack(block);
    }

    @Override
    protected RecipeType<?> recipeType() {
        // herbalbrews 用 architectury RegistrySupplier——同筒仓，经注册表键查；空值守卫（F-007）
        RecipeType<?> t = BuiltInRegistries.RECIPE_TYPE.get(ResourceLocation.fromNamespaceAndPath("herbalbrews", "kettle_brewing"));
        if (t == null) {
            throw new IllegalStateException("herbalbrews:kettle_brewing RecipeType 未注册（目标 mod 版本不符？）");
        }
        return t;
    }

    @Override
    protected Class<TeaKettleRecipe> recipeClass() {
        return TeaKettleRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(TeaKettleRecipe recipe, Level level) {
        return recipe.getResultItem();
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof TeaKettleBlockEntity;
    }

    /** 契约（F-201）：本体 MaidStateManager.cookState 原地 addAll（0.2.9 all.java:8713），返回值必须可变——Stream.toList() 违约致 2026-10-20 崩服。 */
    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        TeaKettleRecipe recipe = (TeaKettleRecipe) recipeHolder.value();
        List<StackPredicate> ans = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) {
                ans.add(StackPredicate.of(ingredient));
            }
        }
        // 注水容器与燃料按标签取
        ans.add(StackPredicate.of(stack -> stack.is(TagsRegistry.SMALL_WATER_FILL) || stack.is(TagsRegistry.LARGE_WATER_FILL)));
        ans.add(StackPredicate.of(stack -> stack.is(TagsRegistry.HEAT_ITEMS)));
        return ans;
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new ArrayList<>();
        if (level.getBlockEntity(pos) instanceof TeaKettleBlockEntity kettle) {
            for (int i = SLOT_INGREDIENT_FIRST; i <= SLOT_HEAT; i++) {
                if (!kettle.getItem(i).isEmpty()) {
                    ans.add(kettle.getItem(i).copy());
                }
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof TeaKettleBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        TeaKettleBlockEntity kettle = level.getBlockEntity(pos) instanceof TeaKettleBlockEntity k ? k : null;
        if (kettle == null) {
            return;
        }
        TeaKettleRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] kettle 请求配方缺失或类型不符：{}", request.id);
            return;
        }

        // 出品 → 取走（F-109：核对归属，非本单成品不取防错单）
        ItemStack output = kettle.getItem(SLOT_OUTPUT);
        if (!output.isEmpty() && output.getItem() != recipe.getResultItem(level.registryAccess()).getItem()) {
            // F-111：异物产出静默停摆 → 响出
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 煮茶壶槽 0 为非本单产物（{}），请求停摆待人工处理：{} @ {}", output.getItem(), request.id, pos);
            return;
        }
        if (!output.isEmpty()) {
            DeliveryUtil.deliverToMaidInv(maid, output.copy());
            kettle.removeItem(SLOT_OUTPUT, output.getCount());
            kettle.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
            request.remain--;
            return;
        }

        // F-155：水/燃料槽被非标签异物占据 → WARN 停摆（防订单无声永挂）
        ItemStack waterSlot = kettle.getItem(SLOT_WATER);
        if (!waterSlot.isEmpty() && !waterSlot.is(TagsRegistry.SMALL_WATER_FILL) && !waterSlot.is(TagsRegistry.LARGE_WATER_FILL)) {
            if (System.currentTimeMillis() - stallLastWarnMs > 30_000L) {
                stallLastWarnMs = System.currentTimeMillis();
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 煮茶壶水槽为非注水物品（{}），停摆待处理：{} @ {}", waterSlot.getItem(), request.id, pos);
            }
            return;
        }
        ItemStack heatSlot = kettle.getItem(SLOT_HEAT);
        if (!heatSlot.isEmpty() && !heatSlot.is(TagsRegistry.HEAT_ITEMS)) {
            if (System.currentTimeMillis() - stallLastWarnMs > 30_000L) {
                stallLastWarnMs = System.currentTimeMillis();
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 煮茶壶燃料槽为非燃料物品（{}），停摆待处理：{} @ {}", heatSlot.getItem(), request.id, pos);
            }
            return;
        }

        // 依槽补料：水 → 燃料 → 配料（槽位被占则跳过）
        boolean supplied = false;
        if (kettle.getItem(SLOT_WATER).isEmpty()) {
            ItemStack water = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                    StackPredicate.of(s -> s.is(TagsRegistry.SMALL_WATER_FILL) || s.is(TagsRegistry.LARGE_WATER_FILL)), true);
            if (!water.isEmpty()) {
                kettle.setItem(SLOT_WATER, water);
                kettle.setChanged();
                supplied = true;
            }
        }
        if (kettle.getItem(SLOT_HEAT).isEmpty()) {
            ItemStack heat = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                    StackPredicate.of(s -> s.is(TagsRegistry.HEAT_ITEMS)), true);
            if (!heat.isEmpty()) {
                kettle.setItem(SLOT_HEAT, heat);
                kettle.setChanged();
                supplied = true;
            }
        }
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            boolean present = false;
            for (int i = SLOT_INGREDIENT_FIRST; i <= SLOT_INGREDIENT_LAST; i++) {
                if (ingredient.test(kettle.getItem(i))) {
                    present = true;
                    break;
                }
            }
            if (present) {
                continue;
            }
            for (int i = SLOT_INGREDIENT_FIRST; i <= SLOT_INGREDIENT_LAST; i++) {
                if (kettle.getItem(i).isEmpty()) {
                    ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(ingredient), true);
                    if (!take.isEmpty()) {
                        kettle.setItem(i, take);
                        kettle.setChanged();
                        supplied = true;
                    }
                    break;
                }
            }
        }
        if (supplied) {
            maid.swing(InteractionHand.OFF_HAND);
        }
    }
}
