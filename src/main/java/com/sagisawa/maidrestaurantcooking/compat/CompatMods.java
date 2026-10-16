package com.sagisawa.maidrestaurantcooking.compat;

import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ModList 软依赖门控常量集（照本体 ModCompats 写法：构造期求值布尔常量，任务注册按此分流）。
 *
 * <p>modId 全部实测自各目标 jar 的 META-INF/neoforge.mods.toml（2026-10-17），
 * 与 jar 文件名可能不同（如 jar 叫 kaleidoscopecookery，注册 id 是 kaleidoscope_cookery；
 * 乡野的注册 id 是 rtdd）——勿凭文件名猜，新增目标 mod 时先解包验证。
 */
public final class CompatMods {
    /** 必需前置：本体。缺了 FML 在依赖检查阶段就会拦截，此常量只用于日志与断言。 */
    public static final boolean MAID_RESTAURANT = ModList.get().isLoaded("maid_restaurant");
    /** 必需链：森罗厨房（本体 0.2.9 硬依赖，运行环境必有）。 */
    public static final boolean KALEIDOSCOPE_COOKERY = ModList.get().isLoaded("kaleidoscope_cookery");

    public static final boolean FARMERSDELIGHT = ModList.get().isLoaded("farmersdelight");
    public static final boolean FARM_AND_CHARM = ModList.get().isLoaded("farm_and_charm");
    public static final boolean BREWINANDCHEWIN = ModList.get().isLoaded("brewinandchewin");
    public static final boolean VINERY = ModList.get().isLoaded("vinery");
    public static final boolean BREWERY = ModList.get().isLoaded("brewery");
    public static final boolean HERBALBREWS = ModList.get().isLoaded("herbalbrews");
    public static final boolean BEACHPARTY = ModList.get().isLoaded("beachparty");
    public static final boolean BAKERIES = ModList.get().isLoaded("bakeries");
    public static final boolean BAKERY = ModList.get().isLoaded("bakery");
    public static final boolean REFURBISHED_FURNITURE = ModList.get().isLoaded("refurbished_furniture");
    public static final boolean FLAVOR_IMMERSED_DAILY = ModList.get().isLoaded("flavor_immersed_daily");
    public static final boolean BARBEQUESDELIGHT = ModList.get().isLoaded("barbequesdelight");
    public static final boolean CUISINEDELIGHT = ModList.get().isLoaded("cuisinedelight");
    public static final boolean MYNETHERSDELIGHT = ModList.get().isLoaded("mynethersdelight");
    public static final boolean TWILIGHTDELIGHT = ModList.get().isLoaded("twilightdelight");
    public static final boolean FRUITSDELIGHT = ModList.get().isLoaded("fruitsdelight");
    public static final boolean ENDS_DELIGHT = ModList.get().isLoaded("ends_delight");
    public static final boolean TIDESDELIGHT = ModList.get().isLoaded("tidesdelight");
    public static final boolean LENDERSDELIGHT = ModList.get().isLoaded("lendersdelight");
    public static final boolean CULTURALDELIGHTS = ModList.get().isLoaded("culturaldelights");
    public static final boolean WILDERNATURE = ModList.get().isLoaded("wildernature");
    public static final boolean DUNGEONSDELIGHT = ModList.get().isLoaded("dungeonsdelight");
    /** 乡野（jar 名 乡野neoforge…，注册 id rtdd）。 */
    public static final boolean RTDD = ModList.get().isLoaded("rtdd");
    public static final boolean TIDE = ModList.get().isLoaded("tide");

    private CompatMods() {
    }

    /** 构造期调用：落日志，报告每个门控的开/关。装了哪些目标 mod 一眼可查。 */
    public static void logGates() {
        Logger log = com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking.LOGGER;
        if (!MAID_RESTAURANT || !KALEIDOSCOPE_COOKERY) {
            // required 依赖缺失时 FML 会先拦截；走到这里仍为 false 属异常状态，响出来
            log.error("[xia-de-chu-fang] 必需前置异常：maid_restaurant={}, kaleidoscope_cookery={}",
                    MAID_RESTAURANT, KALEIDOSCOPE_COOKERY);
            return;
        }
        Map<String, Boolean> optional = new LinkedHashMap<>();
        optional.put("farmersdelight", FARMERSDELIGHT);
        optional.put("farm_and_charm", FARM_AND_CHARM);
        optional.put("brewinandchewin", BREWINANDCHEWIN);
        optional.put("vinery", VINERY);
        optional.put("brewery", BREWERY);
        optional.put("herbalbrews", HERBALBREWS);
        optional.put("beachparty", BEACHPARTY);
        optional.put("bakeries", BAKERIES);
        optional.put("bakery", BAKERY);
        optional.put("refurbished_furniture", REFURBISHED_FURNITURE);
        optional.put("flavor_immersed_daily", FLAVOR_IMMERSED_DAILY);
        optional.put("barbequesdelight", BARBEQUESDELIGHT);
        optional.put("cuisinedelight", CUISINEDELIGHT);
        optional.put("mynethersdelight", MYNETHERSDELIGHT);
        optional.put("twilightdelight", TWILIGHTDELIGHT);
        optional.put("fruitsdelight", FRUITSDELIGHT);
        optional.put("ends_delight", ENDS_DELIGHT);
        optional.put("tidesdelight", TIDESDELIGHT);
        optional.put("lendersdelight", LENDERSDELIGHT);
        optional.put("culturaldelights", CULTURALDELIGHTS);
        optional.put("wildernature", WILDERNATURE);
        optional.put("dungeonsdelight", DUNGEONSDELIGHT);
        optional.put("rtdd(乡野)", RTDD);
        optional.put("tide", TIDE);
        long on = optional.values().stream().filter(Boolean::booleanValue).count();
        StringBuilder sb = new StringBuilder();
        optional.forEach((k, v) -> sb.append(v ? '+' : '-').append(k).append(' '));
        log.info("[xia-de-chu-fang] 软依赖门控：必需链 OK（maid_restaurant + kaleidoscope_cookery），可选目标 {} 个在场：{}",
                on, sb);
    }
}
