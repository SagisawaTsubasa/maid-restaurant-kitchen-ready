# M1 验证记录：四模板 + 公共件 + flex 打样

- 日期：2026-10-17
- 出口判据：flex_pot(47)/flex_stockpot(51) 编译绿 + 公共件三件就位 + 真机全链操作单（待人工）

## 构建验证（自动化，已过）

| 项 | 结果 |
|---|---|
| `gradlew build` | **BUILD SUCCESSFUL**（含 FlexPotCookTask / FlexStockpotCookTask / CookTaskRegistry / RecipeDataFilter / FurnaceRouting） |
| libs 增补 | + `touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar`（EntityMaid 在 ICookTask 签名里，本体硬依赖链，collect-libs.ps1 extras 段收集；TSV 不含非烹饪 mod 故 M0 未收） |

## flex 打样实证链（反编译/javap，全部落码前验证）

| 实证点 | 结论 |
|---|---|
| flex 配方类结构 | `FlexPotRecipe`/`FlexStockpotRecipe` 与普通版互不继承（final Record）；FlexPotRecipe(time, stirFryCount, Ingredient carrier, NonNullList\<Ingredient\> ingredients, ItemStack result)；FlexStockpotRecipe 多 soupBase/visuals |
| 锅 BE 原生支持 | `PotBlockEntity.startCooking` 先查 pot 后查 flex；`StockpotBlockEntity` 内建 `flexQuickCheck`——女仆投料后 BE 自匹配，闭环成立 |
| RecipeType 字段时机 | 森罗 `ModRecipes.FLEX_*` 在 `register(RegisterEvent)` 赋值，**构造期为 null** → 注册器构造期只记 UID 不调 getType()（本体冲刷在 commonSetup，届时已填充） |
| `RecipeManager.byKeyTyped` | 1.21.1 vanilla 中为 **private**（本体自带 AT 才能调）→ 本包用公开 `byKey` + `instanceof FlexXxxRecipe` 校验，缺失/不符 ERROR 落日志不崩 tick |
| StackPredicate API | 有 `(Ingredient)` 构造器与 `of(Ingredient)` 重载——模糊配料直配 |
| T1 修订 | 「子类改键」原案不可行（见边界清单），落地为继承 + 状态机换键复制；searchWorkBlock/isValidWorkBlock/getCurrentInput/getIcon/getKitchenWares 继承父类 |

## 公共件三件

| 件 | 状态 |
|---|---|
| 批量注册器 `CookTaskRegistry.registerAll()` | 构造期门控入队（flex 两类）；M6 冒烟补注册后自检 |
| 排除过滤器 `RecipeDataFilter.apply()` | exclusion 前缀（默认 kaleidoscope_tavern:/irons_spellbooks:alchemist_cauldron）+ categoryToggles；本包两类 flex 的 getAllRecipeData 已接线 |
| 映射表加载器 `FurnaceRouting` | 惰性解析 + 非法行 WARN + invalidate()；M3 T4 消费 |

## 模板定型

`docs/模板设计-T1-T4.md`：T1 已修订落地（flex 两类即实例）；T2/T3/T4 实证基础与首个实例锚点写明，随 M2-M4 实例化回填（不预造空抽象）。

## 真机全链操作单（待人工）

前置：M0 客户端步已过（jar 装入、加载日志正常）。

1. 进世界（创造即可），`/give @s kaleidoscope_cookery:pot` 放置 + 下方放热源（营火）。
2. 女仆餐厅雇一名女仆，厨房区域放「女仆厨房 POI」相关设施（本体厨房方块）。
3. 对点单 UI 下单一道**模糊炒锅**配方（菜单里图标同炒锅、产物为泛用菜品的条目；或 `/kubejs` 查 `kaleidoscope_cookery:flex_pot` 任一 id 手动下单）。
4. 判定：女仆取油→倒油→投料→锅铲翻炒→**上菜为对应 flex 产物**；日志无 `flex_pot 请求配方缺失或类型不符` ERROR。
5. 汤锅同理（`kaleidoscope_cookery:stockpot` + 汤底桶 + flex_stockpot 配方）。

结果回填：＿＿＿＿（未验证 / 通过 / 失败+现象）
