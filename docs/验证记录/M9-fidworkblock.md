# M9 验证记录：烟火凡人心灶台方块组（fidworkblock）

- 日期：2026-10-18
- 出口判据：编译绿 + runServer 冒烟绿 + 真机抽验（待人工）
- 漏扫修正：v0.1.0 扫描时 `fidworkblock:cooking` 类型不在已知类型清单（TSV 0 条），实为 jar 内 `data/fidworkblock/recipe/<设备>/` 全量在库——本里程碑补扫并接入

## 12 设备盘点（javap ModBlocks/ModBlockEntities/ApplianceType + 配方 JSON 计数）

| 设备 | appliance | 条数 | zh_cn | 驱动模型 | 本轮处置 |
|---|---|---|---|---|---|
| squeezingmachine | squeezingmachine | 42 | 压榨机 | 自走（投料即启动） | ✅ 接 |
| jar | jar | 25 | 坛子 | 密封（seal 自启） | ✅ 接 |
| teapot | teapot | 19 | 茶壶 | 密封 | ✅ 接 |
| meatmincer | meatmincer | 5 | 绞肉机 | 密封 | ✅ 接 |
| pressurecooker | pressurecooker | 28 | 高压锅 | 炉灶自走（WorldlyContainer） | ✅ 接 |
| dryer | dryer | 31 | 变温烤箱 | 炉灶自走（mode 字段 5 条 oven） | ✅ 接 |
| pot | pot | 51 | 汤锅 | 炉灶自走（container 槽 0 条配方用） | ✅ 接 |
| wok | wok | 34 | 炒锅 | 翻炒 N 次→finishCooking | ✅ 接 |
| choppingboard | choppingboard | 60 | 砧板 | 厨刀 processWithTool | ✅ 接 |
| fryingpan | fryingpan | 33 | 炸锅 | 燃油+叠放 dryer | ✅ 接 |
| mortar | mortar | 45 | 研钵 | 即时 processStored | ✅ 接 |
| steambox | steambox | 24 | 蒸笼 | 叠层+注水（Block.useItemOn） | ❌ 边界（见下） |

合计 397 条；本轮落地面 373 条（steambox 24 条边界）。

## 关键实证（javap 字节码级）

- **单类型分流**：全部设备共享 `fidworkblock:cooking`（ModRecipeTypes 注册 "cooking"），配方按 `appliance` 字段（=ApplianceType 枚举小写名=方块注册路径）分流。
- **炉灶自走系**：AbstractCookingBlockEntity implements WorldlyContainer；serverTick 自动 findMatchingRecipe→canCook→cookRecipe；397 条 minHeat 全 0→**无需热源**；槽位 ApplianceType.getInputStartSlot()=0 / getOutputSlot()=inputSlots / getContainerSlot()=inputSlots+1（仅 pot hasContainer，0 条配方写 container→不触发）。
- **wok 产物语义**：finishCooking→findRecipeResult **配方命中用配方 result**（getCookedResult），未命中才 DishItem.createProceduralDish 程序化菜——女仆按配方投料必命中，产物=菜单展示，无失真。stirFry(Player) 的 player 参数**字节码级未消费**（全程无 aload_1），null 安全。
- **choppingboard**：processWithTool(tool, player)→doProcess→**ItemStack.hurtAndBreak(int, ServerLevel, @Nullable ServerPlayer, Consumer)**——原版源码 L429 显式判空（`p_220160_ == null || !hasInfiniteMaterials()`），null 安全；产物 ItemEntity 落地（setDefaultPickUpDelay）→拾取 AI；厨刀判定 id=`flavor_immersed_daily:kitchenknife`（静态初始化 ldc 实证）。
- **fryingpan**：collectOutput(Player) **真消费玩家**（isCreative/getInventory）→女仆绕开，走 getInventory().extractItem(OUTPUT_SLOT)；startFrying 条件=hasOil+hasIngredient+**isOnDryer**（叠放校验进 searchWorkBlock 谓词）+油≥料数；油判定 id=`flavor_immersed_daily:cookingoil`（本体炉式线可烧出，物料闭环）。
- **mortar**：processStored(player) 的 player 仅进 Level.playSound（原版 @Nullable），null 安全；即时（isInstant，cookingTime=0）。
- **密封系**：seal() 内部置 sealed→startProcessing()，失败自动解封（putfield 回 false）——先 checkRecipe()==true 再 seal；addItemToNextSlot 返回 insertItem remainder（NeoForge 语义）。
- **刀/油惰性解析**：构造期 ITEM 注册表未填充 fidworkblock/flavor_immersed_daily 条目（M0 实证纪律），运行期 itemStackOf(id) 惰性解析。

## 实现

- `FidworkblockCookTask implements ICookTask`：单任务类，cookTick 按 appliance 分七驱动策略（炉灶自走/自走/密封/翻炒/工具/燃油/即时）；searchWorkBlock 经 `RequestManager.peek(maid, 0)`（本体 OvenCookTask 先例）取当前订单→解析 appliance→按方块 id 寻址+空闲过滤（isIdleForSearch：加工中/已密封/有产出不接新单）；isValidWorkBlock 只验设备身份（受理中工位不因 busy 失效，防中途换工位丢状态）。
- `getAllRecipeData`：只收录 DRIVEN_APPLIANCES 且产物含 FOOD 组件（研钵砂砾/土膏等非食品天然被滤）。
- 门控：CompatMods.FIDWORKBLOCK（modId 实测自其 neoforge.mods.toml）+ mods.toml optional + logGates。

## R1 审查修复（2026-10-18，13 发现全修：P1×1 P2×4 P3×8）

- **F-161 (P1)**：wok 改「计数式配齐才翻炒 + 收锅前配方自校验」——原「锅空才投料」门控使部分投料永不续投、半成品被程序化菜顶单。
- **F-162 (P2)**：isValidWorkBlock 校验工位与本单 appliance 同型（peek null 宽容）；11 case 补 mismatch WARN。
- **F-163 (P2)**：fryingpan 台面残留判定（对称面）+ isIdleForSearch 补 !hasIngredient。
- **F-164 (P2)**：炉灶系输入区残留扫描（与密封系同构退料+WARN）。
- **F-165 (P2)**：searchWorkBlock 未接设备 WARN + 炸锅「在场但不叠放」F-128 式检测 WARN。
- **F-166/F-167/F-168/F-169/F-170/F-171/F-172/F-173 (P3)**：密封系死参数清除；「已投」改计数式（需求份数=配料重复次数）；单料设备入口配料数防护；窗口化日志键控（消息码:工位）；ERROR 窗口化；占位符修复；文档漂移修正；炉灶系取货补 setChanged。

### 待确认取证结论（R1 审查员缺证据项，本机 javap/解包补齐）

| 项 | 结论 | 证据 |
|---|---|---|
| a. peek 签名语义 | `RequestManager.peek(EntityMaid, int) → IRequest`，本体 OvenCookTask 字节码 `peek(maid, 0)`+checkcast CookRequest+requireNonNull；CookRequest 字段 id/type/remain | maid_restaurant-0.2.9.jar javap（本会话实证） |
| b. 重复/多配料分布 | **重复配料真实存在**：jar dup=6 / teapot dup=3（buckwheattea: buckwheat×2）/ pot dup=3 / pressurecooker dup=6 / wok dup=5（beanwithsesamesauce: choppedcowpea×2）；单料设备（choppingboard/dryer/fryingpan/meatmincer/mortar/squeezingmachine/steambox）全 1 料 dup=0 → F-167 升级必修，F-168 防护性修复 | fidworkblock jar 解包全量 JSON 统计（397 条） |
| c. removeItem 置脏 | 仅在槽清空时 setChanged（vanilla ContainerHelper.removeItem 模式）→ F-173 取货侧对称补 setChanged 必要 | AbstractCookingBlockEntity javap -c |
| d. finishCooking 返回 | void（无成功标志）→ F-161 收锅校验用配方自校验实现 | WokBlockEntity javap |
| e. processWithTool 损刀 | 原地：processWithTool 把传入 tool 经 doProcess 直调 ItemStack.hurtAndBreak（原版 @Nullable ServerPlayer 判空） | ChoppingboardBlockEntity javap -c |

## 边界（本轮新增）

| 类型 | 来源 mod | 条数 | 原因 | 处置 |
|---|---|---|---|---|
| steambox 蒸笼 | fidworkblock | 24 | 注水消费在 SteamboxBlock.useItemOn 层（isTidyWater+shrink）+ 叠层几何（findBottom/getStackHeight，最高 16 层），程序化复刻成本高、语义未真机验证 | 边界清单，不硬凑；后续可按 Block 交互复刻评估 |

## 构建

`gradlew compileJava` 绿。

## 真机抽验操作单（待人工，用例见 真机sweep操作单.md 的「fidworkblock 灶台方块组（M9）」节）

压榨机苹果→果酱×2（必测）/ 坛子腌菜 seal 闭环 / 高压锅炖汤 / 炒锅翻炒收锅 / 砧板切菜（女仆带厨刀）/ 炸锅（须叠放 dryer 上+女仆带食用油）/ 研钵磨粉。
