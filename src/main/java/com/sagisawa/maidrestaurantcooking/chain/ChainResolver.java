package com.sagisawa.maidrestaurantcooking.chain;

import com.mastermarisa.maid_restaurant.utils.CookTasks;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 链式合成倒排索引 + 求解器（2026-10-25 计划书 S1）。
 *
 * <p>索引：「产物物品 id → CRAFTING 配方」按产物分组的倒排表一次性重建；查询为分组样本
 * 谓词测试（F-256：byResult 组内条目同产物，组样本命中即整组候选——免组内逐条目重复谓词测试；
 * 求解一次性触发、非每 tick——F-246 口径：非 O(1)，仍遍历组键可接受，StackPredicate 不暴露
 * 底层物品 id 无法直接按键定位）。收编口径：
 * {@code RecipeType.CRAFTING} 且 {@code CookTasks.getTask(type) != null}（本体公开 API，
 * all.java:8146-8148 实证）、配方为 {@link CraftingRecipe} 实例（F-239：与执行器接受面同构）、
 * 配料非全空（F-261：零/全空配料配方入索引只会生成必败死步；菜单面 getAllRecipeData 与
 * 执行器面 cookTick 同批过滤）、产物含 {@link DataComponents#FOOD} 组件（子合成产物限食物，
 * 防合成台配方空间污染——工具/盔甲/方块不可被子合成）。
 *
 * <p>失效（fork RecipeIndex.java:42-97 同款思路，代码自写不 import fork 类）：
 * {@link OnDatapackSyncEvent}（仅全量同步 player==null 时整表失效——F-269：玩家加入也 fire
 * 本事件但 player 非 null，玩家加入不改变服务端配方，忽略之防每次加入全表重建主线程尖峰）
 * + RecipeManager 实例身份比对兜底（绕过事件的重载路径换 manager 必重建）。
 * 纯服务端主线程访问，synchronized 仅作重入护栏。
 *
 * <p>求解：深度≤{@value #MAX_DEPTH}（原订单缺项为第 0 层，最深 3 层子合成）、配方 visited
 * 防环、每订单子合成总份数预算 {@value #STEP_BUDGET}；候选按产出数降序（更少份数优先）。
 * 候选回退（F-234）：零叶子（配料全可由库存满足）候选立采，否则取叶子总缺量最小的候选
 * （F-251：度量口径=Σ Missing.count 总缺量，非组数），全败才退化；候选间共享库存逐候选
 * 快照/回滚（F-250：防被淘汰候选的试算扣减污染后续候选与外层）。缺料扣减为多重集合口径
 * （F-190：同一谓词多份时跨槽扣减，非按槽独立计数）。
 * 解不出的缺项退化为「叶子」——由服务层从世界容器补货，补货失败才冷却。
 */
@EventBusSubscriber(modid = MaidRestaurantCooking.MOD_ID)
public final class ChainResolver {

    /** 子合成递归深度上限（原订单缺项为第 0 层）。 */
    public static final int MAX_DEPTH = 3;
    /** 每订单子合成总份数预算（防链爆炸）。 */
    public static final int STEP_BUDGET = 16;

    /** 缺项：谓词（测试口径）+ 稳定键（冷却/聚合）+ 份数（1 份=1 个物品，与本体 filterByCount 同口径）。 */
    public record Missing(StackPredicate predicate, String key, int count) {
    }

    /** 子合成一步：CRAFTING 配方 + 份数。 */
    public record Step(ResourceLocation recipeId, int crafts) {
    }

    /** 链计划：steps 依赖序（子合成先于父合成）；leaves=需世界补货的叶子缺项（同键已聚合）。 */
    public record Plan(List<Step> steps, List<Missing> leaves) {
        /** 子合成总份数（预算对账用）。 */
        public int totalCrafts() {
            int n = 0;
            for (Step s : steps) {
                n += s.crafts();
            }
            return n;
        }
    }

    /** 索引条目：配方 + 产物样例（谓词测试用）+ 单次产出数。 */
    private record Entry(RecipeHolder<?> holder, ItemStack sample, int resultCount) {
    }

    private record Snapshot(RecipeManager manager, Map<String, List<Entry>> byResult) {
    }

    /** 求解中间产物。 */
    private record Resolution(List<Step> steps, Map<String, Missing> leaves, int crafts) {
    }

    private static volatile Snapshot snapshot;

    private ChainResolver() {
    }

    /**
     * 数据包（重）加载完成 → 整表失效，下次查询懒重建。
     * F-269：本事件在<b>玩家加入</b>（player=加入者，非 null；neoforge-21.1.251-sources
     * PlayerList.java:205 placeNewPlayer 补丁点实证）与 <b>/reload 全量同步</b>（player=null；
     * 同源 PlayerList.java:902 reloadResources 实证）都会 fire——玩家加入不改变服务端配方，
     * 只在全量同步（{@code getPlayer() == null}）时置空快照；/reload 换 RecipeManager 实例
     * 另有 {@link #index} 身份比对兜底，双保险幂等。
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            snapshot = null;
        }
    }

    /**
     * 对原订单缺项求子合成链。
     *
     * @param misses    原订单缺项（通常来自 ItemHandlerUtils.filterByCount）
     * @param stockView 可用库存快照（调用方应传入原订单保留量出净后的盈余面，F-237）；求解只消耗其副本，不改原列表
     * @return 链计划；steps 可为空（纯补货单）。唯一 null 路径=misses 为空/null（F-240）：
     *         非空 misses 恒返回计划——预算耗尽走叶子降级不返回 null，调用方勿以 null 判预算耗尽
     */
    @Nullable
    public static Plan resolve(ServerLevel level, List<Missing> misses, List<ItemStack> stockView) {
        if (misses == null || misses.isEmpty()) {
            return null;
        }
        List<ItemStack> stock = new ArrayList<>();
        for (ItemStack s : stockView) {
            if (!s.isEmpty()) {
                stock.add(s.copy());
            }
        }
        // F-240：resolveRequired 对非空 required 恒返回 Resolution（resolveMissing 全败叶子化），无 null 分支
        Resolution r = resolveRequired(level, misses, stock, 0, new HashSet<>(), STEP_BUDGET);
        return new Plan(List.copyOf(r.steps()), List.copyOf(r.leaves().values()));
    }

    // ------------------------------------------------------------------ 求解

    private static Resolution resolveRequired(ServerLevel level, List<Missing> required, List<ItemStack> stock,
                                              int depth, Set<ResourceLocation> visited, int budget) {
        List<Step> steps = new ArrayList<>();
        Map<String, Missing> leaves = new LinkedHashMap<>();
        int used = 0;
        for (Missing miss : required) {
            int need = consumeFromStock(miss, stock);
            if (need <= 0) {
                continue;
            }
            // F-234/F-240：resolveMissing 恒返回 Resolution（候选回退或叶子化），无 null 分支
            Resolution sub = resolveMissing(level, miss, need, stock, depth, visited, budget - used);
            used += sub.crafts();
            steps.addAll(sub.steps());
            for (Map.Entry<String, Missing> e : sub.leaves().entrySet()) {
                leaves.merge(e.getKey(), e.getValue(),
                        (a, b) -> new Missing(a.predicate(), a.key(), a.count() + b.count()));
            }
        }
        return new Resolution(steps, leaves, used);
    }

    private static Resolution resolveMissing(ServerLevel level, Missing miss, int need, List<ItemStack> stock,
                                             int depth, Set<ResourceLocation> visited, int budget) {
        Resolution bestPartial = null;
        List<ItemStack> bestStock = null; // F-250：bestPartial 选中时的库存面，最终回填供外层继续求解
        int bestLeafDeficit = 0;          // F-251：bestPartial 的叶子总缺量（度量口径见下）
        if (depth < MAX_DEPTH && budget > 0) {
            for (Entry entry : candidatesFor(level, miss)) {
                if (visited.contains(entry.holder().id())) {
                    continue;
                }
                int crafts = ceilDiv(need, entry.resultCount());
                if (crafts > budget) {
                    continue; // 预算淘汰发生在扣库存之前，无污染
                }
                List<Missing> required = ingredientsOf(level, entry, crafts);
                if (required == null) {
                    continue; // 展开失败发生在扣库存之前，无污染
                }
                // F-250：候选共享可变 stock——试算前深拷贝快照，被淘汰（预算超限/非更优）即回滚，
                // 防零叶子可行解被前候选的试算扣减吞掉（「候选 B 耗尽 Y 致候选 A 误判有叶子」死角）
                List<ItemStack> snapshot = copyStock(stock);
                visited.add(entry.holder().id());
                Resolution sub = resolveRequired(level, required, stock, depth + 1, visited, budget - crafts);
                visited.remove(entry.holder().id());
                if (sub.crafts() + crafts > budget) {
                    restoreStock(stock, snapshot);
                    continue;
                }
                List<Step> steps = new ArrayList<>(sub.steps());
                steps.add(new Step(entry.holder().id(), crafts));
                Resolution full = new Resolution(steps, sub.leaves(), sub.crafts() + crafts);
                if (full.leaves().isEmpty()) {
                    // F-234：零叶子=全部配料可由库存满足——立采（「可行即选」真回退语义，
                    // 产出数降序下首个零叶子候选即最优可行解）；stock 保持本候选扣减后状态（选中即消费）
                    return full;
                }
                // 有叶子候选：保留叶子总缺量最小者继续试后续候选（F-251：度量=Σ Missing.count 总缺量，
                // 非叶子组数——「1 组缺 64」不应胜过「2 组各缺 1」，总缺量小者补货可行性更高；
                // 原实现首候选即返致回退路径死代码，F-234）
                int deficit = 0;
                for (Missing leaf : full.leaves().values()) {
                    deficit += leaf.count();
                }
                if (bestPartial == null || deficit < bestLeafDeficit) {
                    bestPartial = full;
                    bestLeafDeficit = deficit;
                    bestStock = copyStock(stock);
                }
                restoreStock(stock, snapshot); // 已记录（或未更优）一律回滚，试下个候选
            }
        }
        if (bestPartial != null) {
            // F-250：返回 bestPartial 时把 stock 回填到该候选扣减后的状态——外层 resolveRequired
            // 的后续缺项基于与计划一致的库存面继续求解（未被淘汰候选污染）
            restoreStock(stock, bestStock);
            return bestPartial;
        }
        // 无候选 / 候选全败（含 ingredientsOf 失败）/ 深度或预算超限 → 叶子（服务层从世界补货；补货失败才冷却）
        Map<String, Missing> leaves = new LinkedHashMap<>();
        leaves.put(miss.key(), miss);
        return new Resolution(List.of(), leaves, 0);
    }

    /**
     * 候选查询（F-256 分组样本口径）：byResult 组内条目同产物（build 按产物 id 分组），逐组以
     * 组样本测试谓词，命中即整组入候选——组内免逐条目重复谓词测试。非 O(1)：仍遍历组键，
     * StackPredicate 不暴露底层物品 id（本体谓词私有字段不可反解，all.java:9108-9158 实证），
     * 无法直接 byResult.get(id) 定位；tag/多物品谓词与本口径天然一致（组样本即产物）。
     * 产出数降序。
     */
    private static List<Entry> candidatesFor(ServerLevel level, Missing miss) {
        List<Entry> ans = new ArrayList<>();
        for (List<Entry> list : index(level).values()) {
            if (!list.isEmpty() && miss.predicate().test(list.getFirst().sample())) {
                ans.addAll(list);
            }
        }
        ans.sort(Comparator.comparingInt(Entry::resultCount).reversed());
        return ans;
    }

    /**
     * 配方 × crafts 的缺项展开。谓词取任务接口口径 {@code ICookTask.getIngredients}（防任务侧
     * 语义变化），稳定键取原始 {@code CraftingRecipe.getIngredients()}（过滤空后保序）。
     * 两列表长度不一致 = 任务配料口径被覆盖改动——WARN 响出（30s 窗，F-236）并按原始配料
     * 全量降级展开（F-235：CRAFTING 执行器 cookTick 即按 recipe.getIngredients 抽料，raw 是
     * 消耗真源；原 Math.min 截断会丢尾部配料致计划少料、预检停等）。
     */
    @Nullable
    private static List<Missing> ingredientsOf(ServerLevel level, Entry entry, int crafts) {
        var task = CookTasks.getTask(RecipeType.CRAFTING);
        if (task == null) {
            // build 时已判过；此处兜底响出（注册链路异常）
            ChainCraftService.errorWindow("resolve-crafting-task-missing",
                    "链式合成：CRAFTING 任务处理器缺失，无法展开配料");
            return null;
        }
        try {
            List<StackPredicate> predicates = task.getIngredients(entry.holder(), level);
            List<Ingredient> raw = entry.holder().value() instanceof CraftingRecipe cr
                    ? cr.getIngredients().stream().filter(i -> !i.isEmpty()).toList()
                    : List.of();
            boolean mismatch = raw.size() != predicates.size();
            if (mismatch) {
                ChainCraftService.warnWindow("resolve-ingredient-mismatch:" + entry.holder().id(),
                        "链式合成：配方 {} 任务配料数（{}）与原始配料数（{}）不一致，按原始配料降级展开",
                        entry.holder().id(), predicates.size(), raw.size());
            }
            // F-235：不一致时按 raw 全量展开（执行器消耗真源），不再 Math.min 截断
            int n = mismatch ? raw.size() : predicates.size();
            Map<String, Missing> merged = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                StackPredicate predicate = mismatch ? new StackPredicate(raw.get(i)) : predicates.get(i);
                Missing m = new Missing(predicate, ingredientKey(raw.get(i)), crafts);
                merged.merge(m.key(), m, (a, b) -> new Missing(a.predicate(), a.key(), a.count() + b.count()));
            }
            return new ArrayList<>(merged.values());
        } catch (Exception e) {
            // 求解一次性路径：异常必须响（响完该候选视为不可用，服务层叶子化补货）；30s 窗防重解刷屏（F-236）
            ChainCraftService.errorWindow("resolve-ingredients-error:" + entry.holder().id(),
                    "链式合成：配方 {} 配料展开异常：{}", entry.holder().id(), e.toString());
            return null;
        }
    }

    /** 模拟库存多重集合扣减（F-190 口径），返回仍缺份数。只动副本。 */
    private static int consumeFromStock(Missing miss, List<ItemStack> stock) {
        int need = miss.count();
        for (int i = 0; i < stock.size() && need > 0; i++) {
            ItemStack s = stock.get(i);
            if (!s.isEmpty() && miss.predicate().test(s)) {
                int take = Math.min(need, s.getCount());
                s.shrink(take);
                need -= take;
            }
        }
        return need;
    }

    /** F-250：库存深拷贝快照（consumeFromStock 原地 shrink 列表元素，必须元素级 copy）。 */
    private static List<ItemStack> copyStock(List<ItemStack> stock) {
        List<ItemStack> copy = new ArrayList<>(stock.size());
        for (ItemStack s : stock) {
            copy.add(s.copy());
        }
        return copy;
    }

    /** F-250：回滚到快照（原地清重填，保持调用方持有的同一列表实例）。 */
    private static void restoreStock(List<ItemStack> stock, List<ItemStack> snapshot) {
        stock.clear();
        stock.addAll(snapshot);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /** 配料稳定键：候选物品 id 排序拼接（tag 配料动态展开为当前成员，键随数据包变化自洽）。 */
    private static String ingredientKey(Ingredient ing) {
        ItemStack[] items = ing.getItems();
        if (items.length == 0) {
            return "empty@" + System.identityHashCode(ing);
        }
        List<String> ids = new ArrayList<>(items.length);
        for (ItemStack s : items) {
            ids.add(itemId(s));
        }
        Collections.sort(ids);
        return String.join("|", ids);
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    // ------------------------------------------------------------------ 索引

    private static Map<String, List<Entry>> index(ServerLevel level) {
        RecipeManager manager = level.getRecipeManager();
        Snapshot snap = snapshot;
        if (snap != null && snap.manager() == manager) {
            return snap.byResult();
        }
        synchronized (ChainResolver.class) {
            snap = snapshot;
            if (snap != null && snap.manager() == manager) {
                return snap.byResult();
            }
            Map<String, List<Entry>> byResult = build(level, manager);
            snapshot = new Snapshot(manager, byResult);
            return byResult;
        }
    }

    /**
     * 一次性遍历配方表建倒排索引（仅 CRAFTING + CraftingRecipe 实例 + 配料非全空（F-261）+
     * 产物含 FOOD；单配方异常不入索引且可见）。
     */
    private static Map<String, List<Entry>> build(ServerLevel level, RecipeManager manager) {
        if (CookTasks.getTask(RecipeType.CRAFTING) == null) {
            MaidRestaurantCooking.LOGGER.error(
                    "[kitchen-ready] 链式合成索引：CRAFTING 无任务处理器（注册链路异常），索引为空");
            return Map.of();
        }
        Map<String, List<Entry>> byResult = new HashMap<>();
        int skippedNonCrafting = 0;
        int skippedEmptyIngredients = 0;
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            try {
                if (holder.value().getType() != RecipeType.CRAFTING) {
                    continue;
                }
                // F-239：与执行器接受面同构——CraftingTableCookTask.getIngredients/cookTick 只接受
                // CraftingRecipe（CraftingTableCookTask.java:119/280-282）；非该实例的 CRAFTING 型
                // 配方入索引只会生成零配料必败步
                if (!(holder.value() instanceof CraftingRecipe cr)) {
                    skippedNonCrafting++;
                    continue;
                }
                // F-261：零/全空配料配方不入索引（自定义 CraftingRecipe 合法存在）——空缺项展开
                // 会被 resolveMissing 当「零叶子候选」立采生成必败死步，执行器静默 return 订单挂起；
                // 三面同批过滤（索引本面 + 菜单面 getAllRecipeData + 执行器面 cookTick errorStall）
                if (cr.getIngredients().stream().allMatch(Ingredient::isEmpty)) {
                    skippedEmptyIngredients++;
                    continue;
                }
                ItemStack result = holder.value().getResultItem(level.registryAccess());
                if (result == null || result.isEmpty() || !result.has(DataComponents.FOOD)) {
                    continue;
                }
                byResult.computeIfAbsent(itemId(result), k -> new ArrayList<>())
                        .add(new Entry(holder, result.copyWithCount(1), result.getCount()));
            } catch (Throwable t) {
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 链式合成索引：配方 {} 建索失败，跳过",
                        holder.id(), t);
            }
        }
        if (skippedNonCrafting > 0) {
            // F-275：危险/良性分列——非 CraftingRecipe 实例是 F-239 危险配方（菜单/执行器面均不接受、
            // 旧菜单面 CCE 源），ERROR 级独占预警，不再稀释进良性过滤计数
            MaidRestaurantCooking.LOGGER.error(
                    "[kitchen-ready] 链式合成索引：{} 条 CRAFTING 型配方非 CraftingRecipe 实例（F-239 危险配方，执行器/菜单面不接受），已跳过",
                    skippedNonCrafting);
        }
        if (skippedEmptyIngredients > 0) {
            // F-275：全空配料=良性数据包边缘（F-261 正常过滤），保持 WARN 级
            MaidRestaurantCooking.LOGGER.warn(
                    "[kitchen-ready] 链式合成索引：{} 条 CRAFTING 型配方配料全空（良性数据包边缘，F-261 过滤），已跳过",
                    skippedEmptyIngredients);
        }
        return byResult;
    }
}
