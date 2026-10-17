package com.sagisawa.maidrestaurantcooking.cooktask.fidworkblock;

import com.fidtest.block.entity.AbstractCookingBlockEntity;
import com.fidtest.block.entity.ChoppingboardBlockEntity;
import com.fidtest.block.entity.FryingpanBlockEntity;
import com.fidtest.block.entity.JarBlockEntity;
import com.fidtest.block.entity.MeatmincerBlockEntity;
import com.fidtest.block.entity.MortarBlockEntity;
import com.fidtest.block.entity.SqueezingmachineBlockEntity;
import com.fidtest.block.entity.TeapotBlockEntity;
import com.fidtest.block.entity.WokBlockEntity;
import com.fidtest.recipe.CookingRecipe;
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
import com.sagisawa.maidrestaurantcooking.utils.RecipeDataFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * M9 烟火凡人心「灶台方块组」（fidworkblock:cooking，12 设备 397 条，本轮接 11 台 373 条）的女仆烹饪任务。
 * 单类型按配方 {@code appliance} 字段分流——本体 taskMap 按 RecipeType 键控，一个任务类吃全部设备。
 *
 * <p>驱动表（2026-10-18 全量 javap 实证 + R1 审查修复，见 docs/验证记录/M9-fidworkblock.md）：
 * <ul>
 *   <li>炉灶自走系 pressurecooker/dryer/pot：WorldlyContainer 投料→serverTick 自转化
 *       （minHeat 全 0，无需热源）→输出槽取货。removeItem 仅在槽清空时置脏（字节码实证），
 *       取货侧对称补 setChanged（F-173）。</li>
 *   <li>自走系 squeezingmachine：addInput→startProcessing→等 isWorking 完成→collectOutput。</li>
 *   <li>密封系 jar/teapot/meatmincer：投料→checkRecipe→seal()（内部自启，失败自动解封）
 *       →等完成→collectOutput。输入区=[0,outputSlot)（F-166 口径）。</li>
 *   <li>翻炒系 wok：配料计数配齐才翻炒（F-161：杜绝半成品收锅）→stirFry(null)×requiredStirs
 *       （player 参数字节码级确认未消费）→收锅前配方自校验（finishCooking 无成功标志，
 *       未命中不扣单退料响出）。</li>
 *   <li>工具系 choppingboard：addItem→抽厨刀（flavor_immersed_daily:kitchenknife）→
 *       processWithTool(knife, null)（hurtAndBreak 的 ServerPlayer 原版 @Nullable 判空，
 *       null 安全；doProcess 对传入刀原地 hurtAndBreak 损耗）→产物落地拾取→刀回包。</li>
 *   <li>燃油系 fryingpan：addOil+addIngredient→startFrying（要求 isOnDryer 叠放+油≥料）→
 *       等→从 getInventory() 输出槽直接提取（collectOutput(Player) 真消费玩家，绕开）。</li>
 *   <li>即时系 mortar：addItem→processStored(null)（player 仅进 playSound，null 安全）→
 *       产物落地拾取。</li>
 * </ul>
 * <p>R1 审查修复（F-161~F-173）：wok/密封/炉灶三支统一「贪心对齐+计数式核对」——需求份数=
 * 配料在配方中的重复次数（jar dup=6/teapot dup=3/pot dup=3/pressurecooker dup=6/wok dup=5，
 * 全量 JSON 统计实证重复配料真实存在）；supply 中未被任何配料接受的项=F-139 异物整体退料；
 * isValidWorkBlock 校验工位与订单 appliance 同型；searchWorkBlock 的确定性误配（未接设备/
 * 炸锅未叠放）窗口化 WARN；窗口化日志键控（消息码:工位，仿 F-160 过期清理）防跨消息/跨工位压制。
 *
 * <p>状态纪律：任务实例单例跨 maid 共享——加工状态全部在 BE；仅日志窗口表键控
 * （ConcurrentHashMap）。刀/油物品栈运行期惰性解析（构造期注册表未填充）。
 * 蒸笼 steambox 不接（边界）：注水在 Block.useItemOn 层 + 叠层几何，如实进 docs/边界清单.md。
 */
public class FidworkblockCookTask implements ICookTask {

    private static final String MOD_ID = "fidworkblock";
    private static final ResourceLocation RECIPE_TYPE_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "cooking");
    private static final ResourceLocation KNIFE_ID = ResourceLocation.fromNamespaceAndPath("flavor_immersed_daily", "kitchenknife");
    private static final ResourceLocation OIL_ID = ResourceLocation.fromNamespaceAndPath("flavor_immersed_daily", "cookingoil");

    /** 本轮驱动核对通过的设备（= 配方 appliance 字段值，同方块注册路径）；steambox 缺席=边界。 */
    private static final Set<String> DRIVEN_APPLIANCES = Set.of(
            "squeezingmachine", "jar", "teapot", "meatmincer",
            "pressurecooker", "dryer", "pot",
            "wok", "choppingboard", "fryingpan", "mortar");

    /** F-169/F-170：窗口化日志键（消息码:工位短串 → 窗口截止时刻），30s 窗去重；过期清理仿 F-160。 */
    private final ConcurrentHashMap<String, Long> logWindows = new ConcurrentHashMap<>();
    private static final long LOG_WINDOW_MS = 30_000L;
    private static final int LOG_WINDOW_MAX = 256;

    /** 运行期解析物品栈（注册表未注册/未装载 → AIR）。 */
    private static ItemStack itemStackOf(ResourceLocation id) {
        var item = BuiltInRegistries.ITEM.get(id);
        return item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    @Override
    public String getUID() {
        return "FidworkblockCookTask";
    }

    @Override
    public ItemStack getIcon() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(MOD_ID, "squeezingmachine"));
        return block.defaultBlockState().isAir() ? ItemStack.EMPTY : new ItemStack(block);
    }

    /** 注册表键查惰性解析（farmcharm 先例；构造期禁调——RegisterEvent 在 CONSTRUCT 之后）。 */
    @Override
    public RecipeType<?> getType() {
        RecipeType<?> type = BuiltInRegistries.RECIPE_TYPE.get(RECIPE_TYPE_ID);
        if (type == null) {
            throw new IllegalStateException("fidworkblock:cooking 配方类型未注册（fidworkblock 未装载或版本不符）");
        }
        return type;
    }

    @Override
    public List<StackPredicate> getIngredients(RecipeHolder<? extends net.minecraft.world.item.crafting.Recipe<?>> recipeHolder, Level level) {
        if (!(recipeHolder.value() instanceof CookingRecipe recipe)) {
            return List.of();
        }
        return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).map(StackPredicate::new).toList();
    }

    // ═══════════ 窗口化日志（F-169/F-170）═══════════

    /** 30s 窗内同键只响一次；键=消息码:工位（防跨消息/跨工位压制），过期条目顺手清理。 */
    private boolean shouldLog(String code, BlockPos pos) {
        String key = code + ":" + (pos == null ? "-" : pos.toShortString());
        long now = System.currentTimeMillis();
        Long until = logWindows.get(key);
        if (until != null && now < until) {
            return false;
        }
        if (logWindows.size() > LOG_WINDOW_MAX) {
            logWindows.entrySet().removeIf(e -> e.getValue() < now);
        }
        logWindows.put(key, now + LOG_WINDOW_MS);
        return true;
    }

    private void warnStall(String code, BlockPos pos, String format, Object... args) {
        if (shouldLog(code, pos)) {
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] " + format, args);
        }
    }

    /** ERROR 级窗口化（F-170：配方缺失/未知设备等每 tick 路径，20Hz 刷屏防线）。 */
    private void errorStall(String code, BlockPos pos, String format, Object... args) {
        if (shouldLog(code, pos)) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] " + format, args);
        }
    }

    // ═══════════ 订单与配方 ═══════════

    /** 当前订单 → 配方（peek(maid,0) 为本体 OvenCookTask 先例；peek 可能无单，null 安全）。 */
    @Nullable
    private CookRequest peekCookRequest(EntityMaid maid) {
        return RequestManager.peek(maid, 0) instanceof CookRequest cook ? cook : null;
    }

    @Nullable
    private CookingRecipe resolveRecipe(ServerLevel level, CookRequest request, BlockPos pos) {
        RecipeHolder<?> holder = level.getRecipeManager().byKey(request.id).orElse(null);
        if (holder == null || !RECIPE_TYPE_ID.equals(BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()))
                || !(holder.value() instanceof CookingRecipe recipe)) {
            errorStall("recipe-missing:" + request.id, pos,
                    "fidworkblock 请求配方缺失或类型不符：{}", request.id);
            return null;
        }
        return recipe;
    }

    // ═══════════ 计数式配料核对（F-161/F-163/F-164/F-167 统一形态）═══════════

    /** 非空配料需求列表。 */
    private List<Ingredient> requiredIngredients(CookingRecipe recipe) {
        return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).toList();
    }

    /**
     * 贪心对齐：需求份数 = 配料在配方中的重复次数（F-167 计数式，防「存在即跳过」漏配）。
     * 对 need 逐份找 supply 未消费项，命中标记 used[i]。返回未满足的需求份数（按配方顺序）。
     */
    private List<Ingredient> findMissing(List<ItemStack> supply, boolean[] used, List<Ingredient> need) {
        List<Ingredient> missing = new ArrayList<>();
        for (Ingredient ing : need) {
            boolean found = false;
            for (int i = 0; i < supply.size(); i++) {
                if (!used[i] && ing.test(supply.get(i))) {
                    used[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                missing.add(ing);
            }
        }
        return missing;
    }

    /** supply 中存在未被任何需求接受的项（F-139 异物判据）。 */
    private boolean hasForeignItem(List<ItemStack> supply, boolean[] used) {
        for (int i = 0; i < supply.size(); i++) {
            if (!used[i] && !supply.get(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ═══════════ 寻址 ═══════════

    @Nullable
    @Override
    public BlockPos searchWorkBlock(ServerLevel level, EntityMaid maid, int horizontalSearchRange, int verticalSearchRange) {
        CookRequest request = peekCookRequest(maid);
        if (request == null) {
            return null;
        }
        BlockPos probe = maid.blockPosition();
        CookingRecipe recipe = resolveRecipe(level, request, probe);
        if (recipe == null) {
            return null; // resolveRecipe 已窗口化 ERROR（F-170）
        }
        String appliance = recipe.getAppliance();
        if (!DRIVEN_APPLIANCES.contains(appliance)) {
            // F-165：确定性误配必须可观测（steambox 边界/上游新设备）
            warnStall("appliance-undriven", probe, "fidworkblock 设备 {} 未接入（边界），订单挂起：{} @ {}",
                    appliance, request.id, probe);
            return null;
        }
        ResourceLocation target = ResourceLocation.fromNamespaceAndPath(MOD_ID, appliance);
        boolean frying = appliance.equals("fryingpan");
        BlockPos center = maid.getBrainSearchPos();
        Predicate<BlockPos> matcher = pos -> {
            if (!target.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()))
                    || BlockUsageManager.getUserCount(pos) > 0) {
                return false;
            }
            // 空闲过滤（防抢他人进行中设备）；受理中工位由 isValidWorkBlock 维持，不受此限
            return isIdleForSearch(level.getBlockEntity(pos), appliance, frying);
        };
        List<BlockPos> found = SearchUtils.search(center, horizontalSearchRange, verticalSearchRange, matcher);
        if (found.isEmpty() && frying) {
            // F-165/F-128 模式：范围内有炸锅方块但不叠放（确定性误配）→ 响出，与「无设备」静默区分
            for (BlockPos p : BlockPos.withinManhattan(center, horizontalSearchRange, verticalSearchRange, horizontalSearchRange)) {
                if (target.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()))
                        && level.getBlockEntity(p) instanceof FryingpanBlockEntity fp && !fp.isOnDryer()) {
                    warnStall("fryingpan-not-on-dryer", p, "fidworkblock 范围内炸锅未叠放在变温烤箱上，订单挂起：{} @ {}",
                            request.id, p);
                    break;
                }
            }
        }
        return found.isEmpty() ? null
                : found.stream().min(Comparator.comparingDouble(p -> p.distSqr(maid.blockPosition()))).get();
    }

    /** 搜索期空闲判定：加工中/已密封/有产出/台面有料（fryingpan，F-163）的设备不接新单。 */
    private boolean isIdleForSearch(@Nullable net.minecraft.world.level.block.entity.BlockEntity be, String appliance, boolean frying) {
        if (be == null) {
            return false;
        }
        return switch (appliance) {
            case "squeezingmachine" -> be instanceof SqueezingmachineBlockEntity m
                    && !m.isWorking() && !m.hasOutput();
            case "jar" -> be instanceof JarBlockEntity j
                    && !j.isSealed() && !j.isWorking() && !j.hasOutput();
            case "teapot" -> be instanceof TeapotBlockEntity t
                    && !t.isSealed() && !t.isWorking() && !t.hasOutput();
            case "meatmincer" -> be instanceof MeatmincerBlockEntity m
                    && !m.isSealed() && !m.isWorking() && !m.hasOutput();
            case "pressurecooker", "dryer", "pot" -> be instanceof AbstractCookingBlockEntity c
                    && c.getCookingProgress() <= 0
                    && c.getItem(c.getApplianceType().getOutputSlot()).isEmpty();
            case "wok" -> be instanceof WokBlockEntity w
                    && w.getStirCount() <= 0 && w.getItemCount() <= 0;
            case "choppingboard" -> be instanceof ChoppingboardBlockEntity cb && cb.isEmpty();
            case "fryingpan" -> frying && be instanceof FryingpanBlockEntity fp
                    && !fp.isWorking() && !fp.hasOutput() && fp.isOnDryer() && !fp.hasIngredient();
            case "mortar" -> be instanceof MortarBlockEntity mo && mo.isEmpty();
            default -> false;
        };
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        ResourceLocation blockKey = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        if (!MOD_ID.equals(blockKey.getNamespace()) || !DRIVEN_APPLIANCES.contains(blockKey.getPath())) {
            return false;
        }
        // F-162：工位必须与本单 appliance 同型（换单/工位被换后防静默空转）
        CookRequest request = peekCookRequest(maid);
        if (request == null) {
            return true; // 无单探测：维持宽容
        }
        RecipeHolder<?> holder = level.getRecipeManager().byKey(request.id).orElse(null);
        if (holder == null || !(holder.value() instanceof CookingRecipe recipe)) {
            return false; // 配方失效：判失效让本体重新 search（search 侧同失败 → 窗口化 WARN 挂起）
        }
        return blockKey.getPath().equals(recipe.getAppliance());
    }

    @Override
    public List<ItemStack> getCurrentInput(Level level, BlockPos pos, EntityMaid maid) {
        List<ItemStack> ans = new ArrayList<>();
        var be = level.getBlockEntity(pos);
        if (be instanceof AbstractCookingBlockEntity cooking) {
            IItemHandler handler = cooking.getItemHandler();
            for (int i = 0; i < handler.getSlots(); i++) {
                if (!handler.getStackInSlot(i).isEmpty()) {
                    ans.add(handler.getStackInSlot(i).copy());
                }
            }
        } else if (be instanceof JarBlockEntity j) {
            addNonEmpty(ans, j.getInventory());
        } else if (be instanceof TeapotBlockEntity t) {
            addNonEmpty(ans, t.getInventory());
        } else if (be instanceof MeatmincerBlockEntity m) {
            addNonEmpty(ans, m.getInventory());
        } else if (be instanceof SqueezingmachineBlockEntity sm) {
            addNonEmpty(ans, sm.getInventory());
        } else if (be instanceof WokBlockEntity w) {
            for (ItemStack stack : w.getStoredItems()) {
                if (!stack.isEmpty()) {
                    ans.add(stack.copy());
                }
            }
        } else if (be instanceof ChoppingboardBlockEntity cb && !cb.getStoredItem().isEmpty()) {
            ans.add(cb.getStoredItem().copy());
        } else if (be instanceof MortarBlockEntity mo && !mo.getStoredItem().isEmpty()) {
            ans.add(mo.getStoredItem().copy());
        } else if (be instanceof FryingpanBlockEntity fp) {
            addNonEmpty(ans, fp.getInventory());
        }
        return ans;
    }

    private void addNonEmpty(List<ItemStack> ans, ItemStackHandler inv) {
        for (int i = 0; i < inv.getSlots(); i++) {
            if (!inv.getStackInSlot(i).isEmpty()) {
                ans.add(inv.getStackInSlot(i).copy());
            }
        }
    }

    @Override
    public List<StackPredicate> getKitchenWares() {
        return List.of();
    }

    // ═══════════ cookTick 分流 ═══════════

    @Override
    public void cookTick(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request) {
        CookingRecipe recipe = resolveRecipe(level, request, pos);
        if (recipe == null) {
            return;
        }
        String appliance = recipe.getAppliance();
        if (!DRIVEN_APPLIANCES.contains(appliance)) {
            // 不可达（search 不寻址 + isValidWorkBlock 同型校验）；防御性窗口化 ERROR（F-170）
            errorStall("appliance-undriven-tick", pos, "fidworkblock 设备 {} 未接入（边界），订单挂起：{} @ {}",
                    appliance, request.id, pos);
            return;
        }
        var be = level.getBlockEntity(pos);
        if (be == null) {
            return;
        }
        switch (appliance) {
            case "squeezingmachine" -> {
                if (be instanceof SqueezingmachineBlockEntity m) {
                    tickSqueezingmachine(maid, pos, request, recipe, m);
                } else {
                    warnBlockMismatch(pos, "squeezingmachine", request, be); // F-162
                }
            }
            case "jar" -> {
                if (be instanceof JarBlockEntity j) {
                    tickSealed(maid, pos, request, recipe, "jar", j.getInventory(),
                            JarBlockEntity.OUTPUT_SLOT,
                            j::isSealed, j::isWorking, j::isProcessingDone, j::checkRecipe, j::seal,
                            j::addItemToNextSlot, j::collectOutput, j::setChanged);
                } else {
                    warnBlockMismatch(pos, "jar", request, be); // F-162
                }
            }
            case "teapot" -> {
                if (be instanceof TeapotBlockEntity t) {
                    tickSealed(maid, pos, request, recipe, "teapot", t.getInventory(),
                            TeapotBlockEntity.OUTPUT_SLOT,
                            t::isSealed, t::isWorking, t::isProcessingDone, t::checkRecipe, t::seal,
                            t::addItemToNextSlot, t::collectOutput, t::setChanged);
                } else {
                    warnBlockMismatch(pos, "teapot", request, be); // F-162
                }
            }
            case "meatmincer" -> {
                if (be instanceof MeatmincerBlockEntity m) {
                    tickSealed(maid, pos, request, recipe, "meatmincer", m.getInventory(),
                            MeatmincerBlockEntity.OUTPUT_SLOT,
                            m::isSealed, m::isWorking, m::isProcessingDone, m::checkRecipe, m::seal,
                            m::addItemToSlot, m::collectOutput, m::setChanged);
                } else {
                    warnBlockMismatch(pos, "meatmincer", request, be); // F-162
                }
            }
            case "pressurecooker", "dryer", "pot" -> {
                if (be instanceof AbstractCookingBlockEntity c) {
                    tickStoveAuto(maid, pos, request, recipe, c);
                } else {
                    warnBlockMismatch(pos, appliance, request, be); // F-162
                }
            }
            case "wok" -> {
                if (be instanceof WokBlockEntity w) {
                    tickWok(level, maid, pos, request, recipe, w);
                } else {
                    warnBlockMismatch(pos, "wok", request, be); // F-162
                }
            }
            case "choppingboard" -> {
                if (be instanceof ChoppingboardBlockEntity cb) {
                    tickChoppingboard(maid, pos, request, recipe, cb);
                } else {
                    warnBlockMismatch(pos, "choppingboard", request, be); // F-162
                }
            }
            case "fryingpan" -> {
                if (be instanceof FryingpanBlockEntity fp) {
                    tickFryingpan(maid, pos, request, recipe, fp);
                } else {
                    warnBlockMismatch(pos, "fryingpan", request, be); // F-162
                }
            }
            case "mortar" -> {
                if (be instanceof MortarBlockEntity mo) {
                    tickMortar(maid, pos, request, recipe, mo);
                } else {
                    warnBlockMismatch(pos, "mortar", request, be); // F-162
                }
            }
            default -> errorStall("appliance-unknown", pos, "fidworkblock 未知设备 appliance={}：{} @ {}",
                    appliance, request.id, pos);
        }
    }

    /** F-162：appliance 与工位 BE 类型不匹配（换单/工位被换）——窗口化 WARN，防静默空转。 */
    private void warnBlockMismatch(BlockPos pos, String appliance, CookRequest request, Object actual) {
        warnStall("block-mismatch:" + appliance, pos, "fidworkblock 工位类型与订单不符（期望 {}，实际 {}）：{} @ {}",
                appliance, actual.getClass().getSimpleName(), request.id, pos);
    }

    // ═══ 压榨机（自走单槽）═══

    private void tickSqueezingmachine(EntityMaid maid, BlockPos pos, CookRequest request,
                                      CookingRecipe recipe, SqueezingmachineBlockEntity m) {
        if (m.hasOutput()) {
            ItemStack out = m.collectOutput();
            if (!out.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, out);
                request.remain--;
                maid.swing(InteractionHand.OFF_HAND);
            }
            return;
        }
        if (m.isWorking()) {
            return; // 加工中，等待
        }
        if (m.hasInput()) {
            // F-139：台面残留与本单配料不符 → 退料回包（F-163 对称面）
            ItemStack onTable = m.getInputItem();
            if (requiredIngredients(recipe).stream().noneMatch(i -> i.test(onTable))) {
                ItemStack back = m.removeInput();
                if (!back.isEmpty()) {
                    ItemUtils.getItemToLivingEntity(maid, back);
                }
                warnStall("residue:squeezingmachine", pos, "fidworkblock 压榨机台面存在与本单不符的料（{}），已退料回包：{} @ {}",
                        onTable.getItem(), request.id, pos);
                return;
            }
        } else {
            ItemStack take = extractSingleIngredient(maid, recipe, pos, request);
            if (take == null || take.isEmpty()) {
                return; // 缺料静默等待（null=非单料配方已 ERROR）
            }
            ItemStack remainder = m.addInput(take);
            if (!remainder.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, remainder);
                warnStall("insert-reject:squeezingmachine", pos, "fidworkblock 压榨机投料被拒（{}），原料已回包：{} @ {}",
                        remainder.getItem(), request.id, pos);
                return;
            }
        }
        if (!m.startProcessing()) {
            // 投料与配方不符（不该发生：按配方抽取+台面校验）；退料回包 + 响出
            ItemStack back = m.removeInput();
            if (!back.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, back);
            }
            warnStall("start-fail:squeezingmachine", pos, "fidworkblock 压榨机 startProcessing 失败，已退料回包：{} @ {}", request.id, pos);
        }
    }

    // ═══ 密封系（jar/teapot/meatmincer 同构：输入区=[0,outputSlot)，F-166 口径）═══

    private void tickSealed(EntityMaid maid, BlockPos pos, CookRequest request, CookingRecipe recipe, String name,
                            ItemStackHandler inv, int outputSlot,
                            java.util.function.BooleanSupplier isSealed, java.util.function.BooleanSupplier isWorking,
                            java.util.function.BooleanSupplier isProcessingDone, java.util.function.BooleanSupplier checkRecipe,
                            Runnable seal, java.util.function.UnaryOperator<ItemStack> addItem,
                            java.util.function.Supplier<ItemStack> collectOutput, Runnable setChanged) {
        if (isProcessingDone.getAsBoolean() && !inv.getStackInSlot(outputSlot).isEmpty()) {
            ItemStack out = collectOutput.get();
            if (!out.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, out);
                request.remain--;
                maid.swing(InteractionHand.OFF_HAND);
            }
            return;
        }
        if (isSealed.getAsBoolean() || isWorking.getAsBoolean()) {
            return; // 密封加工中，等待
        }

        // 计数式核对（F-167）+ F-139 异物整体退料：supply=输入区全部槽
        List<ItemStack> supply = new ArrayList<>();
        for (int i = 0; i < outputSlot; i++) {
            if (!inv.getStackInSlot(i).isEmpty()) {
                supply.add(inv.getStackInSlot(i));
            }
        }
        boolean[] used = new boolean[supply.size()];
        List<Ingredient> need = requiredIngredients(recipe);
        List<Ingredient> missing = findMissing(supply, used, need);
        if (hasForeignItem(supply, used)) {
            rollbackSealed(maid, inv, outputSlot);
            warnStall("residue:" + name, pos, "fidworkblock {} 台面存在与本单不符的料，已退料回包：{} @ {}", name, request.id, pos);
            return;
        }
        if (!missing.isEmpty()) {
            for (Ingredient ing : missing) {
                ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                        StackPredicate.of(ing), true);
                if (take.isEmpty()) {
                    return; // 缺料静默等待（已投部分留台面，下 tick 续投——计数式核对支持续投）
                }
                boolean putIn = false;
                for (int i = 0; i < outputSlot && !putIn; i++) {
                    if (inv.getStackInSlot(i).isEmpty()) {
                        inv.setStackInSlot(i, take);
                        putIn = true;
                    }
                }
                if (!putIn) {
                    ItemUtils.getItemToLivingEntity(maid, take);
                    warnStall("insert-reject:" + name, pos, "fidworkblock {} 输入槽满，原料已回包：{} @ {}", name, request.id, pos);
                    return;
                }
            }
            setChanged.run();
        }
        if (supply.isEmpty() && missing.isEmpty()) {
            return; // 无配料（异常配方），静默
        }
        if (checkRecipe.getAsBoolean()) {
            seal.run(); // 内部自启；失败由设备自行解封（seal 语义，javap 实证）
            setChanged.run();
        } else {
            // 按配方投满但 checkRecipe 仍 false：全部退料回包 + 响出
            rollbackSealed(maid, inv, outputSlot);
            warnStall("check-fail:" + name, pos, "fidworkblock {} 按配方投料后 checkRecipe 失败，已退料回包：{} @ {}", name, request.id, pos);
        }
    }

    /** 密封系退料回包：输入区 [0,outputSlot) 逐槽清空交还女仆（F-166：不触碰输出区）。 */
    private void rollbackSealed(EntityMaid maid, ItemStackHandler inv, int outputSlot) {
        for (int i = 0; i < outputSlot; i++) {
            ItemStack slotStack = inv.getStackInSlot(i);
            if (!slotStack.isEmpty()) {
                inv.setStackInSlot(i, ItemStack.EMPTY);
                ItemUtils.getItemToLivingEntity(maid, slotStack);
            }
        }
    }

    // ═══ 炉灶自走系（pressurecooker/dryer/pot：WorldlyContainer）═══

    private void tickStoveAuto(EntityMaid maid, BlockPos pos, CookRequest request,
                               CookingRecipe recipe, AbstractCookingBlockEntity c) {
        int outputSlot = c.getApplianceType().getOutputSlot();
        ItemStack expect = recipe.getResult();
        ItemStack output = c.getItem(outputSlot);
        if (!output.isEmpty() && output.getItem() != expect.getItem()) {
            warnStall("output-foreign:stove", pos, "fidworkblock 炉灶系输出槽为非本单产物（{}），停摆待处理：{} @ {}",
                    output.getItem(), request.id, pos);
            return;
        }
        if (!output.isEmpty()) {
            ItemStack taken = c.removeItem(outputSlot, output.getCount());
            if (!taken.isEmpty()) {
                // F-173：removeItem 仅在槽清空时置脏（字节码实证），取货侧对称补 setChanged
                c.setChanged();
                ItemUtils.getItemToLivingEntity(maid, taken);
                request.remain--;
                maid.swing(InteractionHand.OFF_HAND);
            }
            return;
        }

        // F-164：输入区残留扫描（计数式核对+异物整体退料，与密封系同构）
        int inputStart = c.getApplianceType().getInputStartSlot();
        int inputSlots = c.getApplianceType().getInputSlots();
        List<ItemStack> supply = new ArrayList<>();
        for (int i = 0; i < inputSlots; i++) {
            if (!c.getItem(inputStart + i).isEmpty()) {
                supply.add(c.getItem(inputStart + i));
            }
        }
        boolean[] used = new boolean[supply.size()];
        List<Ingredient> need = requiredIngredients(recipe);
        List<Ingredient> missing = findMissing(supply, used, need);
        if (hasForeignItem(supply, used)) {
            for (int i = 0; i < inputSlots; i++) {
                ItemStack slotStack = c.getItem(inputStart + i);
                if (!slotStack.isEmpty()) {
                    c.setItem(inputStart + i, ItemStack.EMPTY);
                    ItemUtils.getItemToLivingEntity(maid, slotStack);
                }
            }
            c.setChanged();
            warnStall("residue:stove", pos, "fidworkblock 炉灶系输入区存在与本单不符的料，已退料回包：{} @ {}", request.id, pos);
            return;
        }
        if (supply.isEmpty() && missing.size() == need.size()) {
            // 空设备：正常补投路径继续
        }
        boolean supplied = false;
        for (Ingredient ing : missing) {
            ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                    StackPredicate.of(ing), true);
            if (take.isEmpty()) {
                if (supplied) {
                    // 部分已投：本单中途缺料，已投部分留位下 tick 续投（计数式核对支持）
                    c.setChanged();
                }
                return; // 缺料静默等待
            }
            boolean putIn = false;
            for (int i = 0; i < inputSlots; i++) {
                int slot = inputStart + i;
                if (c.getItem(slot).isEmpty()) {
                    c.setItem(slot, take);
                    putIn = true;
                    supplied = true;
                    break;
                }
            }
            if (!putIn) {
                ItemUtils.getItemToLivingEntity(maid, take);
                warnStall("insert-reject:stove", pos, "fidworkblock 炉灶系输入槽满，原料已回包：{} @ {}", request.id, pos);
                return;
            }
        }
        if (supplied) {
            c.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
        }
        // serverTick 自转化（minHeat=0 无需热源）；产物由取货分支完成
    }

    // ═══ 翻炒系（wok：F-161 计数配齐才翻炒 + 收锅前自校验）═══

    private void tickWok(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request,
                         CookingRecipe recipe, WokBlockEntity w) {
        List<Ingredient> need = requiredIngredients(recipe);
        List<ItemStack> stored = new ArrayList<>(w.getStoredItems());
        boolean[] used = new boolean[stored.size()];
        List<Ingredient> missing = findMissing(stored, used, need);

        if (!missing.isEmpty()) {
            // 补投（F-161：未配齐不翻炒，杜绝半成品收锅；已投部分留锅，计数式核对支持续投）
            for (Ingredient ing : missing) {
                ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                        StackPredicate.of(ing), true);
                if (take.isEmpty()) {
                    return; // 缺料静默等待
                }
                if (!w.canAddItem()) {
                    ItemUtils.getItemToLivingEntity(maid, take);
                    warnStall("insert-reject:wok", pos, "fidworkblock 炒锅投料被拒（满/非配方料），原料已回包：{} @ {}", request.id, pos);
                    return;
                }
                w.addItem(take);
            }
            w.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
            return;
        }
        if (hasForeignItem(stored, used)) {
            // F-139：锅内异物（本单配料全部满足后仍有余料）→ 不翻炒收锅，响出待处理
            warnStall("residue:wok", pos, "fidworkblock 炒锅内存在与本单不符的料，收锅中止：{} @ {}", request.id, pos);
            return;
        }
        if (w.getStirCount() < w.getRequiredStirs()) {
            // 逐 tick 翻炒一次；player 参数未消费（字节码实证），传 null 安全
            w.stirFry(null);
            w.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
            return;
        }
        // 收锅前配方自校验（F-161b：finishCooking 无成功标志——javap void；命中即配方 result，
        // 未命中走程序化菜=错菜。此处投料按配方+异物已排除，双保险校验）
        List<ItemStack> finalCheck = new ArrayList<>(w.getStoredItems());
        boolean[] checkUsed = new boolean[finalCheck.size()];
        List<Ingredient> stillMissing = findMissing(finalCheck, checkUsed, need);
        if (!stillMissing.isEmpty() || hasForeignItem(finalCheck, checkUsed)) {
            warnStall("finish-check-fail:wok", pos, "fidworkblock 炒锅收锅前校验失败（存料与本单配方不符），收锅中止：{} @ {}", request.id, pos);
            return;
        }
        // 配方命中用配方 result，产物 ItemEntity 落地由拾取 AI 收集
        w.finishCooking(level, pos);
        w.setChanged();
        request.remain--;
        maid.swing(InteractionHand.OFF_HAND);
    }

    // ═══ 工具系（choppingboard）═══

    private void tickChoppingboard(EntityMaid maid, BlockPos pos, CookRequest request,
                                   CookingRecipe recipe, ChoppingboardBlockEntity cb) {
        if (cb.isEmpty()) {
            ItemStack take = extractSingleIngredient(maid, recipe, pos, request);
            if (take == null || take.isEmpty()) {
                return; // 缺料静默等待（null=非单料配方已 ERROR）
            }
            ItemStack remainder = cb.addItem(take);
            if (!remainder.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, remainder);
            }
            cb.setChanged();
            return;
        }
        // 台面有料：与本单首配料不符则退料
        ItemStack onTable = cb.getStoredItem();
        if (requiredIngredients(recipe).stream().noneMatch(i -> i.test(onTable))) {
            ItemStack back = cb.removeItem();
            if (!back.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, back);
            }
            warnStall("residue:choppingboard", pos, "fidworkblock 砧板台面存在与本单不符的料（{}），已退料回包：{} @ {}",
                    onTable.getItem(), request.id, pos);
            return;
        }
        // 抽厨刀（判定 id=flavor_immersed_daily:kitchenknife，javap 实证自 private isKitchenKnife）
        ItemStack knife = itemStackOf(KNIFE_ID);
        if (knife.isEmpty()) {
            warnStall("knife-unregistered", pos, "fidworkblock 需要厨刀但 flavor_immersed_daily:kitchenknife 未注册，订单挂起：{} @ {}", request.id, pos);
            return;
        }
        ItemStack held = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                StackPredicate.of(s -> s.is(knife.getItem())), true);
        if (held.isEmpty()) {
            warnStall("no-knife", pos, "fidworkblock 砧板等待厨刀但女仆无厨刀，订单挂起：{} @ {}", request.id, pos);
            return;
        }
        boolean done = cb.processWithTool(held, null); // ServerPlayer @Nullable 原版判空，null 安全
        ItemUtils.getItemToLivingEntity(maid, held); // 刀回包（耐久损耗已在 BE 内部原地完成——doProcess hurtAndBreak 字节码实证）
        if (done) {
            cb.setChanged();
            request.remain--; // 产物 ItemEntity 落地，拾取 AI 收集（FD 砧板同款）
            maid.swing(InteractionHand.OFF_HAND);
        } else {
            warnStall("process-fail:choppingboard", pos, "fidworkblock 砧板 processWithTool 失败（料与本单不符）：{} @ {}", request.id, pos);
        }
    }

    // ═══ 燃油系（fryingpan：需叠放在 dryer 上，油≥料）═══

    private void tickFryingpan(EntityMaid maid, BlockPos pos, CookRequest request,
                               CookingRecipe recipe, FryingpanBlockEntity fp) {
        if (fp.hasOutput()) {
            ItemStack out = fp.getInventory().extractItem(FryingpanBlockEntity.OUTPUT_SLOT,
                    fp.getOutputItem().getCount(), false);
            if (!out.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, out);
                request.remain--;
                maid.swing(InteractionHand.OFF_HAND);
            }
            return;
        }
        if (fp.isWorking()) {
            return; // 炸制中
        }
        if (!fp.isOnDryer()) {
            warnStall("not-on-dryer", pos, "fidworkblock 炸锅未叠放在变温烤箱上，订单挂起：{} @ {}", request.id, pos);
            return;
        }
        // F-163：台面残留料判定（对称面——压榨机/密封系/砧板/研钵均有此守卫）
        if (fp.hasIngredient()) {
            ItemStack onTable = fp.getIngredientItem();
            if (requiredIngredients(recipe).stream().noneMatch(i -> i.test(onTable))) {
                ItemStack back = fp.removeIngredient();
                if (!back.isEmpty()) {
                    ItemUtils.getItemToLivingEntity(maid, back);
                }
                warnStall("residue:fryingpan", pos, "fidworkblock 炸锅台面存在与本单不符的料（{}），已退料回包：{} @ {}",
                        onTable.getItem(), request.id, pos);
                return;
            }
        }
        if (!fp.hasOil()) {
            ItemStack oil = itemStackOf(OIL_ID);
            if (oil.isEmpty()) {
                warnStall("oil-unregistered", pos, "fidworkblock 炸锅需食用油但 {} 未注册，订单挂起：{} @ {}", OIL_ID, request.id, pos);
                return;
            }
            ItemStack held = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                    StackPredicate.of(s -> s.is(oil.getItem())), true);
            if (held.isEmpty()) {
                warnStall("no-oil", pos, "fidworkblock 炸锅等待食用油但女仆无油，订单挂起：{} @ {}", request.id, pos);
                return;
            }
            ItemStack remainder = fp.addOil(held);
            if (!remainder.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, remainder);
            }
            fp.setChanged();
        }
        if (!fp.hasIngredient()) {
            ItemStack take = extractSingleIngredient(maid, recipe, pos, request);
            if (take == null || take.isEmpty()) {
                return; // 缺料静默等待（null=非单料配方已 ERROR）
            }
            ItemStack remainder = fp.addIngredient(take);
            if (!remainder.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, remainder);
            }
            fp.setChanged();
        }
        if (fp.hasOil() && fp.hasIngredient() && fp.startFrying()) {
            fp.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
        }
        // startFrying false = 油量不足等条件未齐：料油留位，下 tick 重试（条件达成即启动）
    }

    // ═══ 即时系（mortar）═══

    private void tickMortar(EntityMaid maid, BlockPos pos, CookRequest request,
                            CookingRecipe recipe, MortarBlockEntity mo) {
        if (mo.isEmpty()) {
            ItemStack take = extractSingleIngredient(maid, recipe, pos, request);
            if (take == null || take.isEmpty()) {
                return; // 缺料静默等待（null=非单料配方已 ERROR）
            }
            ItemStack remainder = mo.addItem(take);
            if (!remainder.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, remainder);
            }
            mo.setChanged();
            return;
        }
        boolean done = mo.processStored(null); // player 仅进 playSound（null 安全，字节码实证）
        if (done) {
            mo.setChanged();
            request.remain--; // 产物 ItemEntity 落地，拾取 AI 收集
            maid.swing(InteractionHand.OFF_HAND);
        } else {
            ItemStack back = mo.removeItem();
            if (!back.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, back);
            }
            warnStall("process-fail:mortar", pos, "fidworkblock 研钵加工失败（料与本单不符），已退料回包：{} @ {}", request.id, pos);
        }
    }

    // ═══ 公共小件 ═══

    /**
     * 单料设备入口（压榨机/砧板/炸锅/研钵）：抽「本单第一个可满足的非空配料」1 个。
     * F-168：非单料配方（配料数≠1）不支持本形态——窗口化 ERROR 并跳过（防投退循环；
     * 当前全量数据单料设备均为 1 料，防护为上游后续版本兜底）。
     */
    @Nullable
    private ItemStack extractSingleIngredient(EntityMaid maid, CookingRecipe recipe, BlockPos pos, CookRequest request) {
        List<Ingredient> need = requiredIngredients(recipe);
        if (need.size() != 1) {
            errorStall("multi-ingredient:" + request.id, pos,
                    "fidworkblock 配方 {} 需要 {} 份配料，不支持单料设备，订单挂起：{} @ {}",
                    request.id, need.size(), request.id, pos);
            return null;
        }
        ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                StackPredicate.of(need.get(0)), true);
        return take.isEmpty() ? ItemStack.EMPTY : take;
    }

    @Override
    public List<RecipeData> getAllRecipeData(Level level) {
        RecipeType<?> type = BuiltInRegistries.RECIPE_TYPE.get(RECIPE_TYPE_ID);
        if (type == null) {
            return List.of();
        }
        // M0 实证：R extends Recipe<?> 推断不过须 raw 调用收窄
        @SuppressWarnings("unchecked")
        RecipeType<CookingRecipe> typed = (RecipeType<CookingRecipe>) type;
        List<RecipeData> ans = new ArrayList<>();
        for (RecipeHolder<CookingRecipe> holder : level.getRecipeManager().getAllRecipesFor(typed)) {
            CookingRecipe recipe = holder.value();
            if (!DRIVEN_APPLIANCES.contains(recipe.getAppliance())) {
                continue;
            }
            ItemStack result = recipe.getResult();
            if (!result.isEmpty() && result.has(DataComponents.FOOD)) {
                ans.add(new RecipeData(holder.id(), type, this.getIcon(), result));
            }
        }
        return RecipeDataFilter.apply(ans);
    }
}
