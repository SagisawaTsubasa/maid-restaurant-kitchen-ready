package com.sagisawa.maidrestaurantcooking.cooktask.furnace;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mastermarisa.maid_restaurant.api.ICookTask;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.BlockUsageManager;
import com.mastermarisa.maid_restaurant.utils.ItemHandlerUtils;
import com.mastermarisa.maid_restaurant.utils.SearchUtils;
import com.mastermarisa.maid_restaurant.utils.component.RecipeData;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import com.sagisawa.maidrestaurantcooking.config.FurnaceRouting;
import com.sagisawa.maidrestaurantcooking.utils.DeliveryUtil;
import com.sagisawa.maidrestaurantcooking.utils.RecipeDataFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * T4 炉式路由任务基类：烧炼/烟熏/高炉三类原版配方接入女仆点单。
 *
 * <p>设计（2026-10-17 实证定稿）：
 * <ul>
 *   <li>路由表默认目标 = 原版炉自身（furnace/smoker/blast_furnace）——自带燃料机制、
 *       Container API 公开、环境必有；映射表（furnaceRouting.routingRules）可把某类型
 *       改路由到 modded 烤/熏方块，本类扫描时优先匹配映射 id，未配置回落原版炉。</li>
 *   <li>食物过滤：getAllRecipeData 只收产物含 FOOD/CONSUMABLE 组件的配方（矿物/材料
 *       产物不进点单，v5 §一「类型内过滤」）。</li>
 *   <li>燃料：女仆自动补——炉未点燃且输入在位时投 1 个燃料（AbstractFurnaceBlockEntity.isFuel）。</li>
 *   <li>modded 目标方块的驱动（旋风烤肉塔等）不做通用猜测：路由到非原版炉的行，
 *       扫描会命中其 BE 但驱动仅按原版炉 Container 语义尝试；不可驱动的目标如实由
 *       真机验证暴露并登记边界（M3 记录）。</li>
 * </ul>
 */
public abstract class AbstractFurnaceRoutingCookTask<T extends AbstractCookingRecipe> implements ICookTask {

    /** 原版炉 BE 类型（子类给具体类：FurnaceBlockEntity 等）。 */
    protected abstract Class<? extends AbstractFurnaceBlockEntity> vanillaFurnaceClass();

    /** 本类型 RecipeType（minecraft:smelting/smoking/blasting）。 */
    protected abstract RecipeType<T> recipeType();

    /** 路由表查询键（同 recipeType 的 id 串）。 */
    protected String routingKey() {
        ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.getKey(recipeType());
        return key == null ? "" : key.toString();
    }

    /** 路由目标方块 id；未配置返回 null（回落原版炉）。 */
    @Nullable
    protected ResourceLocation routedTarget() {
        return FurnaceRouting.targetBlock(routingKey());
    }

    @Override
    public String getUID() {
        return "FurnaceRoutingCookTask." + routingKey();
    }

    /** F-122/F-137：路由误配 WARN 上次响出时间戳（30s 时间窗去重，重载后自然恢复）。 */
    private volatile long misconfiguredLastWarnMs;

    @Nullable
    @Override
    public BlockPos searchWorkBlock(ServerLevel level, EntityMaid maid, int horizontalSearchRange, int verticalSearchRange) {
        BlockPos center = maid.getBrainSearchPos();
        ResourceLocation target = routedTarget();
        Predicate<BlockPos> beCheck;
        if (target == null) {
            Class<? extends AbstractFurnaceBlockEntity> clazz = vanillaFurnaceClass();
            beCheck = pos -> clazz.isInstance(level.getBlockEntity(pos));
        } else {
            // F-004：路由目标必须同时是可驱动的炉 BE，否则女仆站定空转
            beCheck = pos -> target.equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()))
                    && level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity;
        }
        List<BlockPos> found = SearchUtils.search(
                center, horizontalSearchRange, verticalSearchRange,
                pos -> beCheck.test(pos) && BlockUsageManager.getUserCount(pos) <= 0
        );
        if (found.isEmpty() && target != null) {
            // F-128：区分"方块在场但 BE 不符"（确凿误配，响出）与"方块未放置"（不误报）
            boolean idPresentButWrongBe = false;
            for (BlockPos p : BlockPos.withinManhattan(center, horizontalSearchRange, verticalSearchRange, horizontalSearchRange)) {
                if (target.equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()))
                        && !(level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity)) {
                    idPresentButWrongBe = true;
                    break;
                }
            }
            if (idPresentButWrongBe && System.currentTimeMillis() - misconfiguredLastWarnMs > 30_000L) {
                misconfiguredLastWarnMs = System.currentTimeMillis();
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 炉式路由目标 {} 的 BE 非炉类（误配）：该类型挂起且不会回落原版炉：{}", target, getUID());
            }
        }
        return !found.isEmpty() ? found.stream().min(Comparator.comparingDouble(p -> p.distSqr(maid.blockPosition()))).get() : null;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        ResourceLocation target = routedTarget();
        if (target == null) {
            return vanillaFurnaceClass().isInstance(level.getBlockEntity(pos));
        }
        // F-004：路由目标需方块 id 命中且 BE 可驱动
        return target.equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()))
                && level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity;
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new ArrayList<>();
        if (level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace) {
            ItemStack input = furnace.getItem(0);
            if (!input.isEmpty()) {
                ans.add(input.copy());
            }
        }
        return ans;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return;
        }
        RecipeHolder<?> holder = level.getRecipeManager().byKey(request.id).orElse(null);
        if (holder == null || !recipeType().equals(holder.value().getType())) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] 炉式请求配方缺失或类型不符：{}（{}）", request.id, getUID());
            return;
        }
        AbstractCookingRecipe recipe = (AbstractCookingRecipe) holder.value();
        ItemStack expectResult = recipe.getResultItem(level.registryAccess());

        // 产物取出
        ItemStack output = furnace.getItem(2);
        if (!output.isEmpty() && output.getItem() == expectResult.getItem()) {
            ItemStack taken = furnace.removeItem(2, output.getCount());
            DeliveryUtil.deliverToMaidInv(maid, taken);
            maid.swing(InteractionHand.OFF_HAND);
            request.remain--;
            return;
        }

        // F-125 前移：输出槽被异物占据 → WARN 停摆（取不走且续投无意义）
        if (!output.isEmpty() && output.getItem() != expectResult.getItem()) {
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 炉输出槽为非本单产物（{}），停摆待处理：{} @ {}", output.getItem(), request.id, pos);
            return;
        }

        // 补燃料：统一出口（F-124：输入等待/投料后也必须可达，否则炉内有料无火永挂）
        // 条件：炉未点燃且燃料槽空（F-101 防覆盖销毁既有燃料栈）；燃料槽非燃料异物 → WARN（F-115）；
        // LIT 读取前守卫方块类型（F-004 防 IAE）
        BlockState furnaceState = level.getBlockState(pos);
        boolean lit = furnaceState.getBlock() instanceof AbstractFurnaceBlock
                && furnaceState.getValue(AbstractFurnaceBlock.LIT);
        if (!lit && !furnace.getItem(1).isEmpty() && !AbstractFurnaceBlockEntity.isFuel(furnace.getItem(1))) {
            // F-115：燃料槽被非燃料异物占据，本单燃料进不去——对称 F-005 响出
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 炉燃料槽为非燃料异物（{}）：{} @ {}", furnace.getItem(1).getItem(), request.id, pos);
            return;
        }

        // 已有输入在炉内：匹配则等待（先补燃料），不匹配（异物）落 WARN（F-005：防永久静默等待）
        ItemStack input = furnace.getItem(0);
        if (!input.isEmpty()) {
            boolean matches = recipe.getIngredients().stream().anyMatch(i -> !i.isEmpty() && i.test(input));
            if (!matches) {
                MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 炉内存在非本单输入，请求可能无法完成：{} @ {}（炉内 {}）", request.id, pos, input.getItem());
                return;
            }
            if (!lit && furnace.getItem(1).isEmpty()) {
                topUpFuel(level, maid, pos, furnace, request);
            }
            return;
        }

        // 投料
        ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                StackPredicate.of(s -> recipe.getIngredients().stream().anyMatch(i -> !i.isEmpty() && i.test(s))), true);
        if (!take.isEmpty()) {
            furnace.setItem(0, take);
            furnace.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
            // 新投入的料需要燃料点燃（F-124：投料同 tick 即补火）
            if (!lit && furnace.getItem(1).isEmpty()) {
                topUpFuel(level, maid, pos, furnace, request);
            }
        }
    }

    /** F-140：无燃料 WARN 上次响出时间戳（30s 时间窗去重）。 */
    private volatile long noFuelLastWarnMs;

    /** 补 1 个燃料（调用方保证：!lit 且燃料槽空）；女仆无燃料时响出（F-140：防订单静默永挂）。 */
    private void topUpFuel(ServerLevel level, EntityMaid maid, BlockPos pos, AbstractFurnaceBlockEntity furnace, CookRequest request) {
        ItemStack fuel = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                StackPredicate.of(AbstractFurnaceBlockEntity::isFuel), true);
        if (!fuel.isEmpty()) {
            furnace.setItem(1, fuel);
            furnace.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
        } else if (System.currentTimeMillis() - noFuelLastWarnMs > 30_000L) {
            noFuelLastWarnMs = System.currentTimeMillis();
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 炉等待点火但女仆无燃料，订单挂起：{} @ {}", request.id, pos);
        }
    }

    @Override
    public RecipeType<?> getType() {
        return recipeType();
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(Items.FURNACE);
    }

    @Override
    public List<RecipeData> getAllRecipeData(Level level) {
        List<RecipeData> ans = new ArrayList<>();
        for (RecipeHolder<T> holder : level.getRecipeManager().getAllRecipesFor(recipeType())) {
            ItemStack result = holder.value().getResultItem(level.registryAccess());
            // 食物过滤：仅可食产物进点单（矿物/材料不进）；1.21.1 可食判据 = FOOD 组件
            if (!result.isEmpty() && result.has(net.minecraft.core.component.DataComponents.FOOD)) {
                ans.add(new RecipeData(holder.id(), recipeType(), this.getIcon(), result));
            }
        }
        return RecipeDataFilter.apply(ans);
    }

    @Override
    public List<StackPredicate> getKitchenWares() {
        return List.of();
    }
}
