# Module-Shop-Addons 更新与备份说明（2026-11-11）

- **执行与撰写模型**：Google **Gemini**
- **更新日期**：2026-11-11
- **目标同步工程**：`E:\心自安然项目\Module-Shop-Addons`（[云端同步目录](https://drive.google.com/drive/folders/1tYYiEzkc01RcgU4Ng7TGzmN15lqjAkJB)）
- **运行环境基线**：Minecraft `1.21.1` · NeoForge `21.1.248` · Module Shop `1.1.0`（公共 API `1.3`）· AtomLib `0.4.0`

## 一、原文件无损备份与一键回滚说明

本次更新严格遵守**先归档备份、绝不直接覆盖销毁原文件**的原则，所有被替换的 22 个原始文件均已原封不动迁入项目既有归档目录 `_archive` 下保存（保留原始 Google Drive 文件 ID、原始修改时间与完整字节内容，同时确保 `src/` 目录内不残留同名副本干扰 Gradle 编译）：

- **备份根目录**：`E:\心自安然项目\_archive\Module-Shop-Addons-before-gemini-20261111`（[打开备份根目录](https://drive.google.com/drive/folders/1DERJuy-E8DLDad4GYE3pHnu-MoTqXN1x)）
  - **股市原版备份（10 个文件）**：`_archive\Module-Shop-Addons-before-gemini-20261111\stock`（[打开股市备份夹](https://drive.google.com/drive/folders/1ewW2zCEguu_H2rWLAKJHVI1USDg517Be)）
  - **盲仓原版备份（12 个文件）**：`_archive\Module-Shop-Addons-before-gemini-20261111\blind`（[打开盲仓备份夹](https://drive.google.com/drive/folders/19x1A0Ey6wCitP8ODDR68-hXcodD9SCVb)）
  - **一键回滚脚本**：`restore-backup.ps1`（[查看脚本](https://drive.google.com/file/d/1HYB9n69jOfyzfLM7Q-Vlqz2CChaxs7og/view?usp=drivesdk)）
- **一键回滚命令**（如需还原至更新前状态，在 PowerShell 执行）：
  ```powershell
  cd "E:\心自安然项目\_archive\Module-Shop-Addons-before-gemini-20261111"
  .\restore-backup.ps1
  ```

## 二、本次对话核心更新内容汇总

### 1. 虚拟股市附属（`stock/` · `module_shop_stock`）

1. **波段动量模型重构与消除 1 分线死水横盘**（`PriceModelV3.java`、`PriceParams.java`、`StockMarketConfig.java`、`PriceModelV3Test.java`）：
   - **保留星币整数位**：全程保持 `long` 整数星币报价与成交结算精度，现价全程以未取整的 double 跨 tick 连续演化（仅对外报价/落 K 线时取整），并新增公允价低价放大 fairScale（上限 barFairMaxScale=3.20），使公允价与现价同步获得有界补偿，低价股能走出跨整数格的波段，避免长时间拉直线。
   - **放宽短周期动量限制**：下调短周期动量衰减阻力并放宽单跳限幅约束，结合多时间尺度趋势漂移、成交量脉冲反馈与温和均值回归，使 1 分线呈现自然的波段推升、回调与盘整节奏。
   - **存档与持仓安全**：模型与配置更新仅作用于后续行情演化；若删除旧服务端配置文件以重新生成新默认参数，**不会影响**玩家在 `StockMarketSavedData`（`world/data/module_shop_stock.dat`）中已持有的多空仓位、冻结保证金与历史挂单。
2. **多周期 K 线（5m / 15m / 60m / 1d / 1w）鼠标滚轮缩放与拖拽修复**（`StockMarketState.java`、`ChartRenderer.java`、`QuoteView.java`、`BarHistorySeeder.java`、`StockMarketSavedData.java`）：
   - **根除滚轮空转死区**：原逻辑在 `visibleBars`（默认 60）大于非 1m 周期实际历史 K 线数量（如日线仅十余根）时，滚轮缩放只修改超出数组上界的 `visibleBars` 而不改变实际渲染根数。现改为基于当前周期有效根数 `Math.min(visibleBars, totalBars)` 实时计算缩放步长，支持一路下探放大至最少 `6` 根 K 线。
   - **光标锚定缩放（Cursor-Anchored Zoom）**：滚轮放大或缩小时，自动锚定鼠标指针所指的 K 线时间位置，并与左键拖拽平移（`panOffset`）边界严格同步。
   - **全周期历史种子自动补齐**：扩展 `BarHistorySeeder` 与存档加载补齐逻辑，对缺少高周期 K 线的标的自举生成约 120 根连贯的 `5m / 15m / 60m / 1d / 1w` 历史数据，开服即可顺滑浏览与缩放全周期图表。

### 2. 遗迹盲仓暗标竞拍附属（`blind/` · `module_shop_blind` `3.5.0 -> 3.6.0`，网络协议 `"7" -> "8"`）

1. **联机匹配与经济安全漏洞修复**（`BlindAuctionConfig.java`、`BlindVaultEngine.java`、`BlindRoom.java`）：
   - **关闭多人在线匹配倒计时自动补 AI**：新增配置项 `multiplayerAutoAiFill`（默认 `false`）。多人联机匹配队列等待超时后不再自动塞入 AI 开局，仅在真人席位满员或真人全员准备就绪后发车。
   - **彻底关闭 AI 对局发星币漏洞**：新增 `!room.practice && !room.hasAi()` 双重真实经济锁。单人/AI 演练局免收门票，全程采用虚拟演练筹码演示盈亏，**绝不调用模块商店真实星币入账接口**，且不写入全服真实成交历史榜。
2. **同类暗拍博弈玩法深化（参考《俱乐大玩家》核心博弈体验，原创实现）**（`BlindCatalog.java`、`BlindVaultEngine.java`、`BlindShopView.java`、`BlindVaultRouterView.java`、`zh_cn.json`、`en_us.json`）：
   - **新增第 5 战术道具「声呐探针（`SONAR`）」**：对选定仓格执行十字 5 格扫描揭示轮廓，并反馈该局部区域的模糊估值区间。
   - **6 大仓门封条主题（`VaultTheme`）与 4 套遗物共鸣溢价（`RelicSet`）**：开局公示仓门封条线索；同一盲仓内同套系遗物达到 `≥3` 件时触发 `+12%` 套组共鸣估值加成。
   - **历轮对手出价节奏复盘**：自动标注 `[观望]`、`[试探]`、`[猛抬逼秒]`、`[高位撤退]`、`[压价控盘]` 等博弈信号，强化心理揣摩。
   - **四流派性格 AI（仅单人演练局）**：划分为「稳健派」「狙击手」「做局客」「秒仓猎手」，出价严格基于当前公开揭示估值推算，绝不透视未揭示真实底牌。
   - **双语键值完整对齐**：`zh_cn.json` 与 `en_us.json` 严格保持 `242` 个键一一对应。
3. **Minecraft 1.21.1 原生短促交互点击音效**（新增 `BlindSound.java`，接入 `BlindShopView`、`BlindVaultRouterView`、`BlindAuctionState`、`BlindAuctionClient`）：
   - 对齐主模组 `module_shop` 的 `ClickSound` 音量区间（`0.24F ~ 0.50F`），全部使用 `SimpleSoundInstance.forUI` 触发原版单次短促触觉反馈：
     - 常规按钮/选区/切页：`UI_BUTTON_CLICK`
     - 报价数字键与增幅键：`BLOCK_BAMBOO_WOOD_BUTTON_CLICK_ON`（按数字键位附极轻微音高阶梯区分，清脆不吵）
     - 退格/清零与无效操作：`BLOCK_BAMBOO_WOOD_BUTTON_CLICK_OFF` / `BLOCK_NOTE_BLOCK_BASS`
     - 战术道具释放：`BLOCK_AMETHYST_BLOCK_CHIME`（扫描/声呐）、`ITEM_SPYGLASS_USE`（透视）、`BLOCK_ENCHANTMENT_TABLE_USE`（干扰）
     - 报价锁定、轮次推进与开仓结算：`BLOCK_COPPER_BULB_TURN_ON`、`BLOCK_NOTE_BLOCK_HAT`、`ENTITY_EXPERIENCE_ORB_PICKUP`（盈利）/ `BLOCK_ANVIL_LAND`（极轻音量提示流拍或亏损）
   - **严守克制设计原则**：零背景音乐（BGM）、零循环环境噪音、零怪异生物音效；服务端无客户端类引用，Dedicated Server 安全运行。

## 三、文件变更与备份对照清单（共 25 项源码/配置）

| 子工程 | 项目内相对路径 | 操作 | 原版备份文件链接 | 更新后文件链接 |
| :--- | :--- | :--- | :--- | :--- |
| `stock` | `src/main/java/cn/autoforged/module_shop_stock/market/model/PriceParams.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1VQYf6e66Tz3WnzAuumN4LBs2UWNHnjwG/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1QnVNSryHGPglJWdqfqAlsPMMYMR4MKIY/view?usp=drivesdk) |
| `stock` | `src/main/java/cn/autoforged/module_shop_stock/market/model/PriceModelV3.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1HI3AI59AZFdKwsAjKTOXZBId3OVxtNzZ/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1-3HanTIJh8Eev6rinXW04KpIInp089wW/view?usp=drivesdk) |
| `stock` | `src/main/java/cn/autoforged/module_shop_stock/StockMarketConfig.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1SG-Mu0NMoc8mqvbBdcjE3BErNOapVt21/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1URFq1ps4vImYBoMlbrF5YbePrkK2024E/view?usp=drivesdk) |
| `stock` | `src/main/java/cn/autoforged/module_shop_stock/market/bars/BarHistorySeeder.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1Q4HN-C8uAtOUb_ue9RVMOuJXIGl3lWLm/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1wTzfh2yUhqguVfR_FlbWXpYjDAwKxkLE/view?usp=drivesdk) |
| `stock` | `src/main/java/cn/autoforged/module_shop_stock/data/StockMarketSavedData.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1Edl2z4xeDzfwm3-FAM-cXnL3TdUclbZV/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1LktI9nXXYaaLpQk7z1uEWKhJn17abbYs/view?usp=drivesdk) |
| `stock` | `src/main/java/cn/autoforged/module_shop_stock/client/StockMarketState.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1_Av40RsBr40JrE5iqBp5eWiOMcCg0pUk/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1eCa3Y0fqzWuVbHvXFzumn2eXPQQH0QlV/view?usp=drivesdk) |
| `stock` | `src/main/java/cn/autoforged/module_shop_stock/client/StockMarketClient.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1y8mz1HIvuxY6jIO_6s0b0q-y_elLl5f7/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1OPXc7p5ed6lYdpoDHF4MSs0EQXcYl8OM/view?usp=drivesdk) |
| `stock` | `src/main/java/cn/autoforged/module_shop_stock/client/StockRealTour.java` | 新增文件 | —（回滚时移除） | [新增文件](https://drive.google.com/file/d/1mEVQRUYkwlAylHYRePEnvbFi8P8f6y7U/view?usp=drivesdk) |
| `stock` | `src/main/java/cn/autoforged/module_shop_stock/client/ChartRenderer.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1SkZ4bo4Y-m6S1l_fm7aRPbvNChUlr-Gy/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1n9LEm6JN9DLkaCP0Bh6L7Lcvt5c7B4A4/view?usp=drivesdk) |
| `stock` | `src/main/java/cn/autoforged/module_shop_stock/client/view/QuoteView.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1YrE0yVUuSL0SCcbtofQs_LrkOGXv5u1i/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1A4VpiEmsvw8H3Nctav6DytAF0pSBBvpw/view?usp=drivesdk) |
| `stock` | `src/test/java/cn/autoforged/module_shop_stock/market/model/PriceModelV3Test.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1xzDxzOqnJVm_LRqxQ57KwH11w5XP-fMR/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1XsRHZsuQEMZtV26GEIhUBlvB7u4Nazdt/view?usp=drivesdk) |
| `blind` | `gradle.properties` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1jhj7A1Ow5p6J_yaCFThFQKuG4N1SNfne/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1rkJVgystDybr3GOU2VtQ3bFE84Ibar--/view?usp=drivesdk) |
| `blind` | `src/main/java/com/atoms/Bshop/blind/BlindAuctionConfig.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1PbYo8h81hKOHd-DY9LORg0cScTQcNPff/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1Uio5pV72zTlaHYxP3Z-ax3WmPuECLGZO/view?usp=drivesdk) |
| `blind` | `src/main/java/com/atoms/Bshop/blind/network/BlindAuctionNetwork.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1Em_R3BJWHsywsOTMhB05Wli8f8RvAGHG/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1dbFbq_BFE1opFoZgny5Br7s-aggCye4M/view?usp=drivesdk) |
| `blind` | `src/main/java/com/atoms/Bshop/blind/auction/BlindCatalog.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1djYl54hgG5x2G21UTvWSRMq82lD5wQKl/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1gF0HZThzFrZkmDBEH-NhMHMbdRXfkXZC/view?usp=drivesdk) |
| `blind` | `src/main/java/com/atoms/Bshop/blind/server/BlindRoom.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1dJA8Y68R66lPFW4XfEtZYipwxjxw1TFl/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1xScziGmZXr_X1RR4MWEuwYKFz2CHimgS/view?usp=drivesdk) |
| `blind` | `src/main/java/com/atoms/Bshop/blind/server/BlindVaultEngine.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1g1b8Dl6gB4BAwv3PCNTuw0Tr2dNH4y5_/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1QOEya6_ZtLpVmR2Ol9AGGurkDsK1VNCD/view?usp=drivesdk) |
| `blind` | `src/main/java/com/atoms/Bshop/blind/client/BlindAuctionState.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1p46rMQpY7n6viFiwaiXdOwm82q8DgB86/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1bjSiMm7LxxPQfwcz7cO5GhP-z44Uqbyx/view?usp=drivesdk) |
| `blind` | `src/main/java/com/atoms/Bshop/blind/client/BlindAuctionClient.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1qtLcgkqUQalS6w-DTAAEwi_9Lx0qcokM/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1gFavqx2muTpf9x6ecBtzYcMEGe6TNWvY/view?usp=drivesdk) |
| `blind` | `src/main/java/com/atoms/Bshop/blind/client/BlindRealTour.java` | 新增文件 | —（回滚时移除） | [新增文件](https://drive.google.com/file/d/1pgWgM52R9WL9JfqROkMFFZlD2BDXdPeC/view?usp=drivesdk) |
| `blind` | `src/main/java/com/atoms/Bshop/blind/client/ui/BlindSound.java` | 新增文件 | —（回滚时移除） | [新增文件](https://drive.google.com/file/d/17UzIcKPQuA5KNNe-fc5BcPEIKXrGFZy9/view?usp=drivesdk) |
| `blind` | `src/main/java/com/atoms/Bshop/blind/client/view/BlindShopView.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1b1-8qFB0rAhHVrzf7PY4kcShoIZ0msje/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1HUCCB80fJyEhZHYDfN-g3sDb7aBlO_PD/view?usp=drivesdk) |
| `blind` | `src/main/java/com/atoms/Bshop/blind/client/view/BlindVaultRouterView.java` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1QFj9mZSM3xpRuH_5cbyPdlCvg_F-0yhb/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1UPxtA6zTh2Tb0kfHC6KMxkLBFf_PtHtl/view?usp=drivesdk) |
| `blind` | `src/main/resources/assets/module_shop_blind/lang/zh_cn.json` | 备份并更新 | [原版备份](https://drive.google.com/file/d/1uv6tHH0OUjN70xiYQlwZgFL3bU48uD4s/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/1TS0xZ9PENxEUnNVkr9_2_H1i6RcTCAuC/view?usp=drivesdk) |
| `blind` | `src/main/resources/assets/module_shop_blind/lang/en_us.json` | 备份并更新 | [原版备份](https://drive.google.com/file/d/13wUtg7_Co4jtpvmiKOUBrpjHtkOnWgyw/view?usp=drivesdk) | [新版文件](https://drive.google.com/file/d/14uTrkxheCSPjWLqXX-DK9-dNE_XSwGzV/view?usp=drivesdk) |

## 四、本地编译与实机验证方式

待云端硬盘桌面客户端同步完成后，在 `E:\心自安然项目\Module-Shop-Addons` 目录下执行：

```powershell
# 单独构建两个子工程并跑通 JUnit 单元测试
cd E:\心自安然项目\Module-Shop-Addons\stock
.\gradlew.bat build "-Pmodule_shop_root=../../Module-Shop"

cd ..\blind
.\gradlew.bat build "-Pmodule_shop_root=../../Module-Shop"
```

如需一键双模组联调并调用原生 OpenGL `Screenshot.grab` 自动抓拍实机画面，可在 `Module-Shop-Addons` 根目录直接运行：

```powershell
.\run-real-ingame-shots.ps1 -ModuleShopRoot "..\Module-Shop"
```

*注：盲仓网络通道版本已升至 `"8"`，正式服部署时需客户端与服务端同步更新 `module_shop_blind-3.6.0.jar`。本文档与上述代码更新由 **Gemini** 生成并同步。*
