package com.sagisawa.maidrestaurantcooking.cooktask.brewing;

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
import net.satisfy.vinery.core.block.entity.FermentationBarrelBlockEntity;
import net.satisfy.vinery.core.recipe.FermentationBarrelRecipe;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * 葡园酒香「发酵桶」（vinery:wine_fermentation，26 条）的女仆烹饪任务。
 * 实证：槽 0=葡萄汁输入、4=酒瓶、5=输出；液位/果汁类型 BE 自管；tick 自发酵。
 * 女仆：投汁+投瓶 → 等 → 取酒（瓶需求由配方 isWineBottleRequired 判定）。
 */
public class FermentationBarrelCookTask extends AbstractTimedContainerCookTask<FermentationBarrelRecipe> {

    /** F-229/F-241/F-255/F-262：停摆 WARN/ERROR 的 30s 窗（键=消息码:订单id:女仆UUID——任务每类型单例，
     * 键必须带订单+女仆，防跨单/跨女仆互吞，F-196/F-202 同族教训；旧 stallLastWarnMs
     * 单时间戳三消息共用窗已删，汁异物/瓶异物/无瓶各自独立键；R7-F-262 改名 logWindows——
     * 原 beNullLogWindows 已名不副实，窗表现承载全部消息码）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> logWindows = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long LOG_WINDOW_MS = 30_000L;

    /** F-241：30s 窗内同键只响一次（仿 CraftingTableCookTask.shouldLog；过期条目顺手清理）。 */
    private boolean shouldLog(String code) {
        long now = System.currentTimeMillis();
        Long until = logWindows.get(code);
        if (until != null && now < until) {
            return false;
        }
        if (logWindows.size() > 64) {
            logWindows.entrySet().removeIf(e -> e.getValue() < now);
        }
        logWindows.put(code, now + LOG_WINDOW_MS);
        return true;
    }

    @Override
    public String getUID() {
        return "FermentationBarrelCookTask";
    }

    @Override
    public ItemStack getIcon() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("vinery", "fermentation_barrel"));
        return block.defaultBlockState().isAir() ? ItemStack.EMPTY : new ItemStack(block);
    }

    @Override
    protected RecipeType<?> recipeType() {
        return FermentationBarrelRecipe.Type;
    }

    @Override
    protected Class<FermentationBarrelRecipe> recipeClass() {
        return FermentationBarrelRecipe.class;
    }

    @Override
    protected ItemStack recipeResult(FermentationBarrelRecipe recipe, Level level) {
        return recipe.getResultItem(level.registryAccess());
    }

    @Override
    protected Predicate<BlockPos> workBlockFilter(ServerLevel level) {
        return pos -> level.getBlockEntity(pos) instanceof FermentationBarrelBlockEntity;
    }

    /** 契约（F-201）：本体 MaidStateManager.cookState 原地 addAll（0.2.9 all.java:8713），返回值必须可变——Stream.toList() 违约致 2026-10-20 崩服。 */
    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        // F-282（R9 发现 2）：F-272 同族——同 wine_fermentation 类型下第三方注册的非
        // FermentationBarrelRecipe 配方，裸强转会在女仆脑 tick 取配料时 CCE；不匹配返回空
        // ArrayList（可变契约 F-201 保持），上层净缺额为空=不介入
        if (!(recipeHolder.value() instanceof FermentationBarrelRecipe recipe)) {
            return new java.util.ArrayList<>();
        }
        // F-148：酒瓶是必需辅料（isWineBottleRequired），进取料单——女仆才会主动去仓库取瓶
        java.util.List<StackPredicate> ans = recipe.getIngredients().stream()
                .filter(i -> !i.isEmpty()).map(StackPredicate::new)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        if (recipe.isWineBottleRequired()) {
            ans.add(StackPredicate.of(s -> s.getItem()
                    == net.satisfy.vinery.core.registry.ObjectRegistry.WINE_BOTTLE.get()));
        }
        return ans;
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new java.util.ArrayList<>();
        if (level.getBlockEntity(pos) instanceof FermentationBarrelBlockEntity barrel) {
            for (int i = 0; i < barrel.getContainerSize(); i++) {
                if (!barrel.getItem(i).isEmpty()) {
                    ans.add(barrel.getItem(i).copy());
                }
            }
        }
        return ans;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FermentationBarrelBlockEntity;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        FermentationBarrelBlockEntity barrel = level.getBlockEntity(pos) instanceof FermentationBarrelBlockEntity b ? b : null;
        if (barrel == null) {
            // F-229/F-241：BE 强转失败静默 return 即订单无声挂起（工位被拆/被换实例等异常态）——
            // 30s 窗 WARN，键=be-null:订单id:女仆UUID（per-request per-maid）
            if (shouldLog("be-null:" + request.id + ":" + maid.getUUID())) {
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 发酵桶工位 BE 缺失或类型不符，订单停摆待处理：{} @ {}", request.id, pos);
            }
            return;
        }
        FermentationBarrelRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            // F-262：粘滞态 ERROR 套 per-key 30s 窗（键=recipe-missing:订单id:女仆UUID）——本体烹饪
            // 任务每 tick 回调，裸 ERROR 在 /reload 删配方等停摆态会 20Hz 刷屏（F-255 迁窗漏的兄弟）
            if (shouldLog("recipe-missing:" + request.id + ":" + maid.getUUID())) {
                MaidRestaurantCooking.LOGGER.error("[kitchen-ready] fermentation_barrel 请求配方缺失或类型不符：{}", request.id);
            }
            return;
        }
        ItemStack expect = recipe.getResultItem(level.registryAccess());

        // 取酒（输出槽 5；F-121：异物产出响出）；F-228：预期产物单变量（原 expect/barrelExpect 等价双算删一）
        ItemStack output = barrel.getItem(FermentationBarrelBlockEntity.OUTPUT_SLOT_GENERAL);
        if (!output.isEmpty() && output.getItem() != expect.getItem()) {
            // F-262：粘滞态 WARN 套 per-key 30s 窗（键=output-foreign:订单id:女仆UUID，同上防 20Hz 刷屏）
            if (shouldLog("output-foreign:" + request.id + ":" + maid.getUUID())) {
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 发酵桶输出槽为非本单产物（{}），停摆待处理：{} @ {}", output.getItem(), request.id, pos);
            }
            return;
        }
        if (!output.isEmpty() && output.getItem() == expect.getItem()) {
            DeliveryUtil.deliverToMaidInv(maid, output.copy());
            barrel.removeItem(FermentationBarrelBlockEntity.OUTPUT_SLOT_GENERAL, output.getCount());
            barrel.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
            request.remain--;
            return;
        }

        // 投汁（槽 0）与瓶（槽 4，配方要求时）；F-138/F-149：槽 0 异物 → 停摆（return 无条件），
        // WARN 带独立窗（F-255：与瓶异物/无瓶消息各自键控，不再共用单时间戳互吞）
        if (!barrel.getItem(0).isEmpty()
                && recipe.getIngredients().stream().noneMatch(ing -> ing.test(barrel.getItem(0)))) {
            if (shouldLog("juice-foreign:" + request.id + ":" + maid.getUUID())) {
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 发酵桶汁槽为非本单原料（{}），停摆待处理：{} @ {}", barrel.getItem(0).getItem(), request.id, pos);
            }
            return;
        }
        for (var ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty() || ingredient.test(barrel.getItem(0))) {
                continue;
            }
            if (barrel.getItem(0).isEmpty()) {
                ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(ingredient), true);
                if (!take.isEmpty()) {
                    barrel.setItem(0, take);
                    barrel.setChanged();
                    maid.swing(InteractionHand.OFF_HAND);
                }
                break;
            }
        }
        // F-143/F-149：酒瓶槽异物 → 停摆（return 无条件），WARN 带独立窗（F-255）；缺瓶且女仆无瓶 → WARN 带独立窗（F-148 闭环）
        ItemStack bottleSlot = barrel.getItem(FermentationBarrelBlockEntity.WINE_BOTTLE_SLOT);
        if (!bottleSlot.isEmpty() && recipe.isWineBottleRequired()
                && bottleSlot.getItem() != net.satisfy.vinery.core.registry.ObjectRegistry.WINE_BOTTLE.get()) {
            if (shouldLog("bottle-foreign:" + request.id + ":" + maid.getUUID())) {
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 发酵桶酒瓶槽为非酒瓶物品（{}），停摆待处理：{} @ {}", bottleSlot.getItem(), request.id, pos);
            }
            return;
        }
        if (recipe.isWineBottleRequired() && barrel.getItem(FermentationBarrelBlockEntity.WINE_BOTTLE_SLOT).isEmpty()) {
            // 酒瓶槽：vinery:wine_bottle（ObjectRegistry.WINE_BOTTLE，2026-10-17 实测注册名）——从女仆包抽
            ItemStack bottle = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                    StackPredicate.of(s -> s.getItem() == net.satisfy.vinery.core.registry.ObjectRegistry.WINE_BOTTLE.get()), true);
            if (!bottle.isEmpty()) {
                barrel.setItem(FermentationBarrelBlockEntity.WINE_BOTTLE_SLOT, bottle);
                barrel.setChanged();
                maid.swing(InteractionHand.OFF_HAND);
            } else if (shouldLog("no-bottle:" + request.id + ":" + maid.getUUID())) {
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 发酵桶等酒瓶但女仆无瓶，订单挂起：{} @ {}", request.id, pos);
            }
        }
    }
}
