package com.sagisawa.maidrestaurantcooking.cooktask.crafting;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils;
import com.mastermarisa.maid_restaurant.api.ICookTask;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.BlockUsageManager;
import com.mastermarisa.maid_restaurant.utils.ItemHandlerUtils;
import com.mastermarisa.maid_restaurant.utils.RequestManager;
import com.mastermarisa.maid_restaurant.utils.SearchUtils;
import com.mastermarisa.maid_restaurant.utils.component.RecipeData;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import com.sagisawa.maidrestaurantcooking.MaidRestaurantCooking;
import com.sagisawa.maidrestaurantcooking.config.Config;
import com.sagisawa.maidrestaurantcooking.utils.RecipeDataFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * M8 合成台食物合成任务（minecraft:crafting，shaped+shapeless 单一类型覆盖）。
 *
 * <p>设计（2026-10-18 实证定稿，证据=gradle 缓存 neoform 反编译源码）：
 * <ul>
 *   <li>原版仅单一 {@code RecipeType.CRAFTING}：ShapedRecipe/ShapelessRecipe 均不覆盖
 *       getType()，一个任务类吃两类配方（本体 0.2.9 与已核对生态 jar 均未注册 CRAFTING，
 *       无同键覆盖；maidtavern/fox_trot_brew 不在本机 libs，残余风险由真机菜单验证兜底）。</li>
 *   <li>工作方块 = 原版合成台（CraftingTableBlock，无 BlockEntity）：纯方块状态判定
 *       （茶壶补放位先例），盒扫 + BlockUsageManager 占用过滤取最近。</li>
 *   <li>匹配免容器：直接构造 {@code CraftingInput.of(w, h, layout)}——工厂内部恒做
 *       最小包围盒裁剪（ofPositioned），shaped 按配方（JSON 已 shrink 的 w×h，空白=
 *       Ingredient.EMPTY）原位摆放，shapeless 在 3×3 内任意散放（matches 走
 *       stackedContents.canCraft，与位置无关）。</li>
 *   <li>无中间容器：抽取→构造→matches 安全门→assemble→交付全部在单次 cookTick 内
 *       完成，无跨 tick 状态；失败路径（中途缺料/matches 不符）已抽原料全部回包。</li>
 *   <li>菜单口径：产物含 FOOD 组件（DataComponents.FOOD，1.21.1 判据同炉式族）；
 *       craftingTable.enabled 运行时消费（构造期 config 未加载禁 .get()）。</li>
 * </ul>
 */
public class CraftingTableCookTask implements ICookTask {

    /** F-169/F-170：窗口化日志键（消息码 → 窗口截止时刻），30s 窗去重防跨消息/20Hz 刷屏压制。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> logWindows = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long LOG_WINDOW_MS = 30_000L;
    private static final int LOG_WINDOW_MAX = 64;

    /** 30s 窗内同键只响一次；过期条目顺手清理（仿 F-160）。 */
    private boolean shouldLog(String code) {
        long now = System.currentTimeMillis();
        Long until = logWindows.get(code);
        if (until != null && now < until) {
            return false;
        }
        if (logWindows.size() > LOG_WINDOW_MAX) {
            logWindows.entrySet().removeIf(e -> e.getValue() < now);
        }
        logWindows.put(code, now + LOG_WINDOW_MS);
        return true;
    }

    /** F-169：窗口化 WARN（键=消息码，防跨消息压制）。 */
    private void warnStall(String code, String format, Object... args) {
        if (shouldLog(code)) {
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] " + format, args);
        }
    }

    /** F-170：ERROR 级窗口化（配方缺失/产物为空等每 tick 路径的 20Hz 刷屏防线）。 */
    private void errorStall(String code, String format, Object... args) {
        if (shouldLog(code)) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] " + format, args);
        }
    }

    @Override
    public String getUID() {
        return "CraftingTableCookTask";
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(Items.CRAFTING_TABLE);
    }

    @Override
    public RecipeType<?> getType() {
        return RecipeType.CRAFTING;
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        if (!(recipeHolder.value() instanceof CraftingRecipe recipe)) {
            return List.of();
        }
        return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).map(StackPredicate::new).toList();
    }

    /**
     * 合成台锚点判据（r8 真机实证修正）：注册表键 {@code minecraft:crafting_table} 优先——
     * Visual Workbench 类「原键换实例」mod（RegistryEntryAdded 把原版键下的 Block 实例换成
     * 自家 BaseEntityBlock 子类）会让 {@code instanceof CraftingTableBlock} 永不命中、女仆
     * 找不到工位卡死；instanceof 分支保留，兼容「原版类 + 异键」的 modded 变体。
     */
    private boolean isCraftingAnchor(BlockState state) {
        if (BuiltInRegistries.BLOCK.getKey(state.getBlock())
                .equals(ResourceLocation.withDefaultNamespace("crafting_table"))) {
            return true;
        }
        return state.getBlock() instanceof CraftingTableBlock;
    }

    @Nullable
    @Override
    public BlockPos searchWorkBlock(ServerLevel level, EntityMaid maid, int horizontalSearchRange, int verticalSearchRange) {
        BlockPos center = maid.getBrainSearchPos();
        List<BlockPos> found = SearchUtils.search(center, horizontalSearchRange, verticalSearchRange,
                pos -> isCraftingAnchor(level.getBlockState(pos))
                        && BlockUsageManager.getUserCount(pos) <= 0);
        if (found.isEmpty()) {
            // F-128 同款诊断：区分「范围内无合成台」与「有但全被占用」，防替换类环境静默卡死
            boolean anyPresent = false;
            for (BlockPos p : BlockPos.withinManhattan(center, horizontalSearchRange, verticalSearchRange, horizontalSearchRange)) {
                if (isCraftingAnchor(level.getBlockState(p))) {
                    anyPresent = true;
                    break;
                }
            }
            warnStall(anyPresent ? "anchor-all-busy" : "anchor-absent",
                    anyPresent
                            ? "合成台工位全被占用，订单等待：{} @ {}"
                            : "范围内未找到合成台（注意：若环境把原版合成台替换成别类方块且键名不同，需扩锚点判据）：{} @ {}",
                    requestOf(maid), maid.blockPosition());
            return null;
        }
        return found.stream().min(Comparator.comparingDouble(p -> p.distSqr(maid.blockPosition()))).get();
    }

    /** 订单摘要（日志用；无单时占位）。 */
    private String requestOf(EntityMaid maid) {
        return RequestManager.peek(maid, 0) instanceof CookRequest cr ? cr.id.toString() : "-";
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return isCraftingAnchor(level.getBlockState(pos));
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        // 合成台无存储容器，无在工位内容物
        return List.of();
    }

    @Override
    public List<StackPredicate> getKitchenWares() {
        return List.of();
    }

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        RecipeHolder<?> holder = level.getRecipeManager().byKey(request.id).orElse(null);
        if (holder == null || !RecipeType.CRAFTING.equals(holder.value().getType())
                || !(holder.value() instanceof CraftingRecipe recipe)) {
            errorStall("crafting-recipe-missing:" + request.id, "合成台请求配方缺失或类型不符：{} @ {}", request.id, pos);
            return;
        }

        // 抽料布局：shaped 按配方网格原位摆放；shapeless 紧凑散放于 3×3 前段
        // （canCraft 与位置无关，紧凑排布保证 ofPositioned 裁剪出连续包围盒）。
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        ShapedRecipe shapedRecipe = recipe instanceof ShapedRecipe sr ? sr : null;
        boolean shaped = shapedRecipe != null;
        int w = shaped ? shapedRecipe.getWidth() : 3;
        int h = shaped ? shapedRecipe.getHeight() : 3;

        List<ItemStack> extracted = new ArrayList<>();
        List<ItemStack> layout = new ArrayList<>(w * h);
        for (int i = 0; i < w * h; i++) {
            layout.add(ItemStack.EMPTY);
        }
        int cursor = 0;
        // F-190：齐备预检——逐份配料在女仆包内计数核对，齐了才动抽取（防持续缺料时 20Hz 抽还 churn）
        var inv = maid.getAvailableInv(false);
        for (int idx = 0; idx < w * h && idx < ingredients.size(); idx++) {
            Ingredient ingredient = ingredients.get(idx);
            if (ingredient.isEmpty()) {
                continue;
            }
            if (ItemHandlerUtils.count(inv, StackPredicate.of(ingredient)) < 1) {
                return; // 缺料静默等待（未抽取零 churn；补货到位后下 tick 通过预检再抽）
            }
        }
        for (int idx = 0; idx < w * h && idx < ingredients.size(); idx++) {
            Ingredient ingredient = ingredients.get(idx);
            if (ingredient.isEmpty()) {
                continue;
            }
            ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(inv, 1,
                    StackPredicate.of(ingredient), true);
            if (take.isEmpty()) {
                // 预检通过后抽取仍失败 = 并发/边界态：已抽部分回包 + WARN（30s 窗）
                rollback(maid, extracted);
                warnStall("crafting-partial-missing", "合成台配料中途缺料，已抽原料回包等待补货：{} @ {}", request.id, pos);
                return;
            }
            extracted.add(take);
            layout.set(shaped ? idx : cursor++, take);
        }
        if (extracted.isEmpty()) {
            // F-190：到此处即异常配方（无任何非空配料）——静默跳过（缺料已在预检层返回，非缺料路径）
            return;
        }

        CraftingInput input = CraftingInput.of(w, h, layout);

        // matches 安全门（F-130/131 同款纪律：不符必须响，不静默装正常）
        if (!recipe.matches(input, level)) {
            rollback(maid, extracted);
            warnStall("crafting-matches-fail", "合成台配方 matches 不符（构造布局与配方不匹配），原料已回包：{} @ {}", request.id, pos);
            return;
        }

        ItemStack result = recipe.assemble(input, level.registryAccess());
        if (result.isEmpty()) {
            rollback(maid, extracted);
            errorStall("crafting-result-empty", "合成台配方产物为空，原料已回包：{} @ {}", request.id, pos);
            return;
        }
        ItemUtils.getItemToLivingEntity(maid, result);

        // 容器返还（牛奶桶→空桶等）：1.21.1 Recipe.getRemainingItems(input) 无 Level 参数
        for (ItemStack remainder : recipe.getRemainingItems(input)) {
            if (!remainder.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, remainder);
            }
        }

        // F-175：产物数量对账——合成台产物数量常态 ≠1（曲奇×8 等），最小合成单位=result.getCount()；
        // 单次合成核销 min(count, remain)，超出订单部分同批交付（进女仆包，属配方固有产出）
        int delivered = Math.min(result.getCount(), request.remain);
        request.remain = Math.max(0, request.remain - delivered);
        maid.swing(InteractionHand.OFF_HAND);
    }

    /** 已抽原料回包（满槽/缺料/matches 失败共用收口）。 */
    private void rollback(EntityMaid maid, List<ItemStack> extracted) {
        for (ItemStack stack : extracted) {
            ItemUtils.getItemToLivingEntity(maid, stack);
        }
    }

    @Override
    public List<RecipeData> getAllRecipeData(Level level) {
        if (!Config.CRAFTING_TABLE_ENABLED.get()) {
            return List.of();
        }
        List<RecipeData> ans = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack result = holder.value().getResultItem(level.registryAccess());
            // 食物过滤：仅可食产物进点单（同炉式族 FOOD 组件判据）
            if (!result.isEmpty() && result.has(DataComponents.FOOD)) {
                ans.add(new RecipeData(holder.id(), RecipeType.CRAFTING, this.getIcon(), result));
            }
        }
        return RecipeDataFilter.apply(ans);
    }
}
