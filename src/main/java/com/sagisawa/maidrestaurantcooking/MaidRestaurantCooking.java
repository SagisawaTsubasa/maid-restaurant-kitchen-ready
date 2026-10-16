package com.sagisawa.maidrestaurantcooking;

import com.mojang.logging.LogUtils;
import com.sagisawa.maidrestaurantcooking.compat.CompatMods;
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

        // 门控常量集在本体 commonSetup 冲刷前必须定案：构造期求值，M1 起各任务注册按此分流
        CompatMods.logGates();

        modEventBus.addListener(this::commonSetup);
        LOGGER.info("[xia-de-chu-fang] 女仆餐厅：下得厨房已加载（v{}），CookTask 注册口就绪", modContainer.getModInfo().getVersion());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // M0 骨架期无任务可冲刷；M1 起批量注册器的入队动作都在各 mod 构造期完成，
        // 这里只保留生命周期占位（若后续需要 enqueueWork 自检，在此追加）。
    }
}
