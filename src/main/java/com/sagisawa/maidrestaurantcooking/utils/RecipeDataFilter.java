package com.sagisawa.maidrestaurantcooking.utils;

import com.mastermarisa.maid_restaurant.utils.component.RecipeData;
import com.sagisawa.maidrestaurantcooking.config.Config;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 点单菜单排除过滤器（M1 公共件）：getAllRecipeData 的统一出口。
 *
 * <p>规则（common.toml）：
 * <ul>
 *   <li>exclusion.excludedRecipePrefixes：id 字符串命中前缀即滤（默认滤森罗酒馆酿酒键——
 *       防"同 RecipeType 后到覆盖"顶掉 maidtavern/fox_trot_brew 的接手线——与炼金坩埚药剂键）。</li>
 *   <li>categoryToggles.disabledCategories：条目为 "命名空间" 或 "命名空间:配方类型键"，
 *       命中即滤整组（防订单菜单过载）。</li>
 * </ul>
 *
 * <p>纪律：过滤只作用于「本包自己任务的 getAllRecipeData 出口」；本体已内置 8 类的菜单
 * 由本体维护，不在此管辖。
 */
public final class RecipeDataFilter {
    private RecipeDataFilter() {
    }

    /** 出口统一过滤；命中排除的条目计数后落 DEBUG 日志（可追溯，不静默）。 */
    public static List<RecipeData> apply(List<RecipeData> data) {
        List<RecipeData> ans = new ArrayList<>(data.size());
        int excluded = 0;
        for (RecipeData d : data) {
            if (isExcluded(d.ID) || isCategoryDisabled(d.ID, d.type)) {
                excluded++;
                continue;
            }
            ans.add(d);
        }
        if (excluded > 0) {
            MaidRestaurantCooking.LOGGER.debug("[kitchen-ready] 点单菜单过滤：{} 条命中排除/关停（总 {}）", excluded, data.size());
        }
        return ans;
    }

    /** id 是否命中排除前缀（"命名空间:" 或 "命名空间:路径前缀"）。 */
    public static boolean isExcluded(ResourceLocation id) {
        return Config.isExcluded(id.toString());
    }

    /** 分类开关判定：disabledCategories 含 "命名空间" 或 "命名空间:类型键" 即命中。 */
    public static boolean isCategoryDisabled(ResourceLocation recipeId, RecipeType<?> type) {
        for (String entry : Config.DISABLED_CATEGORIES.get()) {
            String e = entry.trim().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) {
                continue;
            }
            if (e.equals(recipeId.getNamespace()) || e.equals(recipeId.toString())) {
                return true;
            }
            if (type != null) {
                ResourceLocation typeKey = net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.getKey(type);
                if (typeKey != null && (e.equals(typeKey.getNamespace()) || e.equals(typeKey.toString()))) {
                    return true;
                }
            }
        }
        return false;
    }
}
