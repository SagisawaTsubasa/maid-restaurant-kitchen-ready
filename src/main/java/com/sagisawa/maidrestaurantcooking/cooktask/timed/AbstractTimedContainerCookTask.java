package com.sagisawa.maidrestaurantcooking.cooktask.timed;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mastermarisa.maid_restaurant.api.ICookTask;
import com.mastermarisa.maid_restaurant.utils.BlockUsageManager;
import com.mastermarisa.maid_restaurant.utils.SearchUtils;
import com.mastermarisa.maid_restaurant.utils.component.RecipeData;
import com.sagisawa.maidrestaurantcooking.utils.RecipeDataFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * T3 定时容器模板基类（M2 实证定型，见 docs/模板设计-T1-T4.md）。
 *
 * <p>沉淀自竹匾/腌菜罐/冰箱三例的共同形态：目标方块无本体 POI →
 * SearchUtils 盒扫 + BlockUsageManager 占用过滤；getAllRecipeData = 全量扫描
 * 本配方类型 → RecipeData → 排除过滤器出口；getType() 统一走 recipeType()。
 * cookTick 因各 BE API 差异大，不在此抽象，由子类按实证写。
 *
 * <p>泛型注记：NeoForge 1.21.1 的 RecipeManager.getAllRecipesFor 签名为
 * {@code <I extends RecipeInput, T extends Recipe<I>>}（javap 实测），{@code R extends Recipe<?>}
 * 推断不过，故内部以 raw 调用 + unchecked 收窄。
 *
 * @param <R> 本类型配方类
 */
public abstract class AbstractTimedContainerCookTask<R extends Recipe<?>> implements ICookTask {

    /** 目标 BE 判定（子类给 instanceof 谓词，可附加如 isTop 等条件）。 */
    protected abstract Predicate<BlockPos> workBlockFilter(ServerLevel level);

    /** 本类型 RecipeType 实例（运行期取值，注册表已填充）。 */
    protected abstract RecipeType<?> recipeType();

    /** 配方类判定（byKey 结果 instanceof 校验用）。 */
    protected abstract Class<R> recipeClass();

    /** 配方产物（菜单展示与完成判定；须走目标 mod 公开访问器，禁碰 protected 字段）。 */
    protected abstract ItemStack recipeResult(R recipe, Level level);

    @Override
    public RecipeType<?> getType() {
        return recipeType();
    }

    @Nullable
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public BlockPos searchWorkBlock(ServerLevel level, EntityMaid maid, int horizontalSearchRange, int verticalSearchRange) {
        BlockPos center = maid.getBrainSearchPos();
        Predicate<BlockPos> filter = workBlockFilter(level);
        List<BlockPos> found = SearchUtils.search(
                center, horizontalSearchRange, verticalSearchRange,
                pos -> filter.test(pos) && BlockUsageManager.getUserCount(pos) <= 0
        );
        return !found.isEmpty() ? found.stream().min(Comparator.comparingDouble(p -> p.distSqr(maid.blockPosition()))).get() : null;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<RecipeData> getAllRecipeData(Level level) {
        List<RecipeData> ans = new ArrayList<>();
        for (RecipeHolder<R> holder : (List<RecipeHolder<R>>) (List<?>) level.getRecipeManager().getAllRecipesFor((RecipeType) recipeType())) {
            ans.add(new RecipeData(holder.id(), recipeType(), this.getIcon(), recipeResult(holder.value(), level)));
        }
        return RecipeDataFilter.apply(ans);
    }

    /** byKey 解析 + 配方类校验；不符返回 null（cookTick 里 ERROR 落日志）。 */
    @Nullable
    @SuppressWarnings("unchecked")
    protected R resolveRecipe(ServerLevel level, com.mastermarisa.maid_restaurant.request.CookRequest request) {
        RecipeHolder<?> holder = level.getRecipeManager().byKey(request.id).orElse(null);
        // F-130：类匹配之外再校验 RecipeType 归属，防跨类型同名 id 错配
        if (holder == null || !recipeClass().isInstance(holder.value())
                || (recipeType() != null && !recipeType().equals(holder.value().getType()))) {
            return null;
        }
        return (R) holder.value();
    }
}
