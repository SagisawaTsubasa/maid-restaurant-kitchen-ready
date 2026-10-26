# 女仆餐厅：下得厨房

**Maid Restaurant: Kitchen-Ready**

> 没有小厨娘下不了的厨房——本体管厅堂点单，这里掌勺其余一切灶台。

《女仆餐厅》（`maid_restaurant`）**0.2.9** 点单体系的补全包：通过本体 `CookTasks.register` 通用注册口，把整合包里本体未覆盖的约 **30 类 ≈3000 条**烹饪/酿造/切配/合成配方接入「女仆厨师可做」体系——腌菜罐、竹匾、冰箱、醒发箱、筒仓、冰柜、茶壶、炉式烤熏、**合成配方食物合成（v0.2.2 起无需合成台方块，站立需厨凳/椅子邻位）**、怪物锅、砧板切配、酿造桶、**烟火凡人心灶台方块组（fidworkblock 11 台）**……全都能点。营业链（maid-restaurant-business）按配方类型自动消费本包注册的任务，无依赖无环。**v0.2.5 起链式合成**：厨师女仆做饭缺原料时，自动按「产物→配方」倒排索引求解子合成链（如 3 小麦→面包→牛油果面包），从附近容器补料后原地空手合成中间产物，让原订单续跑——单独运行（无营业 fork）即可用（AUTO 门：fork 在场时自驱让位，fork 走 `ChainCraftApi` 集成）。

- **本名**：下得厨房（取自「上得厅堂，下得厨房」——本体管厅堂，本包下厨房）
- **modid**：`maid_restaurant_cooking`
- **版本线**：Minecraft 1.21.1 / NeoForge 21.1.251 / Java 21
- **协议**：MIT

## 依赖

| 依赖 | 类型 | 说明 |
|---|---|---|
| 女仆餐厅（maid_restaurant）0.2.9+ | **必需** | 注册口构造期入队、本体 commonSetup 冲刷 |
| 森罗厨房（kaleidoscope_cookery）1.6.0+ | **必需** | 本体 0.2.9 硬依赖链 |
| 车万女仆（touhou_little_maid）等本体自身依赖 | 必需 | 随本体带来 |
| 其余约 24 个目标 mod（乐事系/letsdo 系/森罗系/家具等） | 可选 | 装了哪个，对应菜系才接入（ModList 门控，详见 [docs/libs-manifest.md](docs/libs-manifest.md)） |

## 配置

进服后生成 `config/maid_restaurant_cooking-common.toml`：

- **exclusion.excludedRecipePrefixes**：点单菜单排除前缀。默认滤森罗酒馆酿酒键（防止同 RecipeType 后到覆盖顶掉 maidtavern/fox_trot_brew 已接手的酒馆线）与炼金坩埚（药剂非食品）。
- **furnaceRouting.routingRules**：炉式路由映射（烧炼/烟熏/高炉 → 在场烤熏方块），M3 起生效。
- **craftingTable.enabled**：合成配方食物合成开关（默认 true，M8；v0.2.2 起无需合成台方块，站立需厨凳/椅子邻位）。
- **categoryToggles.disabledCategories**：整组关停某命名空间/配方类型，防点单菜单过载。
- **chainCrafting.enabled**：链式合成开关（默认 true，0.2.5）。合成台类子合成（深度≤3、visited 防环、每链≤16 次合成、子合成产物限含食物组件）；无 fork 环境自驱（缺料→补货→原地合成→原订单续跑），营业 fork 在场时自驱自动让位（AUTO 门）。

## 目录

- [docs/验证记录/](docs/验证记录/) —— 逐里程碑验证记录与真机抽验表（真机全链：接单→取料→烹饪→打包→上桌）
- [docs/边界清单.md](docs/边界清单.md) —— 不可适配配方类型，如实记录（随里程碑累积）
- [docs/libs-manifest.md](docs/libs-manifest.md) —— 编译期依赖清单（TSV 来源jar ↔ 实装 jar ↔ modId 实测）
- [docs/scan/烹饪与酿造-配方全量清单.tsv](docs/scan/烹饪与酿造-配方全量清单.tsv) —— 整合包配方全量扫描（3576 条，审计基线）
- [docs/scan/烹饪与酿造体系总览.md](docs/scan/烹饪与酿造体系总览.md) —— 烹饪与酿造体系总览
- [实施计划-0.2.9-全量兼容.md](实施计划-0.2.9-全量兼容.md) —— v5 范围定稿（M0-M7 里程碑）
- [计划书-烹饪全量兼容.md](计划书-烹饪全量兼容.md) —— 立项计划书
- `scripts/collect-libs.ps1` —— 编译期依赖收集脚本（从实装客户端收集，libs/ 不进 git）

## 里程碑状态

> **升级警示（0.2.4·F-221 P1）**：从 ≤0.2.3 升级前，先取消存档中 flex（模糊炒锅/汤锅）在途订单、让女仆回空手空闲态再升级——0.2.4 起本包不注册 flex 任务，残留 flex 在途订单在本体会因任务类型缺失（getType 返 null）触发无防御解引用 NPE：女仆脑 tick 崩服/开相关 GUI 崩客户端（本体反编译实证，证据链见台账 0.2.4 flex 回滚轮 F-221）。根治归本体/fork 侧。

0.1.0 起步、0.2.x 增量交付（0.2.0 M8+M9、0.2.2 自锚点、0.2.3 崩服修复、0.2.4 交付语义入包——**含 flex 模糊配方兼容回滚：flex_pot/flex_stockpot 永不接（2026-10-22 用户决议，归属本体上游/营业中 fork），详见 [docs/边界清单.md](docs/边界清单.md) 永不接决议节**），进度随实施推进勾选，详见实施计划 §二：

- [x] M0 骨架 —— NeoForge 1.21.1 MDK、门控框架、空 mixins、common.toml（2026-10-17，编译绿）
- [x] M1 四模板 + 打样（2026-10-17，flex 两类+公共件；flex 两类已于 0.2.4 回滚永不接——本行为历史打样记录保留）
- [x] M2 森罗系 + 定时容器族（11 任务/11 类型）
- [x] M3 炉式族（路由默认原版炉+食物过滤）
- [x] M4 乐事/农艺/烘焙/砧板（怪物锅 T2 首例+农艺五任务+砧板三系）
- [x] M5 酿造线 + 长尾（酿造桶族四任务；特型如实入边界）
- [x] M6 全量 sweep 自动化部分（runServer 冒烟绿；真机 sweep 待人工）
- [x] M7 审查收口 → 0.1.0 发布（wb 循环 12 轮 P0-P2 零新发现）
- [x] M8 合成台食物合成（CraftingTableCookTask，minecraft:crafting FOOD 过滤 ≈841 条；v0.2.2 自锚点：无需合成台方块，站立自动找凳位/骑坐原地合成）
- [x] M9 fidworkblock 灶台方块组（11 台 373 条；steambox 边界）
- [x] M10 崩服修复（v0.2.3，2026-10-20）—— ICookTask.getIngredients 可变返回契约违约 UOE（本体 cookState 原地 addAll 实证）：25 处 override 全量可变化+契约注，P3 六项（F-195~F-200）随批清偿
- [x] 链式合成（0.2.5，2026-10-25）—— 缺料自动子合成（ChainResolver 倒排索引+ChainCraftService 自驱+ChainCraftApi 公开门面；内存构造合成请求不入任何持久化面）；已知边界见下节

## 链式合成已知边界（0.2.5）

1. 无营业 fork 环境无 DishEatBan，女仆可能偷吃包内中间产物——按需合成数量有界（按缺量，不囤货），fork 环境不受影响。
2. 缺料计算仅女仆背包口径（设备内容物不并入，属后续增强）；子合成求解只消费扣除原订单保留量（required×remain）后的盈余库存，**不反向蚕食原订单已备原料**（防护覆盖求解面+执行面，两条实现路径同款语义：自驱服务补货不足即停不驱动；API 门面补货不足即停返回 false——防子合成齐备预检吃掉原订单保留在包内的同种原料；API 面要求调用方传入盈余面，见 `ChainCraftApi` javadoc，直传全背包会复现 F-237 蚕食。轮次明细以审查台账为准）。
3. 无 IItemHandler 的容器（如 OTC 冰箱）不参与子料补货——无 fork 环境本无 OTC 源桶体系，边界自洽。
4. 非 FOOD 中间物（面粉类）默认不子合成，白名单配置属后续增强。
5. 非合成台工位子合成（石磨/砧板/锅等需走位+占用的设备）属后续批次。

## 许可

MIT
