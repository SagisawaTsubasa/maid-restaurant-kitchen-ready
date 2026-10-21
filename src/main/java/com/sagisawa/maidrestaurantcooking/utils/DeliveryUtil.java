package com.sagisawa.maidrestaurantcooking.utils;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * 统一交付收口（0.2.4「交付语义入包」批）：产物/回包/容器返还一律直接入女仆背包，
 * 对齐本体先例 {@code ItemHandlerHelper.insertItemStacked(maid.getAvailableInv(false),
 * result.copy(), false)}（maid_restaurant-0.2.9 反编译 all.java:3066）。
 *
 * <p>取代森罗 {@code ItemUtils} 对 LivingEntity 的交付工具的「主手空→进主手 / Player→give /
 * 其它→掉地」语义（森罗 jar javap 实证）：后者让成品滞主手、回包与容器返还直接掉地
 * （真机 2026-10-20 判别单两 bug 根因）。全仓交付点已全部改走本收口，
 * grep 旧交付方法名残留=0 为硬验收。
 */
public final class DeliveryUtil {
    private DeliveryUtil() {
    }

    /**
     * 交付一份物品进女仆背包（{@code getAvailableInv(false)}=背包优先、手槽排后，含手槽）。
     *
     * <p>内部对传入栈做防御性 copy：调用方保有所有权，交付后仍可读其数量
     * （如 CraftingTableCookTask F-175 产物对账交付后读 {@code result.getCount()}），
     * 也防第三方 IItemHandler 实现原地改写传入栈。满包溢出=保留原掉地语义
     * （0 拾取延迟，对齐森罗 javap 实证）并 WARN 响出（错误必须响，不静默吞）。
     */
    public static void deliverToMaidInv(EntityMaid maid, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack leftover = ItemHandlerHelper.insertItemStacked(maid.getAvailableInv(false), stack.copy(), false);
        if (!leftover.isEmpty()) {
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 女仆背包已满，交付溢出掉地：{} x{} @ {}",
                    leftover.getItem(), leftover.getCount(), maid.blockPosition());
            ItemEntity dropped = maid.spawnAtLocation(leftover);
            if (dropped != null) {
                dropped.setPickUpDelay(0);
            }
        }
    }
}
