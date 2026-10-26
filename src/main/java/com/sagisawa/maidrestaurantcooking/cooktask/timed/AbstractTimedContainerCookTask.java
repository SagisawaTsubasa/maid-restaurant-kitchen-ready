package com.sagisawa.maidrestaurantcooking.cooktask.timed;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mastermarisa.maid_restaurant.api.ICookTask;
import com.mastermarisa.maid_restaurant.utils.BlockUsageManager;
import com.mastermarisa.maid_restaurant.utils.SearchUtils;
import com.mastermarisa.maid_restaurant.utils.component.RecipeData;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import com.sagisawa.maidrestaurantcooking.utils.RecipeDataFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * T3 定时容器模板基类（M2 实证定型，见 docs/模板设计-T1-T4.md）。
 *
 * <p>沉淀自竹匾/腌菜罐/冰箱三例的共同形态：目标方块无本体 POI →
 * SearchUtils 盒扫 + BlockUsageManager 占用过滤；getAllRecipeData = 全量扫描
 * 本配方类型 → RecipeData → 排除过滤器出口；getType() 统一走 recipeType()。
 * cookTick 因各 BE API 差异大，不在此抽象，由子类按实证写。
 *
 * <p>泛型注记：NeoForge 1.21.1 的 RecipeManager.getAllRecipesFor 签名为
 * {@code <I extends RecipeInput, T extends Recipe<I>>}（javap 实测），{@code R extends Recipe<?>}
 * 推断不过，故内部以 raw 调用 + unchecked 收窄。
 *
 * @param <R> 本类型配方类
 */
public abstract class AbstractTimedContainerCookTask<R extends Recipe<?>> implements ICookTask {

    /** 目标 BE 判定（子类给 instanceof 谓词，可附加如 isTop 等条件）。 */
    protected abstract Predicate<BlockPos> workBlockFilter(ServerLevel level);

    /** 本类型 RecipeType 实例（运行期取值，注册表已填充）。 */
    protected abstract RecipeType<?> recipeType();

    /** 配方类判定（byKey 结果 instanceof 校验用）。 */
    protected abstract Class<R> recipeClass();

    /** 配方产物（菜单展示与完成判定；须走目标 mod 公开访问器，禁碰 protected 字段）。 */
    protected abstract ItemStack recipeResult(R recipe, Level level);

    // ------------------------------------------------------------------ 日志窗

    /**
     * F-282/F-286：30s 窗表与 shouldLogKey（仿 StoveCookTask/CraftingTableCookTask 模式），protected 供
     * 未持自有窗表的子类直接复用（键=消息码:订单id:女仆UUID；任务每类型单例，键必须防跨单/跨女仆互吞，
     * F-196/F-202 同族教训）。Stove/Fermentation 等已持同名私表的子类属字段遮蔽，各用各的，互不影响；
     * 方法名取 shouldLogKey 避开其私有 shouldLog——同签名方法覆写不许收窄访问权（private 覆写
     * protected 编译失败），字段遮蔽无此限制。
     */
    protected final java.util.concurrent.ConcurrentHashMap<String, Long> logWindows = new java.util.concurrent.ConcurrentHashMap<>();
    protected static final long LOG_WINDOW_MS = 30_000L;

    /** 30s 窗内同键只响一次（过期条目顺手清理，F-241 同款）。 */
    protected boolean shouldLogKey(String code) {
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

    // ------------------------------------------------------------------ 接口面

    @Override
    public RecipeType<?> getType() {
        return recipeType();
    }

    @Nullable
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public BlockPos searchWorkBlock(ServerLevel level, EntityMaid maid, int horizontalSearchRange, int verticalSearchRange) {
        BlockPos center = maid.getBrainSearchPos();
        Predicate<BlockPos> filter = workBlockFilter(level);
        List<BlockPos> found = SearchUtils.search(
                center, horizontalSearchRange, verticalSearchRange,
                pos -> filter.test(pos) && BlockUsageManager.getUserCount(pos) <= 0
        );
        return !found.isEmpty() ? found.stream().min(Comparator.comparingDouble(p -> p.distSqr(maid.blockPosition()))).get() : null;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<RecipeData> getAllRecipeData(Level level) {
        List<RecipeData> ans = new ArrayList<>();
        // F-282（F-272 同族，与 CRAFTING 菜单面 CraftingTableCookTask.getAllRecipeData 同构硬化）：
        // ① 擦除安全遍历——增强 for 的 RecipeHolder<R> 编译期强转会在桥方法 checkcast 处对
        //    非 R 配方 CCE 崩菜单构建线程，改 RecipeHolder<?> 裸遍历；
        // ② recipeClass().isInstance 守卫（与执行器接受面 resolveRecipe 同判据）——同 RecipeType 下
        //    第三方注册的非本类配方跳过，不进菜单也不给 recipeResult 触发机会；
        // ③ per-recipe try/catch——坏配方 recipeResult/getIngredients 抛异常只跳过该条，30s 窗
        //    WARN 可见（键=menu-skip:配方id，与 CraftingTableCookTask crafting-menu-skip 同口径）；
        // ④ 全空配料配方不入菜单（F-261 同口径——可下单但执行器永不能核销、订单无声挂起）；
        //    判据=本任务 getIngredients（见循环内注释，勿换 Recipe#getIngredients 接口 default）。
        for (RecipeHolder<?> holder : (List<RecipeHolder<?>>) (List<?>) level.getRecipeManager().getAllRecipesFor((RecipeType) recipeType())) {
            if (!recipeClass().isInstance(holder.value())) {
                continue;
            }
            try {
                R recipe = recipeClass().cast(holder.value());
                // F-261/F-282：全空配料不入菜单——判据用本任务 getIngredients（与执行器/链服务缺项
                // 计算同源的 required 表，表空=订单永不能核销）。不用 Recipe#getIngredients 接口
                // default：runServer 冒烟 R5 实证 FID FridgeFreezingRecipe 等自定义配料访问器配方
                // 不覆写它（恒空表），按它过滤会把 FidFridge 整族菜单误杀（0 条回归）。
                if (getIngredients(holder, level).isEmpty()) {
                    continue;
                }
                ans.add(new RecipeData(holder.id(), recipeType(), this.getIcon(), recipeResult(recipe, level)));
            } catch (Throwable t) {
                if (shouldLogKey("menu-skip:" + holder.id())) {
                    MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] {} 配方 {} 菜单建项失败，跳过：{}", getUID(), holder.id(), t);
                }
            }
        }
        return RecipeDataFilter.apply(ans);
    }

    /** byKey 解析 + 配方类校验；不符返回 null（cookTick 里 ERROR 落日志）。 */
    @Nullable
    @SuppressWarnings("unchecked")
    protected R resolveRecipe(ServerLevel level, com.mastermarisa.maid_restaurant.request.CookRequest request) {
        RecipeHolder<?> holder = level.getRecipeManager().byKey(request.id).orElse(null);
        // F-130+F-131：类匹配之外再校验 RecipeType 归属，防跨类型同名 id 错配。
        // 判据成立性：每个任务实例的 recipeType() 为固定单值——"共享配方类的双类型任务"
        // （如 ChinesefoodFreezer 的 Freezing/Refrigerating、FidFridge 的 Freezing/Tempering）
        // 是独立实例各自返回自己的类型，类共享不等于类型共享，此处校验无回归。
        if (holder == null || !recipeClass().isInstance(holder.value())
                || (recipeType() != null && !recipeType().equals(holder.value().getType()))) {
            return null;
        }
        return (R) holder.value();
    }
}
