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
import java.util.function.Predicate;

/**
 * M9 烟火凡人心「灶台方块组」（fidworkblock:cooking，12 设备 397 条，本轮接 11 台 373 条）的女仆烹饪任务。
 * 单类型按配方 {@code appliance} 字段分流——本体 taskMap 按 RecipeType 键控，一个任务类吃全部设备。
 *
 * <p>驱动表（2026-10-18 全量 javap 实证，见 docs/验证记录/M9-fidworkblock.md）：
 * <ul>
 *   <li>炉灶自走系 pressurecooker/dryer/pot：WorldlyContainer 投料→serverTick 自转化
 *       （minHeat 全 0，无需热源）→输出槽取货。槽位经 ApplianceType getter（inputStart=0，
 *       output=inputSlots）。</li>
 *   <li>自走系 squeezingmachine：addInput→startProcessing→等 isWorking 完成→collectOutput。</li>
 *   <li>密封系 jar/teapot/meatmincer：投料→checkRecipe→seal()（内部自启，失败自动解封）
 *       →等完成→collectOutput。</li>
 *   <li>翻炒系 wok：addItem×N→stirFry(null)×requiredStirs（player 参数字节码级确认未消费）
 *       →finishCooking(level,pos)，产物 ItemEntity 落地由拾取 AI 收集；配方命中用配方
 *       result（getCookedResult），未命中才程序化菜——女仆按配方投料必命中，产物=菜单展示。</li>
 *   <li>工具系 choppingboard：addItem→抽厨刀（flavor_immersed_daily:kitchenknife）→
 *       processWithTool(knife, null)（hurtAndBreak 的 ServerPlayer 原版 @Nullable 判空，
 *       null 安全）→产物落地拾取→刀回包（耐久损耗在 BE 内部完成）。</li>
 *   <li>燃油系 fryingpan：addOil+addIngredient→startFrying（要求 isOnDryer 叠放+油≥料）→
 *       等→从 getInventory() 输出槽直接提取（collectOutput(Player) 真消费玩家，绕开）。</li>
 *   <li>即时系 mortar：addItem→processStored(null)（player 仅进 playSound，null 安全）→
 *       产物落地拾取。</li>
 * </ul>
 * 蒸笼 steambox 不接（边界）：注水在 Block.useItemOn 层 + 叠层几何（findBottom/getStackHeight），
 * 程序化复刻成本高、语义未真机验证，如实进 docs/边界清单.md。
 *
 * <p>状态纪律：任务实例单例跨 maid 共享；本任务无跨 tick 可变状态（全部状态在 BE，
 * 每 tick 独立决策），仅 WARN 限频时间戳（与炉式族同款 volatile）。刀/油物品栈惰性解析
 * （构造期 ITEM 注册表未填充 fidworkblock 条目——M0 实证纪律）。
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

    /** F-148 同款：停摆/回包 WARN 上次响出时间戳（30s 时间窗去重）。 */
    private volatile long stallLastWarnMs;

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

    /** 当前订单 → 配方（peek(maid,0) 为本体 OvenCookTask 先例；peek 可能无单，null 安全）。 */
    @Nullable
    private CookRequest peekCookRequest(EntityMaid maid) {
        return RequestManager.peek(maid, 0) instanceof CookRequest cook ? cook : null;
    }

    @Nullable
    private CookingRecipe resolveRecipe(ServerLevel level, CookRequest request) {
        RecipeHolder<?> holder = level.getRecipeManager().byKey(request.id).orElse(null);
        if (holder == null || !RECIPE_TYPE_ID.equals(BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()))
                || !(holder.value() instanceof CookingRecipe recipe)) {
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] fidworkblock 请求配方缺失或类型不符：{}", request.id);
            return null;
        }
        return recipe;
    }

    // ═══════════ 寻址 ═══════════

    @Nullable
    @Override
    public BlockPos searchWorkBlock(ServerLevel level, EntityMaid maid, int horizontalSearchRange, int verticalSearchRange) {
        CookRequest request = peekCookRequest(maid);
        if (request == null) {
            return null;
        }
        CookingRecipe recipe = resolveRecipe(level, request);
        if (recipe == null || !DRIVEN_APPLIANCES.contains(recipe.getAppliance())) {
            return null; // steambox 等：不寻址，订单挂起（边界，操作单可见）
        }
        String appliance = recipe.getAppliance();
        ResourceLocation target = ResourceLocation.fromNamespaceAndPath(MOD_ID, appliance);
        boolean frying = appliance.equals("fryingpan");
        Predicate<BlockPos> matcher = pos -> {
            if (!target.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()))
                    || BlockUsageManager.getUserCount(pos) > 0) {
                return false;
            }
            // 空闲过滤（防抢他人进行中设备）；受理中工位由 isValidWorkBlock 维持，不受此限
            return isIdleForSearch(level.getBlockEntity(pos), appliance, frying);
        };
        List<BlockPos> found = SearchUtils.search(maid.getBrainSearchPos(), horizontalSearchRange, verticalSearchRange, matcher);
        return found.isEmpty() ? null
                : found.stream().min(Comparator.comparingDouble(p -> p.distSqr(maid.blockPosition()))).get();
    }

    /** 搜索期空闲判定：加工中/已密封/有产出的设备不接新单。 */
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
                    && !fp.isWorking() && !fp.hasOutput() && fp.isOnDryer();
            case "mortar" -> be instanceof MortarBlockEntity mo && mo.isEmpty();
            default -> false;
        };
    }

    @Override
    public boolean isValidWorkBlock(ServerLevel level, EntityMaid maid, BlockPos pos) {
        // 受理工位维持：方块 id 是任一已接设备即可（busy 不判失效，
        // 否则密封等待/翻炒中途会被本体换工位丢状态）
        ResourceLocation blockKey = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return MOD_ID.equals(blockKey.getNamespace()) && DRIVEN_APPLIANCES.contains(blockKey.getPath());
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
        CookingRecipe recipe = resolveRecipe(level, request);
        if (recipe == null) {
            return;
        }
        String appliance = recipe.getAppliance();
        if (!DRIVEN_APPLIANCES.contains(appliance)) {
            // steambox 等：不可达（search 不寻址）；防御性响出
            MaidRestaurantCooking.LOGGER.error("[kitchen-ready] fidworkblock 设备 {} 未接入（边界），订单挂起：{}", appliance, request.id);
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
                }
            }
            case "jar" -> {
                if (be instanceof JarBlockEntity j) {
                    tickSealed(maid, pos, request, recipe, "jar", j.getInventory(),
                            JarBlockEntity.INPUT_SLOT_0, JarBlockEntity.OUTPUT_SLOT,
                            j::isSealed, j::isWorking, j::isProcessingDone, j::checkRecipe, j::seal,
                            j::addItemToNextSlot, j::removeLastInput, j::collectOutput, j::setChanged);
                }
            }
            case "teapot" -> {
                if (be instanceof TeapotBlockEntity t) {
                    tickSealed(maid, pos, request, recipe, "teapot", t.getInventory(),
                            TeapotBlockEntity.INPUT_SLOT_0, TeapotBlockEntity.OUTPUT_SLOT,
                            t::isSealed, t::isWorking, t::isProcessingDone, t::checkRecipe, t::seal,
                            t::addItemToNextSlot, t::removeLastInput, t::collectOutput, t::setChanged);
                }
            }
            case "meatmincer" -> {
                if (be instanceof MeatmincerBlockEntity m) {
                    tickSealed(maid, pos, request, recipe, "meatmincer", m.getInventory(),
                            MeatmincerBlockEntity.INPUT_SLOT, MeatmincerBlockEntity.OUTPUT_SLOT,
                            m::isSealed, m::isWorking, m::isProcessingDone, m::checkRecipe, m::seal,
                            m::addItemToSlot, m::removeInput, m::collectOutput, m::setChanged);
                }
            }
            case "pressurecooker", "dryer", "pot" -> {
                if (be instanceof AbstractCookingBlockEntity c) {
                    tickStoveAuto(maid, pos, request, recipe, c);
                }
            }
            case "wok" -> {
                if (be instanceof WokBlockEntity w) {
                    tickWok(level, maid, pos, request, recipe, w);
                }
            }
            case "choppingboard" -> {
                if (be instanceof ChoppingboardBlockEntity cb) {
                    tickChoppingboard(maid, pos, request, recipe, cb);
                }
            }
            case "fryingpan" -> {
                if (be instanceof FryingpanBlockEntity fp) {
                    tickFryingpan(maid, pos, request, recipe, fp);
                }
            }
            case "mortar" -> {
                if (be instanceof MortarBlockEntity mo) {
                    tickMortar(maid, pos, request, recipe, mo);
                }
            }
            default -> MaidRestaurantCooking.LOGGER.error("[kitchen-ready] fidworkblock 未知设备 appliance={}：{}", appliance, request.id);
        }
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
            // 台面已有料（上 tick 投入未启动的残留）：与本单首配料不符则退料
            ItemStack onTable = m.getInputItem();
            if (recipe.getIngredients().stream().noneMatch(i -> !i.isEmpty() && i.test(onTable))) {
                ItemStack back = m.removeInput();
                if (!back.isEmpty()) {
                    ItemUtils.getItemToLivingEntity(maid, back);
                }
                warnStall("fidworkblock 压榨机台面存在与本单不符的料（{}），已退料回包：{} @ {}", onTable.getItem(), request.id, pos);
                return;
            }
        } else {
            ItemStack take = extractIngredient(maid, recipe);
            if (take.isEmpty()) {
                return; // 缺料静默等待
            }
            ItemStack remainder = m.addInput(take);
            if (!remainder.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, remainder);
                warnStall("fidworkblock 压榨机投料被拒（{}），原料已回包：{} @ {}", remainder.getItem(), request.id, pos);
                return;
            }
        }
        if (!m.startProcessing()) {
            // 投料与配方不符（不该发生：按配方抽取+台面校验）；退料回包 + 响出
            ItemStack back = m.removeInput();
            if (!back.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, back);
            }
            warnStall("fidworkblock 压榨机 startProcessing 失败，已退料回包：{} @ {}", request.id, pos);
        }
    }

    // ═══ 密封系（jar/teapot/meatmincer 同构，ItemStackHandler 槽位 + seal 自启）═══

    private void tickSealed(EntityMaid maid, BlockPos pos, CookRequest request, CookingRecipe recipe, String name,
                            ItemStackHandler inv, int inputStart, int outputSlot,
                            java.util.function.BooleanSupplier isSealed, java.util.function.BooleanSupplier isWorking,
                            java.util.function.BooleanSupplier isProcessingDone, java.util.function.BooleanSupplier checkRecipe,
                            Runnable seal, java.util.function.UnaryOperator<ItemStack> addItem,
                            java.util.function.Supplier<ItemStack> removeLastInput,
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

        // F-139 同款：台面残留扫描——存在与本单任何配料都不符的料则整体退料回包
        List<Integer> occupied = new ArrayList<>();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (i == outputSlot) {
                continue;
            }
            ItemStack slotStack = inv.getStackInSlot(i);
            if (!slotStack.isEmpty()) {
                if (recipe.getIngredients().stream().noneMatch(ing -> !ing.isEmpty() && ing.test(slotStack))) {
                    rollbackSealed(maid, inv, outputSlot, removeLastInput);
                    warnStall("fidworkblock {} 台面存在与本单不符的料，已退料回包：{} @ {}", name, request.id, pos);
                    return;
                }
                occupied.add(i);
            }
        }

        // 补投：逐配料查「已投」，缺的抽 1 投入空槽
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            boolean present = occupied.stream().anyMatch(i -> ingredient.test(inv.getStackInSlot(i)));
            if (present) {
                continue;
            }
            ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                    StackPredicate.of(ingredient), true);
            if (take.isEmpty()) {
                return; // 缺料静默等待（已投的部分留在台面，下 tick 续投）
            }
            boolean putIn = false;
            for (int i = 0; i < inv.getSlots() && !putIn; i++) {
                if (i == outputSlot) {
                    continue;
                }
                if (inv.getStackInSlot(i).isEmpty()) {
                    inv.setStackInSlot(i, take);
                    putIn = true;
                    occupied.add(i);
                }
            }
            if (!putIn) {
                ItemUtils.getItemToLivingEntity(maid, take);
                warnStall("fidworkblock {} 输入槽满，原料已回包：{} @ {}", name, request.id, pos);
                return;
            }
        }
        if (occupied.isEmpty()) {
            return; // 没投任何料（异常配方），静默
        }
        setChanged.run();
        if (checkRecipe.getAsBoolean()) {
            seal.run(); // 内部自启；失败由设备自行解封（seal 语义，javap 实证）
            setChanged.run();
        } else {
            // 按配方投满但 checkRecipe 仍 false：全部退料回包 + 响出
            rollbackSealed(maid, inv, outputSlot, removeLastInput);
            warnStall("fidworkblock {} 按配方投料后 checkRecipe 失败，已退料回包：{} @ {}", name, request.id, pos);
        }
    }

    /** 密封系退料回包：输入槽逐个清空交还女仆。 */
    private void rollbackSealed(EntityMaid maid, ItemStackHandler inv, int outputSlot,
                                java.util.function.Supplier<ItemStack> removeLastInput) {
        for (int i = 0; i < inv.getSlots(); i++) {
            if (i == outputSlot) {
                continue;
            }
            ItemStack slotStack = inv.getStackInSlot(i);
            if (!slotStack.isEmpty()) {
                inv.setStackInSlot(i, ItemStack.EMPTY);
                ItemUtils.getItemToLivingEntity(maid, slotStack);
            }
        }
        // removeLastInput 兜底（防槽位模型差异漏料）
        ItemStack back;
        while (!(back = removeLastInput.get()).isEmpty()) {
            ItemUtils.getItemToLivingEntity(maid, back);
        }
    }

    // ═══ 炉灶自走系（pressurecooker/dryer/pot：WorldlyContainer）═══

    private void tickStoveAuto(EntityMaid maid, BlockPos pos, CookRequest request,
                               CookingRecipe recipe, AbstractCookingBlockEntity c) {
        int outputSlot = c.getApplianceType().getOutputSlot();
        ItemStack expect = recipe.getResult();
        ItemStack output = c.getItem(outputSlot);
        if (!output.isEmpty() && output.getItem() != expect.getItem()) {
            warnStall("fidworkblock 炉灶系输出槽为非本单产物（{}），停摆待处理：{} @ {}", output.getItem(), request.id, pos);
            return;
        }
        if (!output.isEmpty()) {
            ItemStack taken = c.removeItem(outputSlot, output.getCount());
            if (!taken.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, taken);
                request.remain--;
                maid.swing(InteractionHand.OFF_HAND);
            }
            return;
        }

        // 投料：输入槽区 [inputStart, inputStart+inputSlots)，逐配料查已投，缺的抽 1 放空槽
        int inputStart = c.getApplianceType().getInputStartSlot();
        int inputSlots = c.getApplianceType().getInputSlots();
        boolean supplied = false;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            boolean present = false;
            for (int i = 0; i < inputSlots; i++) {
                if (ingredient.test(c.getItem(inputStart + i))) {
                    present = true;
                    break;
                }
            }
            if (present) {
                continue;
            }
            ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                    StackPredicate.of(ingredient), true);
            if (take.isEmpty()) {
                return; // 缺料静默等待（已投部分留在台面，下 tick 续投）
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
                warnStall("fidworkblock 炉灶系输入槽满，原料已回包：{} @ {}", request.id, pos);
                return;
            }
        }
        if (supplied) {
            c.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
        }
        // serverTick 自转化（minHeat=0 无需热源）；产物由取货分支完成
    }

    // ═══ 翻炒系（wok）═══

    private void tickWok(ServerLevel level, EntityMaid maid, BlockPos pos, CookRequest request,
                         CookingRecipe recipe, WokBlockEntity w) {
        if (w.getItemCount() <= 0) {
            boolean supplied = false;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                        StackPredicate.of(ingredient), true);
                if (take.isEmpty()) {
                    return; // 缺料静默等待（已投部分留锅内，下 tick 续投）
                }
                if (!w.canAddItem()) {
                    ItemUtils.getItemToLivingEntity(maid, take);
                    warnStall("fidworkblock 炒锅投料被拒（满/非配方料），原料已回包：{} @ {}", request.id, pos);
                    return;
                }
                w.addItem(take);
                supplied = true;
            }
            if (supplied) {
                w.setChanged();
                maid.swing(InteractionHand.OFF_HAND);
            }
            return;
        }
        if (w.getStirCount() < w.getRequiredStirs()) {
            // 逐 tick 翻炒一次；player 参数未消费（字节码实证），传 null 安全
            w.stirFry(null);
            w.setChanged();
            maid.swing(InteractionHand.OFF_HAND);
            return;
        }
        // 翻炒到位收锅：配方命中用配方 result，产物 ItemEntity 落地由拾取 AI 收集
        w.finishCooking(level, pos);
        w.setChanged();
        request.remain--;
        maid.swing(InteractionHand.OFF_HAND);
    }

    // ═══ 工具系（choppingboard）═══

    private void tickChoppingboard(EntityMaid maid, BlockPos pos, CookRequest request,
                                   CookingRecipe recipe, ChoppingboardBlockEntity cb) {
        if (cb.isEmpty()) {
            ItemStack take = extractIngredient(maid, recipe);
            if (take.isEmpty()) {
                return; // 缺料静默等待
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
        if (recipe.getIngredients().stream().noneMatch(i -> !i.isEmpty() && i.test(onTable))) {
            ItemStack back = cb.removeItem();
            if (!back.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, back);
            }
            warnStall("fidworkblock 砧板台面存在与本单不符的料（{}），已退料回包：{} @ {}", onTable.getItem(), request.id, pos);
            return;
        }
        // 抽厨刀（判定 id=flavor_immersed_daily:kitchenknife，javap 实证自 private isKitchenKnife）
        ItemStack knife = itemStackOf(KNIFE_ID);
        if (knife.isEmpty()) {
            warnStall("fidworkblock 需要厨刀但 flavor_immersed_daily:kitchenknife 未注册，订单挂起：{} @ {}", request.id, pos);
            return;
        }
        ItemStack held = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                StackPredicate.of(s -> s.is(knife.getItem())), true);
        if (held.isEmpty()) {
            warnStall("fidworkblock 砧板等待厨刀但女仆无厨刀，订单挂起：{} @ {}", request.id, pos);
            return;
        }
        boolean done = cb.processWithTool(held, null); // ServerPlayer @Nullable 原版判空，null 安全
        ItemUtils.getItemToLivingEntity(maid, held); // 刀回包（耐久损耗已在 BE 内部完成）
        if (done) {
            cb.setChanged();
            request.remain--; // 产物 ItemEntity 落地，拾取 AI 收集（FD 砧板同款）
            maid.swing(InteractionHand.OFF_HAND);
        } else {
            warnStall("fidworkblock 砧板 processWithTool 失败（料与本单不符）：{} @ {}", request.id, pos);
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
            warnStall("fidworkblock 炸锅未叠放在变温烤箱上，订单挂起：{} @ {}", request.id, pos);
            return;
        }
        if (!fp.hasOil()) {
            ItemStack oil = itemStackOf(OIL_ID);
            if (oil.isEmpty()) {
                warnStall("fidworkblock 炸锅需食用油但 {} 未注册，订单挂起：{} @ {}", OIL_ID, request.id, pos);
                return;
            }
            ItemStack held = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                    StackPredicate.of(s -> s.is(oil.getItem())), true);
            if (held.isEmpty()) {
                warnStall("fidworkblock 炸锅等待食用油但女仆无油，订单挂起：{} @ {}", request.id, pos);
                return;
            }
            ItemStack remainder = fp.addOil(held);
            if (!remainder.isEmpty()) {
                ItemUtils.getItemToLivingEntity(maid, remainder);
            }
            fp.setChanged();
        }
        if (!fp.hasIngredient()) {
            ItemStack take = extractIngredient(maid, recipe);
            if (take.isEmpty()) {
                return; // 缺料静默等待（油已在台上）
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
            ItemStack take = extractIngredient(maid, recipe);
            if (take.isEmpty()) {
                return; // 缺料静默等待
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
            warnStall("fidworkblock 研钵加工失败（料与本单不符），已退料回包：{} @ {}", request.id, pos);
        }
    }

    // ═══ 公共小件 ═══

    /** 抽「本单第一个可满足的非空配料」1 个（单料设备入口）。 */
    private ItemStack extractIngredient(EntityMaid maid, CookingRecipe recipe) {
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            ItemStack take = ItemHandlerUtils.tryExtractSingleSlot(maid.getAvailableInv(false), 1,
                    StackPredicate.of(ingredient), true);
            if (!take.isEmpty()) {
                return take;
            }
        }
        return ItemStack.EMPTY;
    }

    private void warnStall(String format, Object... args) {
        if (System.currentTimeMillis() - stallLastWarnMs > 30_000L) {
            stallLastWarnMs = System.currentTimeMillis();
            MaidRestaurantCooking.LOGGER.warn("[kitchen-ready] " + format, args);
        }
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
