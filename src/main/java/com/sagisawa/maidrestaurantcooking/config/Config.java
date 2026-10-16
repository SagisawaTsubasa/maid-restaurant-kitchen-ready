package com.sagisawa.maidrestaurantcooking.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * maid_restaurant_cooking-common.toml。
 *
 * <p>签名已 javap 实证（neoforge 21.1.251 universal jar）：
 * ModContainer.registerConfig(ModConfig.Type, IConfigSpec)；
 * ModConfigSpec implements IConfigSpec；
 * Builder.defineList(String, List, Predicate&lt;Object&gt;) / define(String, T) / push/pop/build。
 */
public final class Config {
    public static final ModConfigSpec SPEC;

    /**
     * 排除过滤器（getAllRecipeData 生效，驱动点单菜单）：
     * 命中前缀的配方键不进菜单。格式 "命名空间:" 整域排除，或 "命名空间:路径前缀" 精确滤。
     * 默认值来自实施计划 v5 §一：滤森罗酒馆酿酒键（ maidtavern/fox_trot_brew 已接手，
     * 同 RecipeType 后到覆盖会顶掉它们）与炼金坩埚（药剂非食品）。
     */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_RECIPE_PREFIXES;

    /**
     * 炉式路由映射（M3 启用）：每行 "源RecipeType键=目标工作方块id"，
     * 例如 "minecraft:smelting=kaleidoscope_cookery:rotisserie"（示例占位，M3 实证后改默认值）。
     * 炉式三类的女仆烹饪不走原熔炉，由映射表路由到在场烤/熏方块。
     */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> FURNACE_ROUTING_RULES;

    /**
     * 分类开关（点单菜单防过载，M1 接线）：列入的 "命名空间" 或 "命名空间:类型" 整组不接入点单。
     */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED_CATEGORIES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("exclusion")
                .comment("getAllRecipeData 排除过滤器：命中前缀的配方键不出现在点单菜单。默认滤森罗酒馆酿酒键（防同 RecipeType 后到覆盖顶掉 maidtavern/fox_trot_brew）与炼金坩埚（药剂非食品）。")
        ;
        EXCLUDED_RECIPE_PREFIXES = builder
                .defineList("excludedRecipePrefixes",
                        List.of("kaleidoscope_tavern:", "irons_spellbooks:alchemist_cauldron"),
                        o -> o instanceof String s && !s.isBlank());
        builder.pop();

        builder.push("furnaceRouting")
                .comment("炉式路由映射（M3 启用）：每行 \"源RecipeType键=目标工作方块id\"。留空 = 未启用。");
        FURNACE_ROUTING_RULES = builder
                .defineList("routingRules", List.of(),
                        o -> o instanceof String s && !s.isBlank());
        builder.pop();

        builder.push("categoryToggles")
                .comment("分类开关：列入的 \"命名空间\" 或 \"命名空间:配方类型\" 整组不接入点单（点单菜单防过载）。");
        DISABLED_CATEGORIES = builder
                .defineList("disabledCategories", List.of(),
                        o -> o instanceof String s && !s.isBlank());
        builder.pop();

        SPEC = builder.build();
    }

    private Config() {
    }

    /** 排除判定：recipeKey（"命名空间:路径"）是否命中排除前缀。 */
    public static boolean isExcluded(String recipeKey) {
        for (String prefix : EXCLUDED_RECIPE_PREFIXES.get()) {
            if (recipeKey.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
