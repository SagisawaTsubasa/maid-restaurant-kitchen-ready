package com.sagisawa.maidrestaurantcooking.chain;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 链式合成公开 API 门面（0.2.5 定稿签名，fork 集成批按此对账）。
 *
 * <p>双面形态之 API 面：营业 fork（maid_restaurant_business）在场时本包自驱服务不启动
 * （AUTO 门，{@code ChainCraftService} 内运行时判定），fork 侧按自身取料节奏调用本门面
 * （软依赖，无环）。无 fork 环境由 {@link ChainCraftService} 自驱，走同一套求解/补货/驱动逻辑。
 *
 * <p>fork 集成契约（F-259）：{@code stockView} 不是全背包——必须传入原订单保留量
 * （required × remain）出净后的<b>盈余面</b>，否则求解器会规划消费 fork 订单已备原料，
 * {@link #drive} 执行时反向蚕食（F-237 在 API 面复现）。盈余面构造示例：先
 * {@code ItemHandlerUtils.filterByCount(required, stacks, remain)} 得净缺额，再对库存副本
 * 逐谓词扣减 {@code required × remain}（自驱服务 {@code ChainCraftService.surplusAfterReserve}
 * 为同构参照实现）。fork 只需对「准备驱动子合成的订单」做保留扣减；无保留诉求时可传全背包。
 *
 * <p>链计划语义见 {@link ChainResolver}：{@code steps()} 为依赖序子合成步（CRAFTING，
 * 深度≤3、visited 防环、每链总份数预算 16）；{@code leaves()} 为需世界补货的叶子缺项。
 *
 * <p>异常语义（F-244/F-257/F-266/F-271）：{@code resolveSafe/probe/drive/driveStepsOnly} 全入口
 * 异常收口或 null 防护——{@code drive/driveStepsOnly} 对 null 计划/null maid/null level
 * 返回 false（null 计划为 {@link #resolve} 对非法入参的预期返回，静默不打日志，F-266），
 * 内部异常一律 errorWindow 30s 窗收口后返回 false（F-271：键带 maid/item，可见不吞不抛，
 * fork 逐 tick 调用持久异常也不刷屏），fork 侧可直接在 tick 中调用而不设防。
 */
public final class ChainCraftApi {

    private ChainCraftApi() {
    }

    /**
     * 求解：产物 itemId × qty 的子合成链。
     *
     * @param stockView 女仆库存快照——必须是原订单保留量（required × remain）出净后的盈余面，
     *                  非全背包（F-259，直传全背包会令求解器规划消费订单已备原料、drive 时反向
     *                  蚕食，F-237 API 面复现）。构造口径：先
     *                  {@code ItemHandlerUtils.filterByCount(required, stacks, remain)} 得净缺额，
     *                  再对库存副本逐谓词扣减 {@code required × remain}（参照
     *                  {@code ChainCraftService.surplusAfterReserve} 同构实现）；求解做多重集合
     *                  扣减模拟，只读不改原列表
     * @return 链计划（steps 可为空=纯补货单）；物品不存在/qty≤0/misses 为空返回 null
     *         （预算耗尽不返回 null，走叶子降级——F-240 口径）
     */
    @Nullable
    public static ChainResolver.Plan resolve(ServerLevel level, ResourceLocation itemId, int qty,
                                             List<ItemStack> stockView) {
        if (itemId == null || qty <= 0) {
            return null;
        }
        var item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        if (item == null) {
            return null;
        }
        ChainResolver.Missing miss = new ChainResolver.Missing(StackPredicate.of(item), itemId.toString(), qty);
        return ChainResolver.resolve(level, List.of(miss), stockView);
    }

    /**
     * 可行性探测：解存在（含纯补货单）即 true——叶子是否有世界补货源由调用方结合 {@link #drive}
     * 结果判定。F-257：走 {@link #resolveSafe} 收口（stockView null 触发的 NPE、Ingredient.test
     * 异常等不波及调用 tick），探测失败返回 false。
     */
    public static boolean probe(ServerLevel level, ResourceLocation itemId, int qty, List<ItemStack> stockView) {
        return resolveSafe(level, itemId, qty, stockView) != null;
    }

    /**
     * 执行链：补货叶子（盒扫 storage 就近按槽安全抽取进女仆背包）+ 驱动全部子合成步（与自驱服务
     * 同款语义）；<b>补货不足即停返回 false</b>（F-260/F-249 同款语义：子合成齐备预检无「原订单
     * 保留量」概念，计划叶子没补到时早序子步会吃掉原订单保留在包内的同种原料）；缺料步停等，
     * 返回 false 由调用方择机重试——不建议每 tick 重调，本门面不做节流。
     *
     * @param level 女仆所在服务端维度；null 直接返回 false（F-257 入口防护）
     * @param maid  执行女仆（补货抽取/子合成 cookTick 的背包面）；null 直接返回 false
     *              （F-257：防 catch 内解引用二次 NPE 逃逸，违背 F-244「可见不吞不抛」）
     * @param plan  可为 null（{@link #resolve} 对非法入参返回 null）——null 直接返回 false
     *              （F-244/F-266：预期路径静默，不打 ERROR）
     * @return 全链步完成 true；plan/maid/level 为 null、补货不足或执行异常均返回 false
     *         （异常经 errorWindow 30s 窗收口可见，F-244/F-271）
     */
    public static boolean drive(ServerLevel level, EntityMaid maid, ChainResolver.Plan plan) {
        if (maid == null || level == null) {
            return false; // F-257：入口 null 防护——catch 内 maid.getName() 会对 null 二次 NPE 逃逸
        }
        if (plan == null) {
            return false; // F-266：null 计划=resolve 对非法入参的预期返回，静默返 false 不打 ERROR
        }
        try {
            // F-260：补货不足即停（与自驱面 F-249 同款语义），不无条件 driveSteps——防蚕食原订单保留原料。
            // F-273：restock-leftover 键身份=链签名（API 面无订单 id，退化=步 recipeId 序列）
            ChainCraftService.RestockResult restock = ChainCraftService.restock(level, maid,
                    plan.leaves(), ChainCraftService.chainSig(plan, null));
            if (!restock.still().isEmpty()) {
                // 调用方节奏自管（本门面不节流），补货不足只响一次窗控日志（F-263：原因如实区分；
                // F-273：键补链签名——fork 一女仆多计划时不再互吞）
                ChainCraftService.warnWindow("api-restock-short:" + maid.getUUID() + ":"
                        + ChainCraftService.chainSig(plan, null),
                        "链式合成（API）：女仆 {} 补货不足 {} 组（{}），链步停等",
                        maid.getName().getString(), restock.still().size(), restock.reason());
                return false;
            }
            return ChainCraftService.driveSteps(level, maid, plan, null);
        } catch (Exception e) {
            // F-244/F-257：fork tick 内不设防调用——异常收口可见不吞不抛（maid 已入口防护，F-266 删冗余占位）。
            // F-271：改 errorWindow 30s 窗（F-236 每 tick 路径纪律——裸 ERROR 在持久异常下 20Hz 刷盘）。
            // F-283：键补链签名（chainSig(plan,null)，空步计划退化叶键集——F-284）——同女仆跨链/跨计划异常不再互吞
            ChainCraftService.errorWindow("api-drive-error:" + maid.getUUID() + ":"
                            + ChainCraftService.chainSig(plan, null),
                    "链式合成（API）：女仆 {} 链驱动异常：{}", maid.getName().getString(), e.toString());
            return false;
        }
    }

    /**
     * 仅驱动子合成步（fork 自管补货时用；缺料步停等返回 false）。
     *
     * @param level 女仆所在服务端维度；null 直接返回 false（F-257 入口防护）
     * @param maid  执行女仆；null 直接返回 false（F-257，同 {@link #drive}）
     * @param plan  可为 null——null 直接返回 false（F-244/F-266：预期路径静默不打日志）；
     *              执行异常收口同 {@link #drive}
     */
    public static boolean driveStepsOnly(ServerLevel level, EntityMaid maid, ChainResolver.Plan plan) {
        if (maid == null || level == null) {
            return false; // F-257：入口 null 防护（同 drive）
        }
        if (plan == null) {
            return false; // F-266：null 计划=预期路径，静默返 false 不打 ERROR
        }
        try {
            return ChainCraftService.driveSteps(level, maid, plan, null);
        } catch (Exception e) {
            // F-271：同 drive，errorWindow 30s 窗；F-283：键补链签名 + ":steps" 后缀——与 drive 的
            // 链驱动异常键（不同消息码）不共窗，同女仆 30s 内两路异常各自可见
            ChainCraftService.errorWindow("api-drive-error:" + maid.getUUID() + ":"
                            + ChainCraftService.chainSig(plan, null) + ":steps",
                    "链式合成（API）：女仆 {} 链步驱动异常：{}", maid.getName().getString(), e.toString());
            return false;
        }
    }

    /** 求解异常收口包装（fork 侧防波及自身 tick 的保险入口；异常可见不吞）。 */
    @Nullable
    public static ChainResolver.Plan resolveSafe(ServerLevel level, ResourceLocation itemId, int qty,
                                                 List<ItemStack> stockView) {
        try {
            return resolve(level, itemId, qty, stockView);
        } catch (Exception e) {
            // F-271：errorWindow 30s 窗（键带 item——同物品反复求解失败不刷屏）
            ChainCraftService.errorWindow("api-resolve-error:" + itemId,
                    "链式合成（API）：求解 {}×{} 异常：{}", itemId, qty, e.toString());
            return null;
        }
    }
}
