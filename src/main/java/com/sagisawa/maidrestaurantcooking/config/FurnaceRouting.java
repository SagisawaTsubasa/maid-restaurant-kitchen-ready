package com.sagisawa.maidrestaurantcooking.config;

import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * 炉式映射表加载器（M1 公共件，M3 起被 T4 炉式任务消费）。
 *
 * <p>格式（common.toml furnaceRouting.routingRules，每行一条）：
 * {@code 源RecipeType键=目标工作方块id}，如 {@code minecraft:smelting=kaleidoscope_cookery:rotisserie}。
 *
 * <p>纪律：非法行（缺 =、键解析失败、目标方块 id 非法）记 WARN 并跳过该行（不静默装正常、
 * 也不整体失效）；全部非法时 map 为空，T4 任务据此判定「未启用」。
 * 注意（F-122 真实语义）：本表只校验 id 合法性；配置为非炉类方块（BE 非
 * AbstractFurnaceBlockEntity 子类）时该类型**不会回落原版炉**，而是找不到工作方块、
 * 请求挂起（运行期有一次性 WARN）。务必确认目标方块的 BE 是炉类。
 */
public final class FurnaceRouting {
    private record Rule(ResourceLocation sourceType, ResourceLocation targetBlock) {
    }

    private static volatile Map<String, ResourceLocation> cache;
    private static final Object LOCK = new Object();

    private FurnaceRouting() {
    }

    /** 惰性解析：首次访问读 toml 并校验；配置热重载后由消费者触发 invalidate()。 */
    public static Map<String, ResourceLocation> rules() {
        Map<String, ResourceLocation> local = cache;
        if (local == null) {
            synchronized (LOCK) {
                if (cache == null) {
                    cache = parse();
                }
                local = cache;
            }
        }
        return local;
    }

    /** 源类型键 → 目标工作方块 id；未配置返回 null。 */
    @Nullable
    public static ResourceLocation targetBlock(String recipeTypeKey) {
        return rules().get(recipeTypeKey);
    }

    /** 配置文件重载后调用，下一访问重新解析。 */
    public static void invalidate() {
        cache = null;
    }

    private static Map<String, ResourceLocation> parse() {
        Map<String, ResourceLocation> ans = new HashMap<>();
        int bad = 0;
        for (String line : Config.FURNACE_ROUTING_RULES.get()) {
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            int eq = s.indexOf('=');
            String[] parts = eq < 0 ? null : new String[]{s.substring(0, eq).trim(), s.substring(eq + 1).trim()};
            if (parts == null || parts[0].isEmpty() || parts[1].isEmpty()) {
                bad++;
                MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] 炉式路由规则非法（缺=或空段），已跳过：{}", s);
                continue;
            }
            ResourceLocation type = ResourceLocation.tryParse(parts[0]);
            ResourceLocation block = ResourceLocation.tryParse(parts[1]);
            if (type == null || block == null) {
                bad++;
                MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] 炉式路由规则键解析失败（非法 ResourceLocation），已跳过：{}", s);
                continue;
            }
            // F-133：目标方块 id 未注册（写错/模组未装）即时诊断，防运行期静默挂起
            if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(block)) {
                bad++;
                MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] 炉式路由目标方块未注册（写错或模组未装），已跳过：{}", s);
                continue;
            }
            ans.put(type.toString(), block);
        }
        if (bad > 0) {
            MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] 炉式路由共 {} 条非法规则被跳过，有效 {} 条", bad, ans.size());
        }
        return Map.copyOf(ans);
    }
}
