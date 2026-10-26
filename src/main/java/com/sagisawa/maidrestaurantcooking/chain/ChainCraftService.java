package com.sagisawa.maidrestaurantcooking.chain;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.BehaviorUtils;
import com.mastermarisa.maid_restaurant.utils.CookTasks;
import com.mastermarisa.maid_restaurant.utils.ItemHandlerUtils;
import com.mastermarisa.maid_restaurant.utils.MaidStorages;
import com.mastermarisa.maid_restaurant.utils.RequestManager;
import com.mastermarisa.maid_restaurant.utils.SearchUtils;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 链式合成无 fork 自驱服务（2026-10-25 计划书 S2/S3）。
 *
 * <p>触发：无营业 fork 环境（AUTO 门：{@code ModList.isLoaded("maid_restaurant_business")} 为真
 * 即不启动，防与 fork V2 取料腿竞抽数据；fork 集成后走 ChainCraftApi 软依赖）。
 *
 * <p>流程（每 {@value #SCAN_INTERVAL_TICKS} tick 一轮，全维度）：持单女仆扫描
 * （{@code RequestManager.peek(maid, CookRequest.TYPE)}，all.java:8851-8866 公开实证）→
 * 缺项计算（任务接口 {@code getIngredients} × remain − 女仆背包，复用本体
 * {@code filterByCount/filterByCountStockpot} 公开面，stockpot 特判同上游
 * MaidGetFromStorageTask 口径 all.java:6084）→ 顶层冷却过滤（F-252：冷却前置，全冷却即跳过
 * 本轮求解，不调 ChainResolver.resolve）→ ChainResolver 求解
 * （F-231/F-237：缺项传净额，库存传「背包−原订单保留量」盈余副本——求解器只扣盈余，
 * 不双重扣减也不蚕食原订单原料）→ 叶子冷却复核（F-233/F-268：深层叶子键与顶层键双命名空间
 * 都查，命中则顶层缺项同步上冷再跳过）→ 叶子补货（{@code MaidStorages.tryGetHandler} 判定 +
 * 按槽循环三面上限安全抽取——F-253：不复用上游 tryTakeFrom，其负抽取分支会使存储侧物品复制，
 * 实证 NeoForge 21.1.251 ItemStackHandler.java:104-121；搜索中心同
 * {@code BehaviorUtils.getSearchPos}（F-247：与 MaidGetFromStorageTask:91 同源），
 * verticalRange=2 同其构造点 :5416；F-263：回退余量落地、原因三态区分）→ 子合成驱动
 * （{@code CookTasks.getTask(CRAFTING).cookTick} + 内存构造 CookRequest，只读 id/remain
 * 实证 CraftingTableCookTask.java:278-374）→ 原订单缺失清单对账清零。
 * 补货不足即停：顶层+叶子缺项同轮上冷、本轮不驱动（F-249：防子合成齐备预检吃掉原订单
 * 保留在包内的同种原料）。
 *
 * <p>无状态设计：除冷却 map 外零跨 tick 状态——每轮按背包实况重算，天然吸收本体核销/
 * 女仆偷吃/中途耗尽等漂移。不可解或补货不足 → 按「女仆UUID:缺项键」冷却
 * （防多女仆同键互吞；不重试风暴）；管线异常 → 女仆级 pipeline-error 键（F-270）；
 * 全空配料订单 → 订单id#empty 专用键（F-278）。冷却 map 定期清理过期 + 容量硬顶。
 *
 * <p>内存请求生命周期（F-221 判据）：驱动用 CookRequest 为局部变量，随方法返回即弃，
 * 绝不写入 CookRequestHandler/WorldCookRequestHandler/attachment 等任何持久化面。
 */
@EventBusSubscriber(modid = MaidRestaurantCooking.MOD_ID)
public final class ChainCraftService {

    /** 扫描节流（tick）。 */
    private static final int SCAN_INTERVAL_TICKS = 20;
    /** 不可解/补货不足冷却（tick，10s；期间该女仆该缺项不重试）。 */
    private static final int MISSING_COOLDOWN_TICKS = 200;
    /** 冷却 map 容量硬顶（异常风暴保险丝，超顶清过期再清全表）。 */
    private static final int COOLDOWN_MAX = 1024;
    /** 单步驱动同 tick 合成次数上限（防异常配方死循环；正常 remain≤crafts≤预算）。 */
    private static final int DRIVE_LOOP_CAP = 32;
    /** 盒扫垂直半径（同上游 MaidGetFromStorageTask 构造 :5416）。 */
    private static final int VERTICAL_RANGE = 2;

    /** 不可解冷却：键 = 女仆UUID + ":" + 缺项键，值 = 解除 game tick。 */
    private static final Map<String, Long> COOLDOWN = new HashMap<>();

    /** 窗口化日志（30s 窗去重，仿 CraftingTableCookTask F-170 模式）。 */
    private final ConcurrentHashMap<String, Long> logWindows = new ConcurrentHashMap<>();
    private static final long LOG_WINDOW_MS = 30_000L;

    private ChainCraftService() {
    }

    /** AUTO 门：fork 在场则自驱服务整体不启动（每轮运行时判定，不读构造期 config）。 */
    private static boolean businessPresent() {
        return ModList.get().isLoaded("maid_restaurant_business");
    }

    /** 服务启停可见性：首次过门扫描时 INFO 一次（runServer 冒烟判据）。 */
    private static volatile boolean announced = false;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long tick = server.getTickCount();
        // 服务单例守门（config 运行时读取在此层做，S4 接线 chainCrafting.enabled）
        if (tick % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        if (businessPresent() || !com.sagisawa.maidrestaurantcooking.config.Config.CHAIN_CRAFTING_ENABLED.get()) {
            return;
        }
        if (!announced) {
            announced = true;
            MaidRestaurantCooking.LOGGER.info("[kitchen-ready] 链式合成：无 fork 环境，自驱服务活跃（每 {} tick 扫描）",
                    SCAN_INTERVAL_TICKS);
        }
        instance().scan(server, tick);
    }

    /** 状态配对：服务端停止清冷却表与启停标记（冷却表为纯节流面，清空无副作用）。 */
    @SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        if (announced) {
            announced = false;
            MaidRestaurantCooking.LOGGER.info("[kitchen-ready] 链式合成：自驱服务随服务端停止，冷却表已清（{} 项）",
                    COOLDOWN.size());
        }
        COOLDOWN.clear();
    }

    // ------------------------------------------------------------------ 扫描

    private void scan(MinecraftServer server, long tick) {
        purgeCooldown(tick);
        for (ServerLevel level : server.getAllLevels()) {
            AABB box = new AABB(-29_999_984, level.getMinBuildHeight(), -29_999_984,
                    29_999_984, level.getMaxBuildHeight(), 29_999_984);
            for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class, box)) {
                // F-270：pipeline-error 冷却前置（实现取小者：scan catch 上 maid 级冷却，不做分阶段 try）——
                // 持久异常若只靠 WARN 窗会以 1Hz 重扫/重解（F-233/F-252 同族；30s 窗只压显示压不住频率）；
                // 冷却女仆本轮连 peek 都不做
                if (isCooling(maid.getUUID(), "pipeline-error", tick)) {
                    continue;
                }
                // R9 复核顺手项：peek 移入 try——RequestManager.peek 若可抛（本体账本/attachment 异常态），
                // 异常不再逃出 scan 拖垮整轮扫描（与 tickMaid 同款单女仆隔离）。request 在 catch 可能为
                // null（peek 阶段抛出、尚未解析成功），键与文案退化为 "-" 占位
                CookRequest request = null;
                try {
                    if (!(RequestManager.peek(maid, CookRequest.TYPE) instanceof CookRequest peeked)) {
                        continue;
                    }
                    request = peeked;
                    tickMaid(level, maid, request, tick);
                } catch (Exception e) {
                    // 单女仆异常不拖垮整轮扫描；必须可见（30s 窗防 20Hz 刷屏）
                    // F-270：异常同轮上 maid 级 pipeline-error 冷却（键走既有冷却表，随 purge 过期）
                    COOLDOWN.put(maid.getUUID() + ":pipeline-error", tick + MISSING_COOLDOWN_TICKS);
                    // F-273：键补订单 id（catch 处 request 在作用域内）——同女仆跨单异常不再互吞（F-258 口径）
                    warnWindow("maid-exception:" + maid.getUUID() + ":" + (request != null ? request.id : "-"),
                            "链式合成：女仆 {} 订单 {} 处理异常（管线冷却 {} tick）：{}",
                            maid.getName().getString(), request != null ? request.id : "-", MISSING_COOLDOWN_TICKS, e.toString());
                }
            }
        }
    }

    private void tickMaid(ServerLevel level, EntityMaid maid, CookRequest request, long tick) {
        if (request.type == null) {
            // F-245：type=null 会让 getTask(null) 先 NPE（taskMap=ConcurrentHashMap，CookTasks all.java:19 实证）
            // ——F-221 同族（flex 回滚残留等类型缺失订单）专用窗口 WARN，不介入
            warnWindow("type-null:" + request.id + ":" + maid.getUUID(),
                    "链式合成：女仆 {} 订单 {} 任务类型缺失（可能为回滚残留），不介入",
                    maid.getName().getString(), request.id);
            return;
        }
        var task = CookTasks.getTask(request.type);
        if (task == null) {
            // F-245 注释说明（防审计误判为漏覆盖）：非烹饪类请求（上游/他包体系）不介入——
            // 上游 CookTasks 用同一张 taskMap（all.java:19/36-37），未注册类型的订单上游体系同样无法烹饪，
            // 介入与否由上游体系自管；此处 WARN 会对正常外来订单刷假警，故保持静默跳过
            return;
        }
        RecipeHolder<?> holder = level.getRecipeManager().byKey(request.id).orElse(null);
        if (holder == null) {
            // F-245：配方被 /reload 删除等——原任务 cookTick 自会响配方缺失，但女仆不在烹饪 tick 时
            // 原任务不 tick，窗口 WARN 留诊断
            warnWindow("recipe-missing:" + request.id + ":" + maid.getUUID(),
                    "链式合成：女仆 {} 订单 {} 配方缺失（可能已被 /reload 删除），不介入",
                    maid.getName().getString(), request.id);
            return;
        }

        // F-278：全空配料订单冷却前置（键=订单id#empty——此类订单 required 恒空、无 miss 键可挂，
        // 走专用键防每 20 tick 重进本路径；复用冷却表随 purge 过期）
        if (isCooling(maid.getUUID(), request.id + "#empty", tick)) {
            return;
        }
        // 缺项计算（背包口径；stockpot 特判同上游 MaidGetFromStorageTask all.java:6084）
        List<StackPredicate> required = task.getIngredients(holder, level);
        if (required.isEmpty()) {
            // F-278（F-261 第五面）：非 CraftingRecipe 配方/全空配料订单（/reload 前旧单、fork 构造单）
            // 不介入但必须可见——女仆不在烹饪位时原任务 cookTick 不跑（crafting-no-ingredients errorStall
            // 不响），静默 return 即订单无声挂起。窗口 WARN 键带订单+女仆 + 同轮上专用冷却
            COOLDOWN.put(maid.getUUID() + ":" + request.id + "#empty", tick + MISSING_COOLDOWN_TICKS);
            warnWindow("order-empty-ingredients:" + request.id + ":" + maid.getUUID(),
                    "链式合成：女仆 {} 订单 {} 配料表为空（非 CraftingRecipe 配方或配料全空，执行器不接受），冷却 {} tick 复检",
                    maid.getName().getString(), request.id, MISSING_COOLDOWN_TICKS);
            return;
        }
        IItemHandler inv = maid.getAvailableInv(false);
        List<ItemStack> stacks = ItemHandlerUtils.toStacks(inv);
        boolean stockpot = task.getType().equals(ModRecipes.STOCKPOT_RECIPE);
        // filterByCount 契约=净缺额（内部对 handler 副本贪心扣减后只返 remain>0 项，all.java:279-310 实证）
        // ——净额仅作早退与冷却键面，求解器输入另配盈余库存（F-231：勿再对同一背包重复扣减）
        List<Pair<StackPredicate, Integer>> missing = stockpot
                ? ItemHandlerUtils.filterByCountStockpot(required, stacks, request.remain)
                : ItemHandlerUtils.filterByCount(required, stacks, request.remain);
        if (missing.isEmpty()) {
            return; // 原订单在正常推进，不介入
        }

        // 组装求解缺项（F-232 稳定键=订单id#required槽位：pair.left 即 required 表内同一对象，
        // indexOf 跨轮稳定，不随过滤后列表漂移；重复谓词共享首槽可接受）。
        // F-252：顶层冷却检查即「resolve 前置」——命中键不入 misses，全命中则本轮不调 ChainResolver.resolve
        List<ChainResolver.Missing> misses = new ArrayList<>();
        for (Pair<StackPredicate, Integer> pair : missing) {
            ChainResolver.Missing m = new ChainResolver.Missing(
                    pair.left(), missingKey(request, required, pair.left()), pair.right());
            if (!isCooling(maid.getUUID(), m.key(), tick)) {
                misses.add(m);
            }
        }
        if (misses.isEmpty()) {
            // F-252：顶层缺项全冷却 = 本轮跳过求解（restock 失败/drive 仍缺时顶层键已随叶键同轮上冷）。
            // F-276：预期节流信号走 infoWindow（原 WARN 使「窗口 INFO」注释/sweep 口径失实——操作员按
            // [INFO] 过滤看不到会误判失败）。F-265：求解节拍改凭 DEBUG chain.resolve.count 逐轮行判定
            // （「10s 内链计划 INFO 零增长」被 30s 窗自证恒真，已废）
            infoWindow("cooldown-skip:" + maid.getUUID() + ":" + request.id,
                    "链式合成：女仆 {} 订单 {} 缺项 {} 组全部冷却中，本轮跳过求解（F-233/F-252 重解抑制）",
                    maid.getName().getString(), request.id, missing.size());
            return;
        }

        // 求解（F-231 缺项传净额 + F-237 库存传盈余副本：consumeFromStock 只扣盈余，既不重复扣减
        // 也不蚕食原订单已备原料；F-240：非空 misses 恒返回计划——唯一 null 路径=空 misses 已在上方排除）
        ChainResolver.Plan plan = ChainResolver.resolve(level, misses,
                surplusAfterReserve(stacks, required, request.remain, stockpot));

        // F-265：逐轮 DEBUG 行（不走 shouldLog）——真机 sweep G2 凭本行可观测求解节拍：10s 冷却下
        // 60s 内 6–7 行=有界（观测窗起点对齐时 7，F-287：与 sweep G2 口径统一，勿按旧「≤6」误判红）；
        // 1Hz 重解环则 ~60 行=回归。DEBUG 级默认不可见，sweep 时开 debug。
        // 全链唯一非窗口化逐轮行（R8-F-265 指定观测面）
        MaidRestaurantCooking.LOGGER.debug("[kitchen-ready] 链式合成：chain.resolve.count 女仆 {} 订单 {} 缺项 {} 组",
                maid.getName().getString(), request.id, misses.size());

        // F-233/F-268：深层叶子冷却键（ingredientKey 物品键）与顶层检查键（订单id#槽位）不同命名空间——
        // 求解后对叶子键补冷却检查，命中则顶层 misses 同步上冷再跳过（F-268：只 return 不上冷时，
        // miss 集在冷却期内变化——女仆偷吃/净额增减/同键多单——会绕过顶层前置冷却，仍 1 次/秒重解），
        // 并留窗口 INFO 供观察
        for (ChainResolver.Missing leaf : plan.leaves()) {
            if (isCooling(maid.getUUID(), leaf.key(), tick)) {
                cooldownAll(maid.getUUID(), misses, tick);
                // F-276：同 cooldown-skip，改 infoWindow（「并留窗口 INFO 供观察」注释至此成立）
                infoWindow("leaf-cooling-skip:" + maid.getUUID() + ":" + request.id,
                        "链式合成：女仆 {} 订单 {} 深层叶子 {} 冷却中，顶层缺项 {} 组同步上冷，本轮跳过补货/驱动",
                        maid.getName().getString(), request.id, leaf.key(), misses.size());
                return;
            }
        }

        // F-236：计划 INFO 走 30s 窗（drive 停等/深层叶子无源时防每 20 tick 刷屏）
        if (shouldLog("plan:" + maid.getUUID() + ":" + request.id)) {
            MaidRestaurantCooking.LOGGER.info(
                    "[kitchen-ready] 链式合成：女仆 {} 订单 {} 缺项 {} 组 → 链计划 {} 步（共 {} 次合成）+ 补货叶子 {} 组",
                    maid.getName().getString(), request.id, misses.size(),
                    plan.steps().size(), plan.totalCrafts(), plan.leaves().size());
        }

        // 补货叶子（盒扫 storage → 就近按槽安全抽取；F-263：结果带真实原因；F-273：键身份=链签名）
        RestockResult restock = restock(level, maid, plan.leaves(), chainSig(plan, request.id));
        List<ChainResolver.Missing> stillMissing = restock.still();
        if (!stillMissing.isEmpty()) {
            cooldownAll(maid.getUUID(), stillMissing, tick);
            // F-252：顶层缺项键同轮上冷（与叶键双命名空间都盖住）——下一轮 misses 过滤即空，resolve 前置跳过
            cooldownAll(maid.getUUID(), misses, tick);
            // F-263：WARN 按真实原因区分（背包满/附近无源/抽取边界三态），不再一律误报「附近无源」
            warnWindow("restock-short:" + maid.getUUID() + ":" + request.id,
                    "链式合成：女仆 {} 补货不足 {} 组（{}），冷却 {} tick 重试：订单 {}",
                    maid.getName().getString(), stillMissing.size(), restock.reason(),
                    MISSING_COOLDOWN_TICKS, request.id);
            // F-249：补货不足即停（不驱动）——子合成的齐备预检只看槽位有无，计划叶子没补到时，
            // 原订单保留在包内的同种原料会被子合成吃掉（F-237 失败分支回归）。部分推进的价值
            // 低于蚕食原订单，等冷却过期重解。README「已防护」口径同步为求解面+执行面双实现
            // （自驱服务本面 + API 门面 F-260 同款）。
            return;
        }

        // 驱动子合成链（内存请求；F-267：drive-done 窗键带订单 id 链签名）
        boolean driven = driveSteps(level, maid, plan, request.id);

        // 原订单缺失清单对账
        List<ItemStack> after = ItemHandlerUtils.toStacks(maid.getAvailableInv(false));
        RecipeHolder<?> holderAfter = level.getRecipeManager().byKey(request.id).orElse(null);
        if (holderAfter != null) {
            List<StackPredicate> requiredAfter = task.getIngredients(holderAfter, level);
            List<Pair<StackPredicate, Integer>> remain = stockpot
                    ? ItemHandlerUtils.filterByCountStockpot(requiredAfter, after, request.remain)
                    : ItemHandlerUtils.filterByCount(requiredAfter, after, request.remain);
            if (remain.isEmpty()) {
                // F-258：清零 INFO 走 30s 窗（链反复成功/重解时防刷屏；键带订单 id 防跨单互吞）
                if (shouldLog("cleared:" + maid.getUUID() + ":" + request.id)) {
                    MaidRestaurantCooking.LOGGER.info(
                            "[kitchen-ready] 链式合成：女仆 {} 订单 {} 缺失清单清零（驱动 {}），原订单恢复自驱",
                            maid.getName().getString(), request.id, driven ? "完成" : "部分");
                }
            } else if (driven) {
                // F-238：驱动完成仍缺（设备内容物口径/中途被偷吃等）——必须响，不静默冷却
                List<ChainResolver.Missing> remainMisses = toMisses(request, requiredAfter, remain);
                cooldownAll(maid.getUUID(), remainMisses, tick);
                warnWindow("drive-still-missing:" + maid.getUUID() + ":" + request.id,
                        "链式合成：女仆 {} 驱动完成仍缺 {} 组（{}），冷却 {} tick 重解",
                        maid.getName().getString(), remainMisses.size(),
                        remainMisses.stream().map(ChainResolver.Missing::key).toList(),
                        MISSING_COOLDOWN_TICKS);
            } else {
                // F-261：驱动失败（零配料死步/matches 不符等致步未核销）不冷却 = 下一轮同一 miss 集
                // 再进 resolve 形成 1 次/秒重解环——顶层 misses 同轮上冷 + 独立窗口 WARN
                cooldownAll(maid.getUUID(), misses, tick);
                warnWindow("drive-failed:" + maid.getUUID() + ":" + request.id,
                        "链式合成：女仆 {} 订单 {} 子步驱动未完成（缺料或异常配方），顶层缺项 {} 组冷却 {} tick 重解",
                        maid.getName().getString(), request.id, misses.size(), MISSING_COOLDOWN_TICKS);
            }
        }
    }

    /** 冷却稳定键（F-232）：订单id#required槽位（predicate 即 required 表内同一对象，indexOf 跨轮稳定）。 */
    private static String missingKey(CookRequest request, List<StackPredicate> required, StackPredicate predicate) {
        return request.id + "#" + required.indexOf(predicate);
    }

    private static List<ChainResolver.Missing> toMisses(CookRequest request, List<StackPredicate> required,
                                                        List<Pair<StackPredicate, Integer>> missing) {
        List<ChainResolver.Missing> ans = new ArrayList<>();
        for (Pair<StackPredicate, Integer> pair : missing) {
            ans.add(new ChainResolver.Missing(pair.left(), missingKey(request, required, pair.left()), pair.right()));
        }
        return ans;
    }

    /**
     * 背包盈余副本（F-237）：对 required×remain 做与 filterByCount 同款贪心扣减（stockpot 水桶
     * 特判同 filterByCountStockpot 固定 2 桶口径，all.java:324-328），剩余即子合成可用库存——
     * 原订单已备原料保留不外借，子合成不反向蚕食。只动副本，不改原列表。
     */
    private static List<ItemStack> surplusAfterReserve(List<ItemStack> stacks, List<StackPredicate> required,
                                                       int remain, boolean stockpot) {
        List<ItemStack> surplus = new ArrayList<>(stacks.size());
        for (ItemStack s : stacks) {
            if (!s.isEmpty()) {
                surplus.add(s.copy());
            }
        }
        int[] reserved = new int[required.size()];
        for (int i = 0; i < reserved.length; i++) {
            reserved[i] = stockpot && required.get(i).test(new ItemStack(Items.WATER_BUCKET)) ? 2 : remain;
        }
        for (ItemStack s : surplus) {
            for (int i = 0; i < reserved.length; i++) {
                if (reserved[i] > 0 && required.get(i).test(s)) {
                    int take = Math.min(reserved[i], s.getCount());
                    s.shrink(take);
                    reserved[i] -= take;
                }
            }
        }
        surplus.removeIf(ItemStack::isEmpty);
        return surplus;
    }

    // ------------------------------------------------------------------ 补货

    /** 补货结果（F-263）：仍未补满的缺项 + 未补满真实原因摘要（背包满/附近无源/抽取边界计数）。 */
    public record RestockResult(List<ChainResolver.Missing> still, String reason) {
    }

    /**
     * 叶子补货：{@code SearchUtils.search} 盒扫（中心={@code BehaviorUtils.getSearchPos(maid)}，
     * 与上游取料 MaidGetFromStorageTask:91 同源——hasRestriction?restrictCenter:blockPosition().below()
     * 实证 BehaviorUtils.java:35-37；勿换 getBrainSearchPos：TLM 1.5.3 javap 实证其无限制回落
     * 为 blockPosition() 无 .below()，不同源），半径=restrictRadius，垂直=2，storage 判定=
     * {@code MaidStorages.tryGetHandler(level,pos)} 非 null 且 {@code isStackIn} 命中；
     * 就近逐容器按槽循环安全抽取（F-253：三面上限 min(需求余量, 槽存量, 背包余量 simulate)——
     * 上游 {@code tryTakeFrom} 的「槽存量≤需求」分支以需求量模拟插入（all.java:8582
     * {@code copyWithCount(count)}），包内余量不足时 extract 量为负（all.java:8584），
     * NeoForge 21.1.251 {@code ItemStackHandler.extractItem} 对负 amount 直通 else 分支使槽位
     * 数量增加（ItemStackHandler.java:104-121 实证，物品复制）且 countMatches 对账只看女仆包
     * 发现不了存储侧变化，故不复用）。
     *
     * <p>F-263：①插入回退后存储侧仍拒收的余量（back）按 {@code DeliveryUtil.deliverToMaidInv}
     * 同款溢出语义落地（{@code spawnAtLocation} + 0 拾取延迟，女仆拾取 AI 收集）——旧实现
     * 滞留交换面即数据丢失；②返回 {@link RestockResult}，未补满按真实约束面归因三态：
     * 背包满（fits=0 为约束面）＞抽取边界（extract 空返回/退回边界）＞附近无源（含源存量不足），
     * 供 restock-short WARN 区分文案，不再把背包满误报成「附近无源」。
     *
     * <p>F-285（R9 发现 5）：背包满面补第四态证据——① simulate 段 {@code fits<take}（包只收得下
     * 一段，槽里还有富余）与 ② 实插段 {@code leftover 非空且存储全收 back}（溢出纯因包满）都置
     * bagBlocked；旧实现两态都漏归「附近无源」，操作员按「去补货源」行动会白跑。back 非空
     * （存储拒收）仍归抽取边界，F-274 三态语义保持。
     *
     * @param orderSig 窗口键身份（F-273/F-274）：自驱面=订单 id，API 面=链签名（{@link #chainSig}），
     *                 restock-leftover 键带之防同女仆跨单互吞
     */
    static RestockResult restock(ServerLevel level, EntityMaid maid,
                                                       List<ChainResolver.Missing> leaves, String orderSig) {
        List<ChainResolver.Missing> still = new ArrayList<>();
        int bagFullCount = 0;
        int noSourceCount = 0;
        int boundaryCount = 0;
        BlockPos center = BehaviorUtils.getSearchPos(maid);
        int range = (int) maid.getRestrictRadius();
        IItemHandler inv = maid.getAvailableInv(false);
        for (ChainResolver.Missing miss : leaves) {
            int need = miss.count();
            boolean bagBlocked = false;      // 未补满的约束面=女仆背包收不下
            boolean extractBoundary = false; // 命中抽取/退回并发边界态
            List<BlockPos> found = SearchUtils.search(center, range, VERTICAL_RANGE, pos -> {
                IItemHandler h = MaidStorages.tryGetHandler(level, pos);
                return h != null && ItemHandlerUtils.isStackIn(h, miss.predicate());
            });
            found.sort(Comparator.comparingDouble(p -> p.distSqr(maid.blockPosition())));
            for (BlockPos pos : found) {
                if (need <= 0) {
                    break;
                }
                IItemHandler store = MaidStorages.tryGetHandler(level, pos);
                if (store == null) {
                    // F-277：判定与抽取间实例消失——换下家；本叶子最终未补满时按「附近无源」归因
                    // （消失容器只是本轮快照曾经命中，行动指引同为「去补货源」，不单列；原 anyFound
                    // 死变量已删）
                    continue;
                }
                // F-253：按槽循环安全抽取——三面上限 min(需求余量, 槽存量, 背包余量 simulate)，
                // 永不向 extractItem 传非正数，杜绝上游负抽取复制路径
                for (int s = 0; s < store.getSlots() && need > 0; s++) {
                    ItemStack slotStack = store.getStackInSlot(s);
                    if (slotStack.isEmpty() || !miss.predicate().test(slotStack)) {
                        continue;
                    }
                    int take = Math.min(need, slotStack.getCount());
                    int fits = take - ItemHandlerHelper.insertItemStacked(inv, slotStack.copyWithCount(take), true).getCount();
                    if (fits <= 0) {
                        bagBlocked = true;
                        continue; // 女仆包该槽收不下任何一份，换下槽（下家容器由外层继续）
                    }
                    ItemStack extracted = store.extractItem(s, fits, false);
                    if (extracted.isEmpty()) {
                        extractBoundary = true;
                        continue; // 槽实况与快照不符（并发变动），换下槽
                    }
                    // F-285：simulate 已显示包只能收 fits<take（槽里还有富余收不下）——背包容量边界
                    // 在本槽触顶，本叶子最终未补满时归因背包满（旧实现漏归「附近无源」）
                    boolean partialInsert = fits < take;
                    ItemStack leftover = ItemHandlerHelper.insertItemStacked(inv, extracted, false);
                    if (!leftover.isEmpty()) {
                        // 抽出但放不下（抽取与插入间并发边界态）——原槽退回（错误必须响）。
                        // F-274：归因修正——bagBlocked 只在 fits<=0（背包完全收不下）置位，部分插入的
                        // 真约束可能是存储拒收，报「背包满」会误导玩家清背包；extractBoundary 只在
                        // 存储仍拒收（back 非空）或 extract 空返回时置位
                        ItemStack back = store.insertItem(s, leftover, false);
                        if (!back.isEmpty()) {
                            extractBoundary = true;
                            // F-263：存储侧仍拒收的余量按 DeliveryUtil 溢出语义落地
                            // （spawnAtLocation + 0 拾取延迟，女仆拾取 AI 收集）——不再滞留交换面丢失
                            ItemEntity dropped = maid.spawnAtLocation(back);
                            if (dropped != null) {
                                dropped.setPickUpDelay(0);
                            }
                            // F-274：实退=reinserted（leftover 计数含落地部分，非全部退回存储）；退回/落地
                            // 分列两数；back 空时存储全收不再打 false 子句（本分支仅 back 非空可达）；
                            // 键补订单身份（F-273）
                            int reinserted = leftover.getCount() - back.getCount();
                            warnWindow("restock-leftover:" + maid.getUUID() + ":" + orderSig,
                                    "链式合成：女仆 {} 补货插入遇包满边界，退回存储 {}×{}，落地 {}×{}：订单 {}",
                                    maid.getName().getString(), leftover.getItem(), reinserted,
                                    back.getItem(), back.getCount(), orderSig);
                        } else {
                            // F-285：存储全收余量=溢出纯因包满，同归背包满（back 非空才计抽取边界，
                            // F-274 三态语义保持）
                            bagBlocked = true;
                        }
                    }
                    if (partialInsert) {
                        bagBlocked = true;
                    }
                    need -= extracted.getCount() - leftover.getCount();
                }
            }
            if (need > 0) {
                still.add(new ChainResolver.Missing(miss.predicate(), miss.key(), need));
                // F-263：归因优先级 背包满 > 抽取边界 > 附近无源（有源但存量耗尽同归无源——
                // 对玩家的行动指引一致：去补货源）；F-285：partialInsert/back 全收两态并入背包满
                if (bagBlocked) {
                    bagFullCount++;
                } else if (extractBoundary) {
                    boundaryCount++;
                } else {
                    noSourceCount++;
                }
            }
        }
        List<String> parts = new ArrayList<>(3);
        if (bagFullCount > 0) {
            parts.add("背包满×" + bagFullCount);
        }
        if (noSourceCount > 0) {
            parts.add("附近无源×" + noSourceCount);
        }
        if (boundaryCount > 0) {
            parts.add("抽取边界×" + boundaryCount);
        }
        return new RestockResult(still, String.join("、", parts));
    }

    // ------------------------------------------------------------------ 驱动

    /**
     * 子合成驱动：内存构造 CookRequest（id/type/remain/requested/targets，attributes 默认；
     * 不复用 CookRequest.copy()——其丢 extraData，all.java:7397-7409 实证），逐步调
     * {@code cookTick(level, maid, maid.blockPosition(), mem)}（CRAFTING 自锚点：骑坐/凳位
     * 由任务内部处理，pos 仅日志）至 remain≤0；单次未核销（缺料/matches 不符）即停等下轮。
     * remain 语义=份数（F-248：CraftingTableCookTask 0.2.5-R5 起每成功合成核销 1 份），
     * mem.remain=step.crafts()（合成份数）据此自然正确。
     *
     * @param orderId 链签名（F-267）：自驱面传订单 id，API 门面传 null（退化=步 recipeId 序列
     *                拼接，同形状不同链签名不同不互吞）；仅用于 drive-done 窗口键
     * @return 全部步完成 true；任一步未完成 false
     */
    static boolean driveSteps(ServerLevel level, EntityMaid maid, ChainResolver.Plan plan,
                              @Nullable ResourceLocation orderId) {
        var task = CookTasks.getTask(RecipeType.CRAFTING);
        if (task == null) {
            // F-236：每 tick 路径 ERROR 走 30s 窗（注册链路异常时防 20Hz 刷屏）
            errorWindow("crafting-task-missing",
                    "链式合成：CRAFTING 任务处理器缺失，无法驱动子合成");
            return false;
        }
        for (ChainResolver.Step step : plan.steps()) {
            CookRequest mem = new CookRequest();
            mem.id = step.recipeId();
            mem.type = RecipeType.CRAFTING;
            mem.remain = step.crafts();
            mem.requested = step.crafts();
            mem.targets = new long[0];
            int guard = 0;
            while (mem.remain > 0 && guard++ < DRIVE_LOOP_CAP) {
                int before = mem.remain;
                task.cookTick(level, maid, maid.blockPosition(), mem);
                if (mem.remain >= before) {
                    break; // 本轮未核销（缺料/matches 不符）——停等下轮重解
                }
            }
            if (mem.remain > 0) {
                // F-273：键补链签名（自驱面=订单 id/API 面=recipeId 序列）——不同订单同 recipeId 子步不互吞
                instance().warnWindow("drive-step:" + maid.getUUID() + ":" + chainSig(plan, orderId) + ":" + step.recipeId(),
                        "链式合成：女仆 {} 子步 {} 合成 {} 份中断（余 {}，缺料或异常），下轮重解",
                        maid.getName().getString(), step.recipeId(), step.crafts(), mem.remain);
                return false;
            }
        }
        // F-258/F-267：完成 INFO 走 30s 窗；键=女仆+链签名（自驱面=订单 id；API 面 orderId=null
        // 退化为步 recipeId 序列拼接——「步数×份数」签名会让同形状不同链互吞，已废）
        if (!plan.steps().isEmpty()) {
            String sig = chainSig(plan, orderId);
            if (instance().shouldLog("drive-done:" + maid.getUUID() + ":" + sig)) {
                MaidRestaurantCooking.LOGGER.info("[kitchen-ready] 链式合成：女仆 {} 子合成链驱动完成（{} 步）",
                        maid.getName().getString(), plan.steps().size());
            }
        }
        return true;
    }

    /** 链签名（F-267/F-273）：自驱面=订单 id；API 面（orderId=null）=步 recipeId 序列拼接——
     * 窗口键身份用，同女仆跨单/跨计划不互吞（F-258 口径）。
     * F-284（R9 发现 4）：纯补货计划（steps 空、leaves 非空）的步序列退化为空串，会让
     * restock-leftover/api-restock-short 键塌成「uuid:」（跨计划互吞）——空步时改用叶子键
     * 集合排序拼接，签名恒非空。 */
    static String chainSig(ChainResolver.Plan plan, @Nullable ResourceLocation orderId) {
        if (orderId != null) {
            return orderId.toString();
        }
        StringBuilder sig = new StringBuilder();
        for (ChainResolver.Step step : plan.steps()) {
            if (sig.length() > 0) {
                sig.append('>');
            }
            sig.append(step.recipeId());
        }
        if (sig.length() == 0) {
            return plan.leaves().stream().map(ChainResolver.Missing::key).sorted()
                    .collect(java.util.stream.Collectors.joining(","));
        }
        return sig.toString();
    }

    // ------------------------------------------------------------------ 冷却

    private static boolean isCooling(UUID maid, String missKey, long tick) {
        Long until = COOLDOWN.get(maid + ":" + missKey);
        return until != null && tick < until;
    }

    private static void cooldownAll(UUID maid, List<ChainResolver.Missing> misses, long tick) {
        for (ChainResolver.Missing m : misses) {
            COOLDOWN.put(maid + ":" + m.key(), tick + MISSING_COOLDOWN_TICKS);
        }
    }

    /** 过期清理 + 容量硬顶（超顶先清过期，仍超顶清全表——冷却只是节流，清表不致错）。 */
    private static void purgeCooldown(long tick) {
        if (COOLDOWN.size() <= COOLDOWN_MAX / 2) {
            COOLDOWN.values().removeIf(until -> until <= tick);
            return;
        }
        COOLDOWN.values().removeIf(until -> until <= tick);
        if (COOLDOWN.size() > COOLDOWN_MAX) {
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 链式合成：冷却表超容（{}），已整表重置", COOLDOWN.size());
            COOLDOWN.clear();
        }
    }

    // ------------------------------------------------------------------ 日志窗

    /** 30s 窗内同键只响一次（仿 CraftingTableCookTask.shouldLog；服务为静态单例，窗随类存活）。 */
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

    static void warnWindow(String code, String format, Object... args) {
        if (instance().shouldLog(code)) {
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] " + format, args);
        }
    }

    /** F-276：INFO 级窗口化（同 warnWindow）——cooldown-skip/leaf-cooling-skip 等「预期节流」观测信号
     * 用 INFO（原走 WARN 使注释/sweep 的「窗口 INFO」口径失实，操作员按 [INFO] 过滤会误判失败）。 */
    static void infoWindow(String code, String format, Object... args) {
        if (instance().shouldLog(code)) {
            MaidRestaurantCooking.LOGGER.info("[kitchen-ready] " + format, args);
        }
    }

    /** F-236：ERROR 级窗口化（同 warnWindow，供 ChainResolver/本类每扫描路径共用）。 */
    static void errorWindow(String code, String format, Object... args) {
        if (instance().shouldLog(code)) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] " + format, args);
        }
    }

    private static ChainCraftService instance() {
        return Holder.INSTANCE;
    }

    /** 惰性单例（仅承载日志窗状态；事件入口保持 static 供 @EventBusSubscriber 注册）。 */
    private static final class Holder {
        private static final ChainCraftService INSTANCE = new ChainCraftService();
    }
}
