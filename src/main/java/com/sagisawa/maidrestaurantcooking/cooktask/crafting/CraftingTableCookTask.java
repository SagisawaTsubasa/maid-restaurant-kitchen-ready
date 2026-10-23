package com.sagisawa.maidrestaurantcooking.cooktask.crafting;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.sagisawa.maidrestaurantcooking.utils.DeliveryUtil;
import com.mastermarisa.maid_restaurant.api.ICookTask;
import com.mastermarisa.maid_restaurant.data.TagBlock;
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
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M8 合成台食物合成任务（minecraft:crafting，shaped+shapeless 单一类型覆盖）。
 *
 * <p>设计（2026-10-18 实证定稿，证据=gradle 缓存 neoform 反编译源码；2026-10-20 v0.2.2 自锚点修订）：
 * <ul>
 *   <li>原版仅单一 {@code RecipeType.CRAFTING}：ShapedRecipe/ShapelessRecipe 均不覆盖
 *       getType()，一个任务类吃两类配方（本体 0.2.9 与已核对生态 jar 均未注册 CRAFTING，
 *       无同键覆盖；maidtavern/fox_trot_brew 不在本机 libs，残余风险由真机菜单验证兜底）。</li>
 *   <li>锚点=自锚点（v0.2.2）：去外界工作方块依赖，女仆空手自合成——骑坐返回自身坐标原地合成
 *       （零方块零移动，就坐路径对自身坐标距离门 distSqr=0 天然通过）；站立在本体传入半径内扫
 *       「凳邻位」（工位 below() 四水平邻含 {@code TagBlock.SIT_BLOCK}，tag=cook_stool/chair），
 *       叠加凳位级空闲过滤（复刻本体 isChairAvailable，同凳多 marker 去重取最近）；无可用凳位
 *       兜底返回 null 并 WARN（键=消息码:女仆UUID，30s 窗）——null=本体 CheckRateManager
 *       3–6s 退避+请求留队（F-191，非静默也非每 tick 重扫）。历史：0.2.0 instanceof 被
 *       VisualWorkbench 原键换实例躲过、0.2.1 注册表键判据测试服仍未命中，环境方差不可控，
 *       详见 docs/验证记录/M8-合成台.md v0.2.2 节。</li>
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

    /** F-169：窗口化 WARN（键=消息码，防跨消息压制；兜底键=消息码:女仆UUID 防跨女仆压制，F-193）。 */
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

    /** 契约（F-201）：本体 MaidStateManager.cookState 原地 addAll（0.2.9 all.java:8713），返回值必须可变——Stream.toList() 违约致 2026-10-20 崩服。 */
    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        if (!(recipeHolder.value() instanceof CraftingRecipe recipe)) {
            return new ArrayList<>();
        }
        return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).map(StackPredicate::new)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    /**
     * 凳邻位判据（v0.2.2，同本体 MaidApproachCookBlockTask 站立路径唯一硬拒绝点，all.java:5719）：
     * 工位 below() 的 N/S/E/W 四邻之一为 {@code TagBlock.SIT_BLOCK}
     * （tag 内容=#kaleidoscope_cookery:cook_stool + #kaleidoscope_cookery:chair，见本体
     * TagBlock.addTags，反编译源 maid_restaurant-0.2.9.all.java:4628-4630）。
     */
    private boolean hasSeatNeighbor(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(below.relative(direction)).is(TagBlock.SIT_BLOCK)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 自锚点三段式（v0.2.2，R1 修复轮 F-191/F-192/F-193 修订 2026-10-20）：
     * ① 骑坐原地合成（零方块零移动）；② 站立 tag 级初扫+凳位级空闲过滤取最近 marker；
     * ③ 无可用凳位返回 null + WARN。
     *
     * <p>null 依据（本体 0.2.9 反编译 all.java 实证）：两消费端均优雅处理 null——站立路径
     * MaidApproachCookBlockTask.search 以 pos!=null 守卫+isWithinRestriction+逐凳
     * isChairAvailable（all.java:5715-5729），就坐路径 MaidSearchCookBlockTask 以
     * pos!=null && distSqr&lt;=4.0 守卫（all.java:6124-6146）；CheckRateManager 节流在
     * searchWorkBlock 调用之前（all.java:5564-5575，放行时置 60+rand(60) tick≈3–6s，
     * all.java:8080-8092）——null 后重试节奏恒为 3–6s+请求留队，是本体的「等待」词汇，
     * 「每 tick 重扫」不存在。骑坐恢复后 ① 即原地合成。残余竞态（两女仆同 tick 选中同凳）
     * 由本体走位阶段 isChairAvailable 仲裁，败者 3–6s 后重扫；任务侧无 mixin 无法观测本体
     * 拒绝（如实声明，见 docs/验证记录/M8-合成台.md v0.2.2 节）。
     */
    @Nullable
    @Override
    public BlockPos searchWorkBlock(ServerLevel level, EntityMaid maid, int horizontalSearchRange, int verticalSearchRange) {
        // ① 骑坐：原地合成，零方块零移动（就坐路径对自身坐标距离门 distSqr=0 天然通过）
        if (maid.isPassenger()) {
            return maid.blockPosition();
        }
        // ② 站立：tag 级初扫（凳邻位+占用登记过滤，谓词不变），再做凳位级空闲过滤
        BlockPos center = maid.getBrainSearchPos();
        List<BlockPos> raw = SearchUtils.search(center, horizontalSearchRange, verticalSearchRange,
                pos -> hasSeatNeighbor(level, pos) && BlockUsageManager.getUserCount(pos) <= 0);
        if (raw.isEmpty()) {
            // ③ 兜底 a：无凳邻位——null 退避+请求留队（本体等待词汇），WARN 键 per-maid（F-193）
            warnStall("no-seat-neighbor:" + maid.getUUID(),
                    "附近无可用凳位（cook_stool/chair tag），站立合成等待：{} 女仆={} @ {}",
                    requestOf(maid), maid.getName().getString(), maid.blockPosition());
            return null;
        }
        BlockPos nearest = nearestFreeSeatMarker(level, maid, raw);
        if (nearest == null) {
            // ③ 兜底 b：有凳邻位但凳位全被其他女仆占用——同上 null 退避，可观测诊断（F-192）
            warnStall("seat-all-busy:" + maid.getUUID(),
                    "凳邻位存在但凳位全被占用，站立合成等待：{} 女仆={} @ {}",
                    requestOf(maid), maid.getName().getString(), maid.blockPosition());
            return null;
        }
        return nearest;
    }

    /**
     * 凳位级空闲过滤（F-192，作用于 raw 初扫结果）：对每个凳邻位 marker 取其 below() 四邻中
     * {@code TagBlock.SIT_BLOCK} 命中的凳位，要求 {@link #isSeatFree}；同凳多 marker 只保留
     * 距女仆最近者（HashMap 显式距离比较，不依赖 SearchUtils 返回顺序）；过滤后取最近 marker，
     * 全无空闲返回 null（调用方出 seat-all-busy WARN）。
     */
    @Nullable
    private BlockPos nearestFreeSeatMarker(ServerLevel level, EntityMaid maid, List<BlockPos> raw) {
        BlockPos maidPos = maid.blockPosition();
        Map<BlockPos, BlockPos> seatToMarker = new HashMap<>();
        for (BlockPos marker : raw) {
            BlockPos below = marker.below();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos seat = below.relative(direction);
                if (!level.getBlockState(seat).is(TagBlock.SIT_BLOCK) || !isSeatFree(level, maid, seat)) {
                    continue;
                }
                BlockPos best = seatToMarker.get(seat);
                if (best == null || marker.distSqr(maidPos) < best.distSqr(maidPos)) {
                    seatToMarker.put(seat, marker);
                }
            }
        }
        BlockPos nearest = null;
        for (BlockPos marker : seatToMarker.values()) {
            if (nearest == null || marker.distSqr(maidPos) < nearest.distSqr(maidPos)) {
                nearest = marker;
            }
        }
        return nearest;
    }

    /**
     * 凳位空闲判据：精确复刻本体 MaidApproachCookBlockTask.isChairAvailable
     * （all.java:5744-5747）——凳位方块 AABB 内无其他女仆，或仅女仆自身（排除自己）。
     * 残余竞态（两女仆同 tick 同判）由本体走位阶段同判据仲裁。
     */
    private boolean isSeatFree(ServerLevel level, EntityMaid maid, BlockPos seat) {
        List<EntityMaid> occupants = level.getEntitiesOfClass(EntityMaid.class, new AABB(seat));
        return occupants.isEmpty() || occupants.size() == 1 && occupants.getFirst().equals(maid);
    }

    /** 订单摘要（日志用；无单时占位）。 */
    private String requestOf(EntityMaid maid) {
        return RequestManager.peek(maid, 0) instanceof CookRequest cr ? cr.id.toString() : "-";
    }

    /**
     * 非恒真（2026-10-20 R1 后 fork 实证修正）。两面依据：
     * <ul>
     *   <li>本体面：MaidCookingTask 运行中复验 false→stop 重搜（唯一调用点 all.java:5874；
     *       接受判据复刻站立路径 all.java:5719——外部 marker 的凳被拆即失效）。</li>
     *   <li>fork 面：营业 fork CookingTargetRedirector.resolveAssignedTarget（fork
     *       CookingTargetRedirector.java:31-56）以本方法作分配工位活性校验（fork
     *       CookingBridge.selectWorkPosForMaid 选位首步调本类 searchWorkBlock，CRAFTING
     *       营业单 targets[0]=凳邻位 marker）——恒真会让「凳在发布后被拆」的失效工位永远过检，
     *       mixin 不回退→营业单挂 ASSIGNED 硬超时。</li>
     * </ul>
     * 快速通过（0.2.3 F-197 收窄为仅坐标相等）：仅就坐路径天然命中（pos=搜索时的
     * maid.blockPosition()）；站立路径女仆落座在凳（chair）而非工位 marker（本体 accept
     * 落座写 CHAIR_POS，all.java:5766-5789），blockPosition==marker 对站立恒假、站立有效性
     * 实际由 hasSeatNeighbor 兜住——勿因「快速通道未覆盖站立」误删 hasSeatNeighbor（F-205）。
     * 0.2.3 已删除 isPassenger() 恒过分支——骑坐中校验外部 marker 不再恒过，车辆移位/工位
     * 失效即 false（fork CookingTargetRedirector 活性校验与 pos 重新耦合）。
     *
     * <p>maid=null 契约（0.2.4 审计 NPE 随批清偿）：fork CookingBridge.safeValidWorkBlock 以
     * maid=null 作选位活性校验，本方法判据依赖女仆坐标、null 无法判定——入口显式 false
     * （语义与原「NPE 被 fork 吞成 false」一致：CRAFTING 无本地兜底选位），不再依赖异常吞没。</p>
     */
    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        if (maid == null) {
            return false;
        }
        if (maid.blockPosition().equals(pos)) {
            return true;
        }
        return hasSeatNeighbor(level, pos);
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
            errorStall("crafting-recipe-missing:" + request.id + ":" + maid.getUUID(), "合成台请求配方缺失或类型不符：{} @ {} 女仆={}", request.id, pos, maid.getName().getString());
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
        // F-190/F-195：齐备预检——逐份配料做包内多重集合消耗模拟（每份配料从槽位级模拟计数中
        // 扣减，任一份扣减后为负即缺料），修复面包 3×小麦类重复配料按槽独立计数导致的
        // 预检误过→抽取失败→回包 20Hz churn；齐了才动抽取（防持续缺料时 20Hz 抽还 churn）
        var inv = maid.getAvailableInv(false);
        int invSlots = inv.getSlots();
        int[] consumed = new int[invSlots];
        for (int idx = 0; idx < w * h && idx < ingredients.size(); idx++) {
            Ingredient ingredient = ingredients.get(idx);
            if (ingredient.isEmpty()) {
                continue;
            }
            boolean available = false;
            for (int s = 0; s < invSlots; s++) {
                ItemStack stack = inv.getStackInSlot(s);
                if (stack.getCount() - consumed[s] > 0 && ingredient.test(stack)) {
                    consumed[s]++;
                    available = true;
                    break;
                }
            }
            if (!available) {
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
                warnStall("crafting-partial-missing:" + request.id + ":" + maid.getUUID(), "合成台配料中途缺料，已抽原料回包等待补货：{} @ {} 女仆={}", request.id, pos, maid.getName().getString());
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
            warnStall("crafting-matches-fail:" + request.id + ":" + maid.getUUID(), "合成台配方 matches 不符（构造布局与配方不匹配），原料已回包：{} @ {} 女仆={}", request.id, pos, maid.getName().getString());
            return;
        }

        ItemStack result = recipe.assemble(input, level.registryAccess());
        if (result.isEmpty()) {
            rollback(maid, extracted);
            errorStall("crafting-result-empty:" + request.id + ":" + maid.getUUID(), "合成台配方产物为空，原料已回包：{} @ {} 女仆={}", request.id, pos, maid.getName().getString());
            return;
        }
        DeliveryUtil.deliverToMaidInv(maid, result);

        // 容器返还（牛奶桶→空桶等）：1.21.1 Recipe.getRemainingItems(input) 无 Level 参数
        for (ItemStack remainder : recipe.getRemainingItems(input)) {
            if (!remainder.isEmpty()) {
                DeliveryUtil.deliverToMaidInv(maid, remainder);
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
            DeliveryUtil.deliverToMaidInv(maid, stack);
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
