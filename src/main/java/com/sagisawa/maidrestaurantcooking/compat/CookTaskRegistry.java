package com.sagisawa.maidrestaurantcooking.compat;

import com.mastermarisa.maid_restaurant.api.ICookTask;
import com.mastermarisa.maid_restaurant.utils.CookTasks;

/**
 * 批量注册器雏形（M0）：对本体注册口的唯一入口。
 *
 * <p>纪律（反编译实证）：
 * <ul>
 *   <li>CookTasks.register(task) 只把任务入本体暂存队列，本体 FMLCommonSetupEvent 才冲刷进 taskMap；
 *       因此调用必须发生在本 mod 构造期（早于本体冲刷）。</li>
 *   <li>冲刷前调 CookTasks.getTask(...) 拿不到东西（getRegistered() 返回的是队列快照非注册表）。</li>
 *   <li>同 RecipeType 键后到覆盖（taskMap.put）：绝不注册本体内置 8 键与森罗酒馆 3 键。</li>
 *   <li>注册失败的判定要在 M1 批量注册器里做：commonSetup 后自查 taskMap 是否含本包 UID，缺了 ERROR 级日志（错误必须响）。</li>
 * </ul>
 *
 * <p>M0 无调用方；M1 起各任务在此登记门控条件后入队。
 */
public final class CookTaskRegistry {
    private CookTaskRegistry() {
    }

    /** 构造期直通：把任务交给本体暂存队列。仅限 mod 构造期调用。 */
    public static void registerTask(ICookTask task) {
        CookTasks.register(task);
    }
}
