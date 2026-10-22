# 女仆餐厅：下得厨房

**Maid Restaurant: Kitchen-Ready**

> 没有小厨娘下不了的厨房——本体管厅堂点单，这里掌勺其余一切灶台。

《女仆餐厅》（`maid_restaurant`）**0.2.9** 点单体系的补全包：通过本体 `CookTasks.register` 通用注册口，把整合包里本体未覆盖的约 **80 类 ≈3400 条**烹饪/酿造/切配/合成配方接入「女仆厨师可做」体系——腌菜罐、竹匾、冰箱、醒发箱、筒仓、冰柜、茶壶、炉式烤熏、**合成配方食物合成（v0.2.2 起无需合成台方块，站立需厨凳/椅子邻位）**、怪物锅、砧板切配、酿造桶、**烟火凡人心灶台方块组（fidworkblock 11 台）**……全都能点。营业链（maid-restaurant-business）按配方类型自动消费本包注册的任务，无依赖无环。

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

0.1.0 起步、0.2.x 增量交付（0.2.0 M8+M9、0.2.2 自锚点、0.2.3 崩服修复、0.2.4 交付语义入包——**含 flex 模糊配方兼容回滚：flex_pot/flex_stockpot 永不接（2026-10-22 用户决议，归属本体上游/营业中 fork），详见 [docs/边界清单.md](docs/边界清单.md) 永不接决议节**），进度随实施推进勾选，详见实施计划 §二：

- [x] M0 骨架 —— NeoForge 1.21.1 MDK、门控框架、空 mixins、common.toml（2026-10-17，编译绿）
- [x] M1 四模板 + 打样（2026-10-17，flex 两类+公共件；flex 两类已于 0.2.4 回滚永不接——本行为历史打样记录保留）
- [x] M2 森罗系 + 定时容器族（11 任务/10 类型）
- [x] M3 炉式族（路由默认原版炉+食物过滤）
- [x] M4 乐事/农艺/烘焙/砧板（怪物锅 T2 首例+农艺五任务+砧板三系）
- [x] M5 酿造线 + 长尾（酿造桶族四任务；特型如实入边界）
- [x] M6 全量 sweep 自动化部分（runServer 冒烟绿；真机 sweep 待人工）
- [x] M7 审查收口 → 0.1.0 发布（wb 循环 12 轮 P0-P2 零新发现）
- [x] M8 合成台食物合成（CraftingTableCookTask，minecraft:crafting FOOD 过滤 ≈841 条；v0.2.2 自锚点：无需合成台方块，站立自动找凳位/骑坐原地合成）
- [x] M9 fidworkblock 灶台方块组（11 台 373 条；steambox 边界）
- [x] M10 崩服修复（v0.2.3，2026-10-20）—— ICookTask.getIngredients 可变返回契约违约 UOE（本体 cookState 原地 addAll 实证）：25 处 override 全量可变化+契约注，P3 六项（F-195~F-200）随批清偿

## 许可

MIT
