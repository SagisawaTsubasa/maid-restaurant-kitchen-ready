package com.sagisawa.maidrestaurantcooking.utils;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * 统一交付收口（0.2.4「交付语义入包」批）：本包直接交付点（产物/回包/容器返还）
 * 直接入女仆背包，对齐本体先例 {@code ItemHandlerHelper.insertItemStacked(maid.getAvailableInv(false),
 * result.copy(), false)}（maid_restaurant-0.2.9 反编译 all.java:3066）。
 *
 * <p>取代森罗 {@code ItemUtils} 对 LivingEntity 的交付工具的「主手空→进主手 / Player→give /
 * 其它→掉地」语义（森罗 jar javap 实证）：后者让成品滞主手、回包与容器返还直接掉地
 * （真机 2026-10-20 判别单两 bug 根因）。
 *
 * <p>主插入用 {@link EntityMaid#getAvailableBackpackInv()}（TLM 1.5.3 javap 实证：纯背包视图，
 * 不含手槽；{@code getAvailableInv(boolean)} 双视图均含手槽、false=背包优先手槽排后——
 * ifeq 分支 [backpack,hands] vs [hands,backpack]，2026-10-21 首审 F-206）。含手槽视图下
 * 「满背包+空手」会让产物静默入手复现旧症状（F-208），故主插入不含手；取料/预检路径仍用
 * {@code getAvailableInv(false)}（含手槽，回收物可见可取）。
 *
 * <p>边界（详见 docs/边界清单.md「交付语义边界」节）：①森罗 BE 内部交付
 * （teapot/bamboo/pot/stockpot 家族 takeTeapot/onTakeOut/takeOutProduct 等走旧语义）不由本收口
 * 直达，由调用任务以 {@link #snapshotMainHand}→BE 调用→{@link #reclaimDeliveredMainHand}
 * 同 tick 主手快照收口（不 mixin 森罗）；②wok/choppingboard/mortar/FD/furniture 等落地类
 * 产物由 BE 生成 ItemEntity，依赖女仆拾取 AI 收集（设计如此，非本收口面）。
 * grep 旧交付方法名残留=0 仅为源码级验收，行为级边界见边界清单。
 */
public final class DeliveryUtil {
    private DeliveryUtil() {
    }

    /**
     * 交付一份物品进女仆背包（{@link EntityMaid#getAvailableBackpackInv()}，纯背包不含手槽）。
     *
     * <p>内部对传入栈做防御性 copy：调用方保有所有权，交付后仍可读其数量
     * （如 CraftingTableCookTask F-175 产物对账交付后读 {@code result.getCount()}），
     * 也防第三方 IItemHandler 实现原地改写传入栈。背包满溢出=掉地（0 拾取延迟，女仆
     * 拾取 AI 收集）+ WARN 响出（错误必须响，不静默吞）。
     */
    public static void deliverToMaidInv(EntityMaid maid, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack leftover = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), stack.copy(), false);
        if (!leftover.isEmpty()) {
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 女仆背包已满，交付溢出掉地：{} x{} @ {}",
                    leftover.getItem(), leftover.getCount(), maid.blockPosition());
            ItemEntity dropped = maid.spawnAtLocation(leftover);
            if (dropped != null) {
                dropped.setPickUpDelay(0);
            }
        }
    }

    /**
     * BE 交付调用前的主手引用快照：森罗 BE 内部旧交付「主手空→setItemInHand」必换槽内
     * 引用，同 tick 内引用比对即可精确判「本次新增」（中间无 AI tick 插入）。
     * 仅供紧随其后的 {@link #reclaimDeliveredMainHand} 使用，勿跨 tick、勿改动返回栈。
     */
    public static ItemStack snapshotMainHand(EntityMaid maid) {
        return maid.getMainHandItem();
    }

    /**
     * 森罗 BE 内部交付（走旧三分支交付工具）后的任务侧收口：主手引用与
     * 快照不同=BE 刚交付入手的新栈——移入背包（满包溢出同 {@link #deliverToMaidInv}）。
     * BE「手占→掉地」分支不受影响：掉地 0 拾取延迟，由女仆拾取 AI 收集（真机 sweep C2
     * 已验证）。仅动本次新增的栈，引用未变（含 BE 未交付/女仆既有持物）原样保留。
     */
    public static void reclaimDeliveredMainHand(EntityMaid maid, ItemStack handBefore) {
        ItemStack hand = maid.getMainHandItem();
        if (hand == handBefore || hand.isEmpty()) {
            return;
        }
        deliverToMaidInv(maid, hand);
        maid.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }
}
