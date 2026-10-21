package com.sagisawa.maidrestaurantcooking.cooktask.flex;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.PotBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.FlexPotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import com.mastermarisa.maid_restaurant.cooktask.PotCookTask;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.ItemHandlerUtils;
import com.mastermarisa.maid_restaurant.utils.component.RecipeData;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import com.sagisawa.maidrestaurantcooking.utils.DeliveryUtil;
import com.sagisawa.maidrestaurantcooking.utils.RecipeDataFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ItemLike;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 森罗厨房「模糊炒锅」配方（kaleidoscope_cookery:flex_pot，47 条）的女仆烹饪任务。
 *
 * <p>实现注记（实证 2026-10-17）：计划 v5 称此为 T1「子类改键」——实测 FlexPotRecipe 是
 * 与 PotRecipe 互不继承的 final Record（time/stirFryCount/Ingredient carrier/
 * NonNullList&lt;Ingredient&gt; ingredients/ItemStack result），父类 cookTick 状态机内
 * byKeyTyped(ModRecipes.POT_RECIPE, id) 查不到 flex 配方会 NPE。故本类为
 * <b>继承 + 状态机换键复制</b>：寻方块/工作方块判定/取输入/锅铲/图标全部继承父类，仅
 * getType/getUID/getIngredients/cookTick/getAllRecipeData 换用 FLEX_POT_RECIPE 与
 * FlexPotRecipe。锅方块端原生支持 flex（PotBlockEntity.startCooking 先查 pot 后查 flex），
 * 女仆投料后由 BE 自匹配，闭环成立。
 */
public class FlexPotCookTask extends PotCookTask {
    /** 模糊配方暂无黑名单条目；机制保留，发现问题配方填 id 即滤。 */
    public static final List<String> BLACK_LIST = new ArrayList<>();

    @Override
    public String getUID() {
        return "FlexPotCookTask";
    }

    @Override
    public RecipeType<?> getType() {
        // cookery ModRecipes 静态块赋值（RecipeType.simple），类加载即可安全引用
        return ModRecipes.FLEX_POT_RECIPE;
    }

    /** 契约（F-201）：本体 MaidStateManager.cookState 原地 addAll（0.2.9 all.java:8713），返回值必须可变——Stream.toList() 违约致 2026-10-20 崩服。 */
    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends Recipe<?>> recipeHolder, Level level) {
        FlexPotRecipe recipe = (FlexPotRecipe) recipeHolder.value();
        List<StackPredicate> predicates = recipe.getIngredients().stream().filter(s -> !s.isEmpty()).map(StackPredicate::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (!recipe.carrier().isEmpty()) {
            for (int i = 0; i < recipe.result().getCount(); i++) {
                predicates.add(StackPredicate.of(recipe.carrier()));
            }
        }

        predicates.add(StackPredicate.of(TagMod.OIL));
        return predicates;
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        if (!(level.getBlockEntity(pos) instanceof PotBlockEntity pot)) {
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

        for (RecipeHolder<FlexPotRecipe> holder : manager.getAllRecipesFor(ModRecipes.FLEX_POT_RECIPE)) {
            if (!BLACK_LIST.contains(holder.id().toString())) {
                ans.add(new RecipeData(holder.id(), ModRecipes.FLEX_POT_RECIPE, this.getIcon(), holder.value().result()));
            }
        }

        // 点单菜单排除过滤器（M1 公共件）：滤酒馆键/坩埚键，分类开关，防菜单过载
        return RecipeDataFilter.apply(ans);
    }

    private void tickState0(ServerLevel level, EntityMaid maid, BlockPos pos, PotBlockEntity pot, CookRequest request) {
        BlockState state = level.getBlockState(pos);
        if (!(Boolean) state.getValue(PotBlock.HAS_OIL)) {
            ItemStack oil = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, StackPredicate.of(TagMod.OIL), true);
            if (!oil.isEmpty()) {
                pot.onPlaceOil(level, maid, oil);
                maid.swing(InteractionHand.OFF_HAND);
            }
        } else {
            // byKeyTyped 在 1.21.1 为 private（本体自带 AT 才能用），用公开的 byKey + 类型校验
            RecipeHolder<?> holder = level.getRecipeManager().byKey(request.id).orElse(null);
            if (holder == null || !(holder.value() instanceof FlexPotRecipe recipe)) {
                MaidRestaurantCooking.LOGGER.error("[kitchen-ready] flex_pot 请求配方缺失或类型不符：{}（锅状态 {}）", request.id, pot.getStatus());
                return;
            }
            List<StackPredicate> required = new ArrayList<>(recipe.ingredients().stream().filter(s -> !s.isEmpty()).map(StackPredicate::new).toList());
            required = ItemHandlerUtils.getRequired(required, pot.getInputs());
            if (required.isEmpty()) {
                ItemStack shovel = ItemHandlerUtils.tryGet(maid.getAvailableInv(false), StackPredicate.of(TagMod.KITCHEN_SHOVEL));
                if (!shovel.isEmpty()) {
                    pot.onShovelHit(level, maid, shovel);
                    level.playSound(
                            (Player) null,
                            maid.blockPosition(),
                            SoundEvents.FIRE_EXTINGUISH,
                            SoundSource.BLOCKS,
                            1.0F,
                            (level.random.nextFloat() - level.random.nextFloat()) * 0.8F
                    );
                    maid.swing(InteractionHand.OFF_HAND);
                }
            } else {
                for (StackPredicate ingredient : required) {
                    ItemStack material = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1, ingredient, true);
                    if (!material.isEmpty()) {
                        ItemStack handRef = DeliveryUtil.snapshotMainHand(maid);
                        pot.addIngredient(level, maid, material);
                        DeliveryUtil.reclaimDeliveredMainHand(maid, handRef); // BE 内部旧交付（Pot javap addIngredient:1431）拒收/容器返还收接
                        maid.swing(InteractionHand.OFF_HAND);
                    }
                }
            }
        }
    }

    private void tickState1(ServerLevel level, EntityMaid maid, BlockPos pos, PotBlockEntity pot, CookRequest request) {
        ItemStack shovel = ItemHandlerUtils.tryGet(maid.getAvailableInv(false), StackPredicate.of(TagMod.KITCHEN_SHOVEL));
        if (!shovel.isEmpty()) {
            pot.onShovelHit(level, maid, shovel);
            maid.swing(InteractionHand.OFF_HAND);
            level.playSound(
                    (Player) null,
                    maid.blockPosition(),
                    SoundEvents.FIRE_EXTINGUISH,
                    SoundSource.BLOCKS,
                    1.0F,
                    (level.random.nextFloat() - level.random.nextFloat()) * 0.8F
            );
        }
    }

    private void tickState2(ServerLevel level, EntityMaid maid, BlockPos pos, PotBlockEntity pot, CookRequest request) {
        // F-126：配方缺失/类型不符时先 ERROR 出局——绝不在未核对归属的情况下取菜计数
        RecipeHolder<?> ownerCheck = level.getRecipeManager().byKey(request.id).orElse(null);
        if (ownerCheck == null || !(ownerCheck.value() instanceof FlexPotRecipe ownerRecipe)) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] flex_pot 请求配方缺失或类型不符：{}（锅状态 {}）", request.id, pot.getStatus());
            return;
        }
        // F-121：锅在为玩家/他单煮制时成品非本单——不取（防异菜被本单计数）
        ItemStack potResult = pot.getResult();
        if (potResult != null && !potResult.isEmpty()
                && potResult.getItem() != ownerRecipe.result().getItem()) {
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] 炒锅内为非本单成品（{}），不取：{} @ {}", potResult.getItem(), request.id, pos);
            return;
        }
        if (pot.hasCarrier()) {
            FlexPotRecipe recipe = ownerRecipe;
            ItemStack carrier = ItemHandlerUtils.tryExtractSingleSlot(
                    maid.getAvailableInv(false), pot.getResult().getCount(), StackPredicate.of(recipe.carrier()), true
            );
            if (!carrier.isEmpty()) {
                // R2-6（复验 2026-10-21）：快照取在 carrier 提取之后——getAvailableInv(false) 含手槽，
                // carrier 可能恰从主手抽出；快照若在提取前，会把「提取导致的主手变化」并入 BE 交付
                // 判据，边缘情形成品落「手占→掉地」。else 分支无提取、各自快照。
                ItemStack handRef = DeliveryUtil.snapshotMainHand(maid);
                pot.takeOutProduct(level, maid, carrier);
                DeliveryUtil.reclaimDeliveredMainHand(maid, handRef); // BE 内部旧交付（Pot javap takeOutProduct:1188/1262/1363）成品+容器收接
                maid.swing(InteractionHand.OFF_HAND);
                request.remain--;
            }
        } else {
            ItemStack handRef = DeliveryUtil.snapshotMainHand(maid);
            pot.takeOutProduct(level, maid, new ItemStack((ItemLike) ModItems.KITCHEN_SHOVEL.get()));
            DeliveryUtil.reclaimDeliveredMainHand(maid, handRef); // 铲子回手收接（同上）
            maid.swing(InteractionHand.OFF_HAND);
            request.remain--;
        }
    }

    private void tickState3(ServerLevel level, EntityMaid maid, BlockPos pos, PotBlockEntity pot, CookRequest request) {
        pot.reset();
        maid.swing(InteractionHand.OFF_HAND);
    }
}
