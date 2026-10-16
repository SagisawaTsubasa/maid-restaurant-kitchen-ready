# M6 验证记录：全量 sweep（自动化部分）

- 日期：2026-10-17
- 出口判据：dev runServer 冒烟绿 + 全类验证记录表 + 真机全链操作单（≈100+ 例待人工）

## 自动化冒烟（dev runServer，已过）

| 项 | 结果 |
|---|---|
| 环境 | dev runServer + maid_restaurant 0.2.9 + kaleidoscope_cookery 1.6.0 + TLM 1.5.3 + geckolib + patchouli（run/mods） |
| 服务器启动 | **Done (5.978s)!** 正常进入待命 |
| 本包日志 | 加载行 + 门控行（必需链 OK，可选 0 在场=dev 预期）+ 8 任务入队（flex 2/竹匾/茶壶/菜板/炉式 3）+ 入队完成行 |
| 本包 ERROR/WARN | **0** |
| config | `maid_restaurant_cooking-common.toml` 生成 ✓ |
| 环境杂音（非本包） | server.properties 首启缺失；森罗数据包在 dev 引用缺失物品（正式环境由森罗兼容修复 mod 处理） |

**本冒烟覆盖**：mod 加载链、门控分流、构造期入队纪律、本体冲刷前时序、config 生成、注册表时序约束（构造期不取 getType）。**门控任务（可选 mod 在场路径）由真机 sweep 覆盖**。

## 全类验证记录总表（≈34 接入判定）

| # | 类型 | 条数 | 接入状态 | 真机全链（接单→取料→烹饪→打包→上桌） |
|---|---|---|---|---|
| 1 | cookery:flex_pot | 47 | ✅ M1 | 待人工 |
| 2 | cookery:flex_stockpot | 51 | ✅ M1 | 待人工 |
| 3 | cookery:bamboo_tray | 28 | ✅ M2 | 待人工 |
| 4 | cookery:teapot | 20 | ✅ M2（含补放闭环） | 待人工 |
| 5 | cookery:chopping_board | 16 | ✅ M4 | 待人工 |
| 6 | chinesefood:pickle_jar | 7 | ✅ M2 | 待人工 |
| 7 | chinesefood:freezing | 2 | ✅ M2 | 待人工 |
| 8 | chinesefood:refrigerating | 2 | ✅ M2 | 待人工 |
| 9 | fid:fridge_freezing | 6 | ✅ M2 | 待人工 |
| 10 | fid:fridge_tempering | 1 | ✅ M2 | 待人工 |
| 11 | farm_and_charm:drying | 15+4 | ✅ M2 | 待人工 |
| 12 | bakeries:fermentation_box | 4 | ✅ M2 | 待人工 |
| 13 | world_liquor:freezer | 5 | ✅ M2 | 待人工 |
| 14 | herbalbrews:kettle_brewing | 9 | ✅ M2 | 待人工 |
| 15 | minecraft:smelting（食物过滤） | 574 中可食 | ✅ M3 | 待人工 |
| 16 | minecraft:smoking（食物过滤） | 241 中可食 | ✅ M3 | 待人工 |
| 17 | minecraft:blasting（食物过滤） | 175 中可食 | ✅ M3 | 待人工 |
| 18 | farm_and_charm:mincer | 59+2 | ✅ M4 | 待人工 |
| 19 | farm_and_charm:stove | 9+3+19 | ✅ M4 | 待人工 |
| 20 | farm_and_charm:pot_cooking | 10+2+9 | ✅ M4 | 待人工 |
| 21 | farm_and_charm:roaster | 9+1 | ✅ M4 | 待人工 |
| 22 | farm_and_charm:crafting_bowl | 6+1+2 | ✅ M4 | 待人工 |
| 23 | farmersdelight:cutting | 259+DD 18+乡野 13 | ✅ M4 | 待人工 |
| 24 | dungeonsdelight:monster_cooking | 70 | ✅ M4 | 待人工 |
| 25 | refurbished_furniture:cutting_board_slicing | 41 | ✅ M4 | 待人工 |
| 26 | vinery:apple_mashing/fermenting | 4 | ✅ M5 | 待人工 |
| 27 | vinery:wine_fermentation | 26 | ✅ M5 | 待人工 |
| 28 | brewery:brewing | 16 | ✅ M5 | 待人工 |
| 29 | beachparty:palm_bar_mixing | 6 | ✅ M5 | 待人工 |
| 30 | minecraft:campfire_cooking | 293 | ⭕ 本体 ToasterCookTask（只验不接，缺口另立议题） | 待人工 |
| 31 | 本体内置 8 类（pot/stockpot/steamer/FD cooking/bakeries 三类） | — | ⭕ 本体管（砍掉，v5 §一） | — |
| 32 | 挂已接类型的跨 mod 行（brewery→farmcharm 各键等） | ≈60 | ✅ 自动覆盖 | 随主类型抽验 |
| 33 | 边界清单 12 项 | ≈280 | ❌ 如实登记（docs/边界清单.md） | — |
| 34 | 排除过滤（酒馆键/坩埚/分类开关） | — | ✅ M1 公共件 | 待人工（toml 改行验证） |

## 真机 sweep 操作单（待人工）

全量操作单与逐例判定表见 [docs/验证记录/真机sweep操作单.md](真机sweep操作单.md)；璇穹之歌-O 客户端 + 本体 0.2.9 + 本包 + 营业 fork 可叠加。
