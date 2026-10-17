package com.sagisawa.maidrestaurantcooking.cooktask.flex;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.ysbbbbbb.kaleidoscopecookery.api.recipe.soupbase.ISoupBase;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StockpotBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.FlexStockpotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.soupbase.SoupBaseManager;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModSoupBases;
import com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils;
import com.mastermarisa.maid_restaurant.cooktask.StockpotCookTask;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.FakePlayerUtils;
import com.mastermarisa.maid_restaurant.utils.ItemHandlerUtils;
import com.mastermarisa.maid_restaurant.utils.component.RecipeData;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import com.sagisawa.maidrestaurantcooking.utils.RecipeDataFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 森罗厨房「模糊汤锅」配方（kaleidoscope_cookery:flex_stockpot，51 条）的女仆烹饪任务。
 *
 * <p>实现注记：同 FlexPotCookTask——FlexStockpotRecipe 与 StockpotRecipe 互不继承
 * （final Record：ingredients/soupBase/result/time/carrier/visuals），父类状态机的
 * STOCKPOT_RECIPE 查不到 flex 配方，故继承 + 换键复制。汤锅 BE 原生支持 flex
 * （StockpotBlockEntity 内建 flexQuickCheck）。父类 tickState2/takeLid 的行为经继承/复制保留。
 */
public class FlexStockpotCookTask extends StockpotCookTask {
    /** 模糊配方暂无黑名单条目；机制保留。 */
    public static final List<String> BLACK_LIST = new ArrayList<>();

    @Override
    public String getUID() {
        return "FlexStockpotCookTask";
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipes.FLEX_STOCKPOT_RECIPE;
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends Recipe<?>> recipeHolder, Level level) {
        FlexStockpotRecipe recipe = (FlexStockpotRecipe) recipeHolder.value();
        List<StackPredicate> predicates = new ArrayList<>(recipe.getIngredients().stream().filter(s -> !s.isEmpty()).map(StackPredicate::new).toList());
        if (!recipe.carrier().isEmpty()) {
            for (int i = 0; i < recipe.result().getCount(); i++) {
                predicates.add(StackPredicate.of(recipe.carrier()));
            }
        }

        predicates.add(StackPredicate.of(SoupBaseManager.getSoupBase(recipe.soupBase())::isSoupBase));
        return predicates;
    }

    /** 森罗 StockpotBlockEntity 私有 recipeId 字段（F-1：缓存反射，替代本体 mixin accessor）。 */
    private static volatile java.lang.reflect.Field recipeIdField = null;
    private static boolean recipeIdResolved = false;

    private static ResourceLocation readRecipeId(StockpotBlockEntity pot) {
        if (!recipeIdResolved) {
            try {
                java.lang.reflect.Field f = StockpotBlockEntity.class.getDeclaredField("recipeId");
                f.setAccessible(true);
                recipeIdField = f;
            } catch (Throwable t) {
                com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking.LOGGER.warn(
                        "[kitchen-ready] StockpotBlockEntity.recipeId 反射解析失败，flex 配料采集降级", t);
            }
            recipeIdResolved = true;
        }
        if (recipeIdField == null) return null;
        try {
            return (ResourceLocation) recipeIdField.get(pot);
        } catch (Throwable t) {
            com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking.LOGGER.warn(
                    "[kitchen-ready] recipeId 读取失败", t);
            return null;
        }
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new ArrayList<>();
        if (level.getBlockEntity(pos) instanceof StockpotBlockEntity pot) {
            ans.addAll(pot.getInputs().stream().filter(s -> !s.isEmpty()).toList());
            if (pot.getSoupBase() != null) {
                ans.add(pot.getSoupBase().getDisplayStack());
            }

            if (pot.getStatus() == 3) {
                // F-1：不再引用本体 mod 的 mixin accessor 类——mixin 配置定义的类禁止普通代码
                // 直接类加载（sponge-mixin IllegalClassLoadError，同名机制已在 business 服崩实证），
                // 改缓存反射读森罗私有 recipeId 字段；解析失败降级为不采集 flex 配料（任务跳过）
                ResourceLocation recipeId = readRecipeId(pot);
                if (recipeId == null) {
                    return ans;
                }
                // 本任务只接 flex 类型请求；类型不符（非 flex 配方）时自然跳过
                level.getRecipeManager().byKey(recipeId).ifPresent(holder -> {
                    if (!(holder.value() instanceof FlexStockpotRecipe recipe)) {
                        return;
                    }
                    recipe.getIngredients().stream().filter(i -> i.getItems().length > 0).forEach(s -> ans.add(s.getItems()[0]));
                    // F-011：空 carrier 的 getItems() 为空数组，直接取 [0] 越界
                    if (recipe.carrier().getItems().length > 0 && recipe.result().getCount() - pot.getTakeoutCount() > 0) {
                        ans.add(recipe.carrier().getItems()[0].copyWithCount(recipe.result().getCount() - pot.getTakeoutCount()));
                    }
                    ans.add(SoupBaseManager.getSoupBase(recipe.soupBase()).getDisplayStack());
                });
            }
        }

        return ans;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        if (!(level.getBlockEntity(pos) instanceof StockpotBlockEntity pot)) {
            return;
        }
        switch (pot.getStatus()) {
            case 0:
                this.tickState0(level, maid, pos, pot, request);
                break;
            case 1:
                this.tickState1(level, maid, pos, pot, request);
                break;
            case 2:
                this.tickState2(level, maid, pos, pot, request);
                break;
            case 3:
                this.tickState3(level, maid, pos, pot, request);
        }
    }

    @Override
    public List<RecipeData> getAllRecipeData(Level level) {
        RecipeManager manager = level.getRecipeManager();
        List<RecipeData> ans = new ArrayList<>();

        for (RecipeHolder<FlexStockpotRecipe> holder : manager.getAllRecipesFor(ModRecipes.FLEX_STOCKPOT_RECIPE)) {
            if (!BLACK_LIST.contains(holder.id().toString())) {
                ans.add(new RecipeData(holder.id(), ModRecipes.FLEX_STOCKPOT_RECIPE, this.getIcon(), holder.value().result()));
            }
        }

        return RecipeDataFilter.apply(ans);
    }

    private void tickState0(ServerLevel level, EntityMaid maid, BlockPos pos, StockpotBlockEntity pot, CookRequest request) {
        if (pot.hasLid()) {
            this.takeLid(level, maid, pos, pot);
        } else {
            RecipeHolder<?> holder = level.getRecipeManager().byKey(request.id).orElse(null);
            if (holder == null || !(holder.value() instanceof FlexStockpotRecipe recipe)) {
                MaidRestaurantCooking.LOGGER.error("[kitchen-ready] flex_stockpot 请求配方缺失或类型不符：{}（锅状态 {}）", request.id, pot.getStatus());
                return;
            }
            ResourceLocation soupBase = recipe.soupBase();
            ISoupBase iSoupBase = SoupBaseManager.getSoupBase(soupBase);
            if (soupBase.equals(ModSoupBases.WATER)) {
                int count = ItemHandlerUtils.count(maid.getAvailableInv(false), StackPredicate.of(iSoupBase::isSoupBase));
                if (count >= 2) {
                    FakePlayer fakePlayer = FakePlayerUtils.getPlayer(level);
                    pot.addSoupBase(level, fakePlayer, new ItemStack(Items.WATER_BUCKET));
                    fakePlayer.getInventory().clearContent();
                    maid.swing(InteractionHand.OFF_HAND);
                    return;
                }
            }

            ItemStack bucket = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(iSoupBase::isSoupBase), true);
            if (!bucket.isEmpty()) {
                pot.addSoupBase(level, maid, bucket);
                maid.swing(InteractionHand.OFF_HAND);
            }
        }
    }

    private void tickState1(ServerLevel level, EntityMaid maid, BlockPos pos, StockpotBlockEntity pot, CookRequest request) {
        if (pot.hasLid()) {
            this.takeLid(level, maid, pos, pot);
        } else {
            RecipeHolder<?> holder = level.getRecipeManager().byKey(request.id).orElse(null);
            if (holder == null || !(holder.value() instanceof FlexStockpotRecipe recipe)) {
                MaidRestaurantCooking.LOGGER.error("[kitchen-ready] flex_stockpot 请求配方缺失或类型不符：{}（锅状态 {}）", request.id, pot.getStatus());
                return;
            }
            List<StackPredicate> required = new ArrayList<>(recipe.ingredients().stream().filter(s -> !s.isEmpty()).map(StackPredicate::new).toList());
            required = ItemHandlerUtils.getRequired(required, pot.getInputs());
            if (required.isEmpty()) {
                ItemStack lid = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(ModItems.STOCKPOT_LID.asItem()), true);
                if (!lid.isEmpty()) {
                    pot.onLitClick(level, maid, lid);
                    maid.swing(InteractionHand.OFF_HAND);
                }
            } else {
                for (StackPredicate ingredient : required) {
                    ItemStack material = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, ingredient, true);
                    if (!material.isEmpty()) {
                        pot.addIngredient(level, maid, material);
                        maid.swing(InteractionHand.OFF_HAND);
                    }
                }
            }
        }
    }

    // 父类为 protected，这里真覆盖（换 flex 配方）；访问权限不可低于父类
    @Override
    protected void tickState3(ServerLevel level, EntityMaid maid, BlockPos pos, StockpotBlockEntity pot, CookRequest request) {
        // F-121：成品归属核对（同 FlexPot tickState2）
        ItemStack potResult = pot.getResult();
        RecipeHolder<?> ownerCheck = level.getRecipeManager().byKey(request.id).orElse(null);
        if (potResult != null && !potResult.isEmpty() && ownerCheck != null
                && ownerCheck.value() instanceof FlexStockpotRecipe ownerRecipe
                && potResult.getItem() != ownerRecipe.result().getItem()) {
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 汤锅内为非本单成品（{}），不取：{} @ {}", potResult.getItem(), request.id, pos);
            return;
        }
        if (pot.hasLid()) {
            this.takeLid(level, maid, pos, pot);
        } else {
            RecipeHolder<?> holder = level.getRecipeManager().byKey(request.id).orElse(null);
            if (holder == null || !(holder.value() instanceof FlexStockpotRecipe recipe)) {
                MaidRestaurantCooking.LOGGER.error("[kitchen-ready] flex_stockpot 请求配方缺失或类型不符：{}（锅状态 {}）", request.id, pot.getStatus());
                return;
            }
            ItemStack carrier = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(recipe.carrier()), true);
            if (!carrier.isEmpty()) {
                pot.takeOutProduct(level, maid, carrier);
                maid.swing(InteractionHand.OFF_HAND);
                if (pot.getTakeoutCount() == 0) {
                    request.remain--;
                }
            }
        }
    }

    /** 父类私有，复制（逻辑与父类一致，仅访问面差异）。 */
    private void takeLid(ServerLevel level, EntityMaid maid, BlockPos pos, StockpotBlockEntity pot) {
        ItemStack lid = pot.getLidItem().isEmpty() ? ModItems.STOCKPOT_LID.get().getDefaultInstance() : pot.getLidItem().copy();
        pot.setLidItem(ItemStack.EMPTY);
        pot.setChanged();
        level.setBlockAndUpdate(pos, level.getBlockState(pos).setValue(StockpotBlock.HAS_LID, false));
        maid.playSound(SoundEvents.LANTERN_BREAK, 0.5F, 0.5F);
        ItemUtils.getItemToLivingEntity(maid, lid);
        maid.swing(InteractionHand.OFF_HAND);
    }
}
