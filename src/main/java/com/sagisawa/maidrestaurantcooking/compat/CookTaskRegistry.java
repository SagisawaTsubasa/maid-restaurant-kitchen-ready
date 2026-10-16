package com.sagisawa.maidrestaurantcooking.compat;

import com.mastermarisa.maid_restaurant.api.ICookTask;
import com.mastermarisa.maid_restaurant.utils.CookTasks;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import com.sagisawa.maidrestaurantcooking.cooktask.bakeries.FermentationBoxCookTask;
import com.sagisawa.maidrestaurantcooking.cooktask.chinesefood.ChinesefoodFreezerCookTask;
import com.sagisawa.maidrestaurantcooking.cooktask.chinesefood.PickleJarCookTask;
import com.sagisawa.maidrestaurantcooking.cooktask.farmcharm.CraftingBowlCookTask;
import com.sagisawa.maidrestaurantcooking.cooktask.farmcharm.FarmcharmHeatedCookTask;
import com.sagisawa.maidrestaurantcooking.cooktask.farmcharm.MincerCookTask;
import com.sagisawa.maidrestaurantcooking.cooktask.farmcharm.SiloCookTask;
import com.sagisawa.maidrestaurantcooking.cooktask.fd.FDCuttingBoardCookTask;
import com.sagisawa.maidrestaurantcooking.cooktask.farmcharm.StoveCookTask;
import com.sagisawa.maidrestaurantcooking.cooktask.fid.FidFridgeCookTask;
import com.sagisawa.maidrestaurantcooking.cooktask.flex.FlexPotCookTask;
import com.sagisawa.maidrestaurantcooking.cooktask.furnace.FurnaceRoutingCookTasks;
import com.sagisawa.maidrestaurantcooking.cooktask.flex.FlexStockpotCookTask;
import com.sagisawa.maidrestaurantcooking.cooktask.herbalbrews.TeaKettleCookTask;
import com.sagisawa.maidrestaurantcooking.cooktask.kaleidoscope.BambooTrayCookTask;
import com.sagisawa.maidrestaurantcooking.cooktask.kaleidoscope.ChoppingBoardCookTask;
import com.sagisawa.maidrestaurantcooking.cooktask.kaleidoscope.TeapotCookTask;
import com.sagisawa.maidrestaurantcooking.cooktask.worldliquor.WorldliquorFreezerCookTask;

/**
 * 批量注册器（M1 公共件）：对本体注册口的唯一入口。
 *
 * <p>纪律（反编译实证，wiki 接口页）：
 * <ul>
 *   <li>CookTasks.register(task) 只把任务入本体暂存队列，本体 FMLCommonSetupEvent 才冲刷进 taskMap；
 *       因此 registerAll() 必须在 mod 构造期调用（早于本体冲刷）。</li>
 *   <li>冲刷前调 CookTasks.getTask(...) 拿不到东西（getRegistered() 返回队列快照非注册表）。</li>
 *   <li>同 RecipeType 键后到覆盖（taskMap.put）：绝不注册本体内置 8 键与森罗酒馆 3 键。</li>
 *   <li>每个任务的注册按 CompatMods 门控分流——目标 mod 不在场就不注册，靠 mods.toml optional 声明兜底。</li>
 *   <li>注册失败的判定（M6 冒烟统一做）：commonSetup 后自查 taskMap 是否含本包 UID，缺了 ERROR 级日志。</li>
 * </ul>
 */
public final class CookTaskRegistry {
    private CookTaskRegistry() {
    }

    /**
     * mod 构造期调用：按门控把全部任务入本体暂存队列。
     * M1：森罗 flex 两类（必需链常在）；M2+ 各家族追加。
     */
    public static void registerAll() {
        // 森罗 flex（T1 打样）：本体硬依赖链，KALEIDOSCOPE_COOKERY 恒真，门控保留为纪律示范
        if (CompatMods.KALEIDOSCOPE_COOKERY) {
            registerTask(new FlexPotCookTask());
            registerTask(new FlexStockpotCookTask());
            // M2 森罗余量：竹匾（自环境计时）、茶壶（注液+补放闭环）
            registerTask(new BambooTrayCookTask());
            registerTask(new TeapotCookTask());
            registerTask(new ChoppingBoardCookTask());
        }

        // M2 定时容器族（ModList 门控，optional AFTER）
        if (CompatMods.KALEIDOSCOPE_CHINESEFOOD) {
            registerTask(new PickleJarCookTask());
            registerTask(new ChinesefoodFreezerCookTask.Freezing());
            registerTask(new ChinesefoodFreezerCookTask.Refrigerating());
        }
        if (CompatMods.FLAVOR_IMMERSED_DAILY) {
            registerTask(new FidFridgeCookTask.Freezing());
            registerTask(new FidFridgeCookTask.Tempering());
        }
        if (CompatMods.FARM_AND_CHARM) {
            registerTask(new SiloCookTask());
            registerTask(new MincerCookTask());
            registerTask(new StoveCookTask());
            registerTask(new FarmcharmHeatedCookTask.CookingPot());
            registerTask(new FarmcharmHeatedCookTask.Roaster());
            registerTask(new CraftingBowlCookTask());
        }
        if (CompatMods.BAKERIES) {
            registerTask(new FermentationBoxCookTask());
        }
        if (CompatMods.KALEIDOSCOPE_WORLD_LIQUOR) {
            registerTask(new WorldliquorFreezerCookTask());
        }
        if (CompatMods.HERBALBREWS) {
            registerTask(new TeaKettleCookTask());
        }
        if (CompatMods.FARMERSDELIGHT) {
            registerTask(new FDCuttingBoardCookTask());
        }

        // M3 炉式三实类（原版类型，无门控；路由表默认原版炉，config 可路由 modded 方块）
        registerTask(new FurnaceRoutingCookTasks.Smelting());
        registerTask(new FurnaceRoutingCookTasks.Smoking());
        registerTask(new FurnaceRoutingCookTasks.Blasting());

        MaidRestaurantCooking.LOGGER.info("[xia-de-chu-fang] CookTask 构造期入队完成（门控分流后）");
    }

    /** 构造期直通：把任务交给本体暂存队列。仅限 mod 构造期调用。
     *  注意：构造期 RecipeType 注册表尚未填充（RegisterEvent 在 CONSTRUCT 之后），
     *  此处禁止调 task.getType()（森罗 ModRecipes 字段此刻为 null）；UID 由任务自身常量提供。 */
    public static void registerTask(ICookTask task) {
        CookTasks.register(task);
        MaidRestaurantCooking.LOGGER.info("[xia-de-chu-fang] CookTask 入队：{}", task.getUID());
    }
}
