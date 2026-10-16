package com.sagisawa.maidrestaurantcooking.cooktask.kaleidoscope;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.TeapotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.TeapotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.serializer.TeapotRecipeSerializer;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import com.mastermarisa.maid_restaurant.api.ICookTask;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.BlockUsageManager;
import com.mastermarisa.maid_restaurant.utils.FakePlayerUtils;
import com.mastermarisa.maid_restaurant.utils.ItemHandlerUtils;
import com.mastermarisa.maid_restaurant.utils.SearchUtils;
import com.mastermarisa.maid_restaurant.utils.component.RecipeData;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import com.sagisawa.maidrestaurantcooking.utils.RecipeDataFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 森罗厨房「茶壶」（kaleidoscope_cookery:teapot，20 条）的女仆烹饪任务。
 *
 * <p>实证（2026-10-17）：BE 流程 = 注液(≥1000mB 流体容器)→投料 1 个→自计时（热源下方）
 * →status2→takeTeapot。注意 takeTeapot 会 setBlock(AIR) 拆掉茶壶并把带数据的茶壶物品
 * 交还用户——女仆循环必须含「补放」：searchWorkBlock 优先找已放置的茶壶 BE，找不到则
 * 找「热源上方空位」并把女仆背包里的茶壶物品放上去（T3+补放混合形态，见模板设计文档）。
 */
public class TeapotCookTask implements ICookTask {

    @Override
    public String getUID() {
        return "TeapotCookTask";
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ModItems.TEAPOT.get());
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipes.TEAPOT_RECIPE;
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends Recipe<?>> recipeHolder, Level level) {
        TeapotRecipe recipe = (TeapotRecipe) recipeHolder.value();
        // 流体容器谓词：任意流体容器首罐 ≥1000mB 且流体与配方一致；配料 1 个
        StackPredicate fluidContainer = StackPredicate.of(stack -> hasFluid(stack, recipe.teaFluid()));
        return List.of(fluidContainer, StackPredicate.of(recipe.ingredient()));
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new ArrayList<>();
        if (level.getBlockEntity(pos) instanceof TeapotBlockEntity teapot) {
            if (!teapot.getInput().isEmpty()) {
                ans.add(teapot.getInput().copy());
            }
            // 已注液：流体已入壶，容器不返还，无法用物品如实表达——cookTick 的注液守卫防重复投
        }
        return ans;
    }

    @Nullable
    @Override
    public BlockPos searchWorkBlock(ServerLevel level, EntityMaid maid, int horizontalSearchRange, int verticalSearchRange) {
        BlockPos center = maid.getBrainSearchPos();
        // 优先：已放置的茶壶
        List<BlockPos> placed = SearchUtils.search(
                center, horizontalSearchRange, verticalSearchRange,
                pos -> level.getBlockEntity(pos) instanceof TeapotBlockEntity && BlockUsageManager.getUserCount(pos) <= 0
        );
        if (!placed.isEmpty()) {
            return placed.stream().min(Comparator.comparingDouble(p -> p.distSqr(maid.blockPosition()))).get();
        }
        // 补放位：热源正上方的空气位（茶壶 hasHeatSource 检查正下方），且女仆带茶壶物品；
        // 加占用过滤（F-015：全仓搜索谓词统一带 BlockUsage）
        if (findTeapotItem(maid).isEmpty()) {
            return null;
        }
        List<BlockPos> spots = SearchUtils.search(
                center, horizontalSearchRange, verticalSearchRange,
                pos -> isPlacementSpot(level, pos)
                        && com.mastermarisa.maid_restaurant.utils.BlockUsageManager.getUserCount(pos) <= 0
        );
        return !spots.isEmpty() ? spots.stream().min(Comparator.comparingDouble(p -> p.distSqr(maid.blockPosition()))).get() : null;
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof TeapotBlockEntity || isPlacementSpot(level, pos);
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        RecipeHolder<?> holder = level.getRecipeManager().byKey(request.id).orElse(null);
        if (holder == null || !(holder.value() instanceof TeapotRecipe recipe)) {
            MaidRestaurantCooking.LOGGER.error("[xia-de-chu-fang] teapot 请求配方缺失或类型不符：{}", request.id);
            return;
        }

        // 补放位：放茶壶（下 tick 起成为常规 BE 目标）
        if (!(level.getBlockEntity(pos) instanceof TeapotBlockEntity teapot)) {
            // F-010：显式抽取 1 个再放置，杜绝对背包原栈引用的隐式扣减歧义
            ItemStack teapotItem = extractTeapotItem(maid);
            if (teapotItem.isEmpty()) {
                return;
            }
            placeTeapot(level, pos, teapotItem, maid);
            return;
        }

        switch (teapot.getStatus()) {
            case 2 -> {
                // 完成：取茶（BE 拆除并把茶壶物品交还女仆，下轮订单由补放位循环）
                // F-118：壶内成品非本单茶时不动（防他单 brew 被本单取走计数）
                ItemStack brewed = teapot.getResult();
                if (!brewed.isEmpty() && brewed.getItem() != recipe.result().getItem()) {
                    MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] 茶壶内为非本单茶品（{}），不取：{} @ {}", brewed.getItem(), request.id, pos);
                    return;
                }
                if (teapot.takeTeapot(level, maid)) {
                    maid.swing(InteractionHand.OFF_HAND);
                    request.remain--;
                }
            }
            case 1 -> {
                // 煮沸中，等待
            }
            default -> {
                // status 0：先注液，后投料（BE 约束：无液不收料）
                ResourceLocation fluidId = teapot.getTeaFluidId();
                if (fluidId == null || fluidId.equals(TeapotRecipeSerializer.EMPTY_TEA_FLUID)) {
                    ItemStack container = extractFluidContainer(maid, recipe.teaFluid());
                    if (!container.isEmpty()) {
                        teapot.addTeaFluid(level, maid, container);
                        maid.swing(InteractionHand.OFF_HAND);
                    }
                    return;
                }
                if (teapot.getInput().isEmpty()) {
                    ItemStack ingredient = ItemHandlerUtils.tryExtractSingleSlot(
                            maid.getAvailableInv(false), 1, StackPredicate.of(recipe.ingredient()), true);
                    if (!ingredient.isEmpty()) {
                        teapot.addIngredient(level, maid, ingredient);
                        maid.swing(InteractionHand.OFF_HAND);
                    }
                }
                // 注液+投料完毕：BE 自计时（需热源，isValidWorkBlock/searchWorkBlock 已过滤）
            }
        }
    }

    @Override
    public List<RecipeData> getAllRecipeData(Level level) {
        List<RecipeData> ans = new ArrayList<>();
        for (RecipeHolder<TeapotRecipe> holder : level.getRecipeManager().getAllRecipesFor(ModRecipes.TEAPOT_RECIPE)) {
            ans.add(new RecipeData(holder.id(), ModRecipes.TEAPOT_RECIPE, this.getIcon(), holder.value().result()));
        }
        return RecipeDataFilter.apply(ans);
    }

    // ---------- 补放与流体工具 ----------

    private static boolean hasFluid(ItemStack stack, ResourceLocation fluidId) {
        if (stack.isEmpty()) {
            return false;
        }
        IFluidHandlerItem cap = stack.getCapability(Capabilities.FluidHandler.ITEM);
        if (cap == null) {
            return false;
        }
        FluidStack inTank = cap.getFluidInTank(0);
        return inTank.getAmount() >= 1000 && fluidId.equals(BuiltInRegistries.FLUID.getKey(inTank.getFluid()));
    }

    /** 从女仆背包取出一个「装着指定流体且 ≥1000mB」的容器。 */
    private static ItemStack extractFluidContainer(EntityMaid maid, ResourceLocation fluidId) {
        var inv = maid.getAvailableInv(false);
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (hasFluid(stack, fluidId)) {
                return inv.extractItem(i, 1, false);
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean isTeapotItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.TeapotBlock;
    }

    private static ItemStack findTeapotItem(EntityMaid maid) {
        var inv = maid.getAvailableInv(false);
        for (int i = 0; i < inv.getSlots(); i++) {
            if (isTeapotItem(inv.getStackInSlot(i))) {
                return inv.getStackInSlot(i);
            }
        }
        return ItemStack.EMPTY;
    }

    /** 抽取式：从女仆背包取出 1 个茶壶物品（放置消耗即该栈本体，无引用歧义）。 */
    private static ItemStack extractTeapotItem(EntityMaid maid) {
        var inv = maid.getAvailableInv(false);
        for (int i = 0; i < inv.getSlots(); i++) {
            if (isTeapotItem(inv.getStackInSlot(i))) {
                return inv.extractItem(i, 1, false);
            }
        }
        return ItemStack.EMPTY;
    }

    /** 热源（茶壶判定：正下方 LIT 属性或无 LIT 热源标签）正上方为空气 → 可补放。 */
    private static boolean isPlacementSpot(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }
        BlockState below = level.getBlockState(pos.below());
        if (below.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)) {
            return Boolean.TRUE.equals(below.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT));
        }
        return below.is(com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod.HEAT_SOURCE_BLOCKS_WITHOUT_LIT);
    }

    private static void placeTeapot(ServerLevel level, BlockPos pos, ItemStack teapotItem, EntityMaid maid) {
        if (!(teapotItem.getItem() instanceof BlockItem blockItem)) {
            // 抽取前已验茶壶 BlockItem，此处纯防御：回包防吞
            com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils.getItemToLivingEntity(maid, teapotItem);
            MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] 茶壶放置防御分支触发，物品已回包 @ {}", pos);
            return;
        }
        var fakePlayer = FakePlayerUtils.getPlayer(level);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos.below()), Direction.UP, pos.below(), false);
        net.minecraft.world.item.context.BlockPlaceContext ctx =
                new net.minecraft.world.item.context.BlockPlaceContext(level, fakePlayer, InteractionHand.MAIN_HAND, teapotItem, hit);
        var result = blockItem.place(ctx);
        if (result.consumesAction()) {
            MaidRestaurantCooking.LOGGER.debug("[xia-de-chu-fang] 女仆补放茶壶 @ {}", pos);
        } else {
            // F-102：place 失败（落位被占/状态拒绝）——已抽取的茶壶必须回包
            com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils.getItemToLivingEntity(maid, teapotItem);
            MaidRestaurantCooking.LOGGER.warn("[xia-de-chu-fang] 茶壶补放失败，物品已回包 @ {}", pos);
        }
    }
}
