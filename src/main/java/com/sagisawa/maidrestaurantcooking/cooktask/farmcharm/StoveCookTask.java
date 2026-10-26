package com.sagisawa.maidrestaurantcooking.cooktask.farmcharm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.sagisawa.maidrestaurantcooking.utils.DeliveryUtil;
import com.mastermarisa.maid_restaurant.request.CookRequest;
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
import net.satisfy.farm_and_charm.core.block.entity.StoveBlockEntity;
import net.satisfy.farm_and_charm.core.recipe.StoveRecipe;
import net.satisfy.farm_and_charm.core.registry.RecipeTypeRegistry;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * 沉浸农艺「炉灶」（farm_and_charm:stove：本体 9 条 + bakery/candlelight/brewery 扩展，
 * TSV 现网 40+ 条，按类型全收）的女仆烹饪任务。
 * 实证：配料槽（getIngredientSlots）+ 燃料槽 4（getTotalBurnTime）+ 输出槽（getOutputSlot），
 * tick 自燃自煮。女仆：投配料+补燃料 → 等熟 → 取货。
 */
public class StoveCookTask extends AbstractTimedContainerCookTask<StoveRecipe> {

    /** F-229/F-241/F-255/F-262：停摆 WARN/ERROR 的 30s 窗（键=消息码:订单id:女仆UUID——任务每类型单例，
     * 键必须带订单+女仆，防跨单/跨女仆互吞，F-196/F-202 同族教训；旧 noFuelLastWarnMs
     * 单时间戳双消息共用窗已删，无燃料/燃料异物各自独立键；R7-F-262 改名 logWindows——
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

    /** 契约（F-201）：本体 MaidStateManager.cookState 原地 addAll（0.2.9 all.java:8713），返回值必须可变——Stream.toList() 违约致 2026-10-20 崩服。 */
    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        // F-282（R9 发现 2）：F-272 同族——同 STOVE_RECIPE_TYPE 下第三方注册的非 StoveRecipe 配方，
        // 裸强转会在女仆脑 tick 取配料时 CCE；不匹配返回空 ArrayList（可变契约 F-201 保持），
        // 上层 filterByCount 净缺额为空=不介入、order 面 required 空走 order-empty-ingredients 冷却可见
        if (!(recipeHolder.value() instanceof StoveRecipe recipe)) {
            return new java.util.ArrayList<>();
        }
        // F-142：燃料进取料单（女仆会主动去取的物品语义，对照 TeaKettle 水/燃料先例）
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
            // F-229/F-241：BE 强转失败静默 return 即订单无声挂起（工位被拆/被换实例等异常态）——
            // 30s 窗 WARN，键=be-null:订单id:女仆UUID（per-request per-maid）
            if (shouldLog("be-null:" + request.id + ":" + maid.getUUID())) {
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 炉灶工位 BE 缺失或类型不符，订单停摆待处理：{} @ {}", request.id, pos);
            }
            return;
        }
        StoveRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            // F-262：粘滞态 ERROR 套 per-key 30s 窗（键=recipe-missing:订单id:女仆UUID）——本体烹饪
            // 任务每 tick 回调，裸 ERROR 在 /reload 删配方等停摆态会 20Hz 刷屏（F-255 迁窗漏的兄弟）
            if (shouldLog("recipe-missing:" + request.id + ":" + maid.getUUID())) {
                MaidRestaurantCooking.LOGGER.error("[kitchen-ready] stove 请求配方缺失或类型不符：{}", request.id);
            }
            return;
        }

        // 取货（F-121：异物产出响出）；F-228：预期产物单变量（原 stoveExpect/expect 等价双算删一）
        ItemStack output = stove.getItem(stove.getOutputSlot());
        ItemStack expect = recipe.getResultItem(level.registryAccess());
        if (!output.isEmpty() && output.getItem() != expect.getItem()) {
            // F-262：粘滞态 WARN 套 per-key 30s 窗（键=output-foreign:订单id:女仆UUID，同上防 20Hz 刷屏）
            if (shouldLog("output-foreign:" + request.id + ":" + maid.getUUID())) {
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 炉灶输出槽为非本单产物（{}），停摆待处理：{} @ {}", output.getItem(), request.id, pos);
            }
            return;
        }
        if (!output.isEmpty() && output.getItem() == expect.getItem()) {
            DeliveryUtil.deliverToMaidInv(maid, output.copy());
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
                // F-152：配料已在位（确实在等点火）才响无燃料 WARN；空灶等待属正常巡检不刷屏。
                // F-255：per-key 窗（键带消息码+订单+女仆），不再与燃料异物共用单时间戳互吞
                boolean ingredientPresent = false;
                for (int slot : stove.getIngredientSlots()) {
                    if (!stove.getItem(slot).isEmpty()) {
                        ingredientPresent = true;
                        break;
                    }
                }
                if (ingredientPresent && shouldLog("no-fuel:" + request.id + ":" + maid.getUUID())) {
                    MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 农艺炉灶配料在位但女仆无燃料，订单挂起：{} @ {}", request.id, pos);
                }
            }
        } else if (!AbstractFurnaceFuelCheck.isFuel(stove.getItem(4))) {
            // F-150：异物占燃料槽 → 停摆（return），WARN 带独立窗（F-255：与无燃料消息各自键控）
            if (shouldLog("fuel-foreign:" + request.id + ":" + maid.getUUID())) {
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 农艺炉灶燃料槽为非燃料异物（{}），停摆待处理：{} @ {}", stove.getItem(4).getItem(), request.id, pos);
            }
            return;
        }

        // 投配料（R5-F-254/R7-F-264：多重集口径——同组配料计数副本逐空槽消费，presentCount<requiredCount
        // 才投。事实更正（R7-F-264，docs/scan/烹饪与酿造-配方全量清单.tsv :475/:477/:482/:491 核对）：
        // farm_and_charm:stove 配方实有 4 条重复配料——grilled_bacon_sandwich（培根×2）、
        // improved_bread（小麦×3）、pasta_with_onion_sauce（洋葱×2）、roasted_corn（玉米×2），
        // 后两条由 farm_and_charm jar 注册（TSV:482/:491）——旧「任一槽已有该配料即跳过」对这 4 条
        // 现网必现只投一份致炉灶永不满足、订单挂起，非旧注释所称「9 条无重复、潜伏级」。
        // tag+具体物品配方无重叠实证（R8-F-264 嵌套展开补全，2026-10-26 本机 libs 全 jar 逐 tag dump）：
        // baguette/braided_bread（letsdo-bakery 注册的 farm_and_charm:stove 配方）配料=[bakery:sweet_dough
        // (物品), #bakery:dough(tag), …]，而 letsdo-bakery jar data/bakery/tags/item/dough.json=["#c:dough"]。
        // #c:dough 各定义方并集={kaleidoscope_cookery:raw_dough, #c:doughs, #c:foods/dough}（cookery）
        // ∪ {#c:doughs}（farm_and_charm）∪ FID 自有面团 9 项（flavor_immersed_daily）∪ replace=true
        // 仅 raw_dough（kaleidoscope_compat unite 包——replace 只删不增，不影响下述零出现结论）；
        // #c:doughs=∅（cookery 空表）∪ {farm_and_charm:dough}（farm_and_charm）∪ {bakeries:sweet_dough,
        // bakeries:salted_dough, bakeries:whole_wheat_dough}（烘焙坊 bakeries jar，bakeries: 命名空间，
        // 非 bakery:）；#c:foods/dough={#c:foods/dough/wheat, create:dough, pasterdream:dough,
        // pasterdream:eggdough, mynethersdelight:ghast_dough}（cookery 空表不计），其中嵌套
        // #c:foods/dough/wheat={farmersdelight:wheat_dough, create:dough, pasterdream:dough,
        // pasterdream:eggdough}。tag 成员只能来自显式列出（replace 仅删不增），而 bakery:sweet_dough
        // 在全 libs 任何 jar 的任何 tag 文件中零出现（唯一 sweet_dough 字样=烘焙坊 bakeries:sweet_dough，
        // 不同 modid；letsdo-bakery 自身 data/c/tags 仅 bread/chocolate/container/cooked_beef/eggs/
        // jam/jams/knives/milk/seeds/water_bottles/wheat，无 dough 族）⇒ 任何加载顺序下
        // bakery:sweet_dough ∉ #c:dough ⇒ ∉ #bakery:dough，同物品不会入两组各计一次 presentCount，
        // 按候选物品集分组实现保留。回归防线=真机 sweep A11 baguette 用例（1 sweet_dough + 1
        // #bakery:dough 其他成员，断言两份都投——若未来 mod 更新令重叠成立该用例必红）。
        // 分组键=候选物品 id 排序拼接（Ingredient 实例不重写 equals——同 JSON 两份配料是不同
        // 实例，按实例分组会复现同缺陷；tag 配料按当前展开成员聚组，与 ChainResolver.ingredientKey
        // 同款口径）
        java.util.Map<String, Integer> requiredCount = new java.util.LinkedHashMap<>();
        java.util.Map<String, Ingredient> groupSample = new java.util.HashMap<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) {
                continue;
            }
            List<String> ids = new java.util.ArrayList<>();
            for (ItemStack item : ing.getItems()) {
                ids.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
            }
            java.util.Collections.sort(ids);
            String key = String.join("|", ids);
            requiredCount.merge(key, 1, Integer::sum);
            groupSample.putIfAbsent(key, ing);
        }
        for (var group : requiredCount.entrySet()) {
            Ingredient ingredient = groupSample.get(group.getKey());
            // presentCount：炉灶配料槽中已有该组配料的份数；need=还差几份
            int presentCount = 0;
            for (int s2 : stove.getIngredientSlots()) {
                if (ingredient.test(stove.getItem(s2))) {
                    presentCount++;
                }
            }
            int need = group.getValue() - presentCount;
            for (int slot : stove.getIngredientSlots()) {
                if (need <= 0) {
                    break;
                }
                if (!stove.getItem(slot).isEmpty() || ingredient.test(stove.getItem(slot))) {
                    continue;
                }
                ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(ingredient), true);
                if (!take.isEmpty()) {
                    stove.setItem(slot, take);
                    stove.setChanged();
                    maid.swing(InteractionHand.OFF_HAND);
                    need--;
                }
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
