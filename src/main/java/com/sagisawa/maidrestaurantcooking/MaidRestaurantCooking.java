package com.sagisawa.maidrestaurantcooking;

import com.mojang.logging.LogUtils;
import com.sagisawa.maidrestaurantcooking.compat.CompatMods;
import com.sagisawa.maidrestaurantcooking.compat.CookTaskRegistry;
import com.sagisawa.maidrestaurantcooking.config.Config;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

/**
 * 女仆餐厅：下得厨房 (Maid Restaurant: Kitchen-Ready)
 *
 * <p>女仆餐厅 (maid_restaurant) 0.2.9 点单体系补全包。注册纪律（反编译实证，wiki 接口页）：
 * CookTasks.register(task) 只入队，本体 FMLCommonSetupEvent 才冲刷入 taskMap——
 * 所以任务注册必须发生在本 mod 构造期（早于本体冲刷），且不能抢在冲刷前调 getTask。
 */
@Mod(MaidRestaurantCooking.MOD_ID)
public class MaidRestaurantCooking {
    public static final String MOD_ID = "maid_restaurant_cooking";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MaidRestaurantCooking(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // 门控常量集在本体 commonSetup 冲刷前必须定案：构造期求值，各任务注册按此分流
        CompatMods.logGates();

        // 批量注册（M1）：构造期把任务入本体暂存队列，本体 FMLCommonSetup 冲刷入 taskMap。
        // 顺序约束：必须在本体冲刷前（构造期即安全）；此处不可调 task.getType()（注册表未填充）。
        CookTaskRegistry.registerAll();

        modEventBus.addListener(this::commonSetup);
        // F-006：配置热重载时使炉式路由缓存失效（routingRules 改动即时生效）
        modEventBus.addListener(this::onConfigReload);
        LOGGER.info("[xia-de-chu-fang] 女仆餐厅：下得厨房已加载（v{}）", modContainer.getModInfo().getVersion());
    }

    private void onConfigReload(final net.neoforged.fml.event.config.ModConfigEvent.Reloading event) {
        if (event.getConfig().getModId().equals(MOD_ID)) {
            com.sagisawa.maidrestaurantcooking.config.FurnaceRouting.invalidate();
            LOGGER.info("[xia-de-chu-fang] 配置重载，炉式路由缓存已失效重建");
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // F-009：本体冲刷后自检——本包入队的每个任务都必须能从 taskMap 反查到（后到覆盖/冲刷失败即 ERROR 响）
        event.enqueueWork(() -> {
            int mine = 0;
            for (var task : com.sagisawa.maidrestaurantcooking.compat.CookTaskRegistry.registered()) {
                try {
                    mine++;
                    var found = com.mastermarisa.maid_restaurant.utils.CookTasks.getTask(task.getType());
                    if (found == null) {
                        LOGGER.error("[xia-de-chu-fang] 注册自检失败：任务 {}（type={}）未进入本体 taskMap——同键后到覆盖或冲刷异常！",
                                task.getUID(), task.getType());
                    } else if (found != task) {
                        // F-106：身份比对——同键被其他实例覆盖（他包/本体同 UID 任务）时亮出来
                        LOGGER.warn("[xia-de-chu-fang] 注册自检：type {} 上实际生效 {}（本包 {} 被覆盖或为同 UID 任务）",
                                task.getType(), found.getUID(), task.getUID());
                    }
                } catch (Exception e) {
                    LOGGER.error("[xia-de-chu-fang] 注册自检异常（任务 {}）：{}", task.getUID(), e.toString());
                }
            }
            LOGGER.info("[xia-de-chu-fang] 注册自检：本包任务 {} 个已冲刷核对", mine);
        });
    }
}
