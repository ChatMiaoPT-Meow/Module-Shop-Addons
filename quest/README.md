# 星途纪行（Module Shop: Quest & Pass · `module_shop_quest`）

基于主模组 [Module-Shop](https://drive.google.com/drive/folders/1QGnciUgHdODqfP-UlqXNr8UOZ5WyfktE) `1.1.0`（公共 API `1.3.0`，参见 [公共API-使用文档.md](https://drive.google.com/file/d/14AhqFUnBlXvvanzHL3BjH0F9l3W0XzBH/view?usp=drivesdk)）与客户端前置库 [AtomLib](https://drive.google.com/drive/folders/1DVH8cmg-R2MriNdjJUBvmhxS5KoMuW6Y) `0.4.0`（参见 [README.md](https://drive.google.com/file/d/1xlXIU2Inh6mR_ArSvhmeRipm5q4tCiCB/view?usp=drivesdk)）构建的高完成度附属模组，完整实现 **双层积分赛季通行证（免费基础线 + 星币解锁进阶线）+ 每日任务 + 每周任务 + 防挂机在线时长奖励**，并严格遵循 [每日任务通行证附属方案.md](https://drive.google.com/file/d/1C43abnDTY0YE8wH3MkXXkEb5NQVr287G/view?usp=drivesdk)、[系统玩法方案-2026-11-11.md](https://drive.google.com/file/d/1FP0EOZ7VQSRm1wX1RadrNOSI3vf3-uGy/view?usp=drivesdk) 及 UI v2 规范（[addon-brief.md](https://drive.google.com/file/d/1WjR_M6x39Nw1OTe-bnbWMFEloIcF78wq/view?usp=drivesdk)、[UPDATE_NOTES_20261111.md](https://drive.google.com/file/d/1VkD_2ENeHcDLZ5NfPk0DX4QHSVaFvTDz/view?usp=drivesdk)）。

## 1. 核心架构与工程亮点

* **双层积分赛季通行证 & 星币买断解锁**：
  * **免费基础线（`pass`）** 与 **星辉进阶线（`pass-extra`）** 并行推进；玩家在界面点击解锁并二次确认后，服务端主线程调用 [EconomyApi.java](https://drive.google.com/file/d/1ay2cRnn4eytGWSAteJdjBrs1oSqYG6T9/view?usp=drivesdk) 的 `take(uuid, cost, reason)` 扣除可配置的**星币**（支持附赠立得积分，且中途解锁立即回溯开放当季全部已达等级的进阶奖励）。
  * **积分与星币双轨物理隔离**：任务与在线奖励同步产出星币与纪行专属积分；积分仅保存在本模组独立存档 `world/data/module_shop_quest.dat` 中，绝不写入主模组钱包，支持季末 **7 天补领宽限期** 与 **比例积分结转（带硬上限）**。
* **双重幂等防刷发奖引擎**：
  * 所有星币与物品发放统一经过「本附属 `claimedRequestIds` 第一幂等账本 + [RewardApi.java](https://drive.google.com/file/d/1JPOJ-4OHLYFOIJLrWTQwvLrJwJEW3jsg/view?usp=drivesdk) `grantOnce` 第二道防线」，键格式严格采用 `quest:<seasonId>:<uuid>:<kind>:<periodKey>:<id>`；背包满或钱包满上限时由主模组自动转为商店邮件并记入资金流水。
* **每日任务 & 每周任务（软联动 + 原版无需改主包即插即用）**：
  * 按可配置时区（默认 `Asia/Shanghai`）自动轮转并执行权重抽池，保证模组玩法任务占比 `>= 60%`，未安装的软联动模组（[Module-Shop-Addons](https://drive.google.com/drive/folders/1tYYiEzkc01RcgU4Ng7TGzmN15lqjAkJB) 中的 `module_shop_stock`、`module_shop_blind` 及 [Lottery-1.21.1](https://drive.google.com/drive/folders/18K0IEBc9OaUNrWCZ9vVJOwPxir4Wm3io) `lottery`）自动剔除或中途免费替换，绝不卡板。
  * 同时订阅 [ModuleShopApi.java](https://drive.google.com/file/d/1DCG2yfCSunWa5KUyawXdQ7B3YKb5XShG/view?usp=drivesdk) `events()` 与基于 [HistoryApi.java](https://drive.google.com/file/d/1jr7z4ooikIEcockTCfTm-Vj-eVErDwcM/view?usp=drivesdk) 的只读流水增量去重桥接——**无需修改现有 `module_shop-1.1.0.jar`，玩家在商店 UI 内的日常购买、购物城采购、收购出售、摊位购买、拍卖出价、股市现货买卖与盲仓结算即可开箱计入进度且绝不双计**。
* **服务端权威防挂机在线时长系统**：
  * 每 20 tick 联合检测 `getLastActionTime()`、位移、视角转动与商店 UI 交互心跳，默认闲置 30 秒自动暂停计时且不清空已累计分钟，支持每日活跃三档（15 / 30 / 60 分钟）与每周累计三档（120 / 240 / 360 分钟）。
* **纯正 AtomLib UI v2 视觉与原版双模回退**：
  * 客户端通过 [ShopModules.java](https://drive.google.com/file/d/16Jl43S-J16jhCCvH5tGKpba0mb8-SKJQ/view?usp=drivesdk) `registerPage("module_shop_quest", ...)` 挂载，使用 [V2.java](https://drive.google.com/file/d/1IVw0a1lMXjkpztjqPBNfympfuEQfNoNP/view?usp=drivesdk)、[ListTable.java](https://drive.google.com/file/d/1ZXmbkUru70_Sfdjm5lAfX0VhDurU8bRD/view?usp=drivesdk)、[ItemOverlay.java](https://drive.google.com/file/d/1xBi7Ws-BQkz8bnkV41I8yS-SOc4MXonB/view?usp=drivesdk) 与 [ShopTheme.java](https://drive.google.com/file/d/1xHcN8F2rL_YjkCPpJS9UfoAcDZbBL9kv/view?usp=drivesdk) 实时适配全部 7 套商店主题皮肤（支持悬停查看物品附魔属性与 JEI 配方），并在非 Windows 环境下自动回退至 `QuestFallbackScreen`。

## 2. 编译与安装步骤

工程目录 `quest/` 已内置 [module_shop-1.1.0-api.jar](https://drive.google.com/file/d/1SJrLkyoCh3N-sS8g_OfNnKzoEKQVUGBH/view?usp=drivesdk) 与完整的 Gradle Wrapper（且专门适配了 Windows 中文目录路径下的 JUnit 5 测试运行）：

1. 将解压后的 `quest/` 文件夹放入本地 `Module-Shop-Addons/quest`（或任意独立目录）。
2. 在 `quest/` 目录下执行构建：
   ```powershell
   .\gradlew.bat build
   ```
   如需直接指向本地主模组仓库构建产物，也可附加 `-Pmodule_shop_root=..\Module-Shop`。
3. 构建完成后取出 `build/libs/module_shop_quest-1.0.0.jar`，与 `module_shop-1.1.0.jar`（客户端另需安装 `atomlib-1.21.1-neoforge-0.4.0.jar`）一同放入 `mods/` 目录即可启动。

## 3. 快速调整奖励与积分规则指南（零改代码热生效）

首次启动服务器会自动在 `config/` 下生成两层配置文件（样板见工程内 `config_examples/module_shop_quest/`）：

| 配置文件路径 | 用途说明 | 生效方式 |
| :--- | :--- | :--- |
| `config/module_shop_quest-common.toml` | 总开关、侧栏自定义名称、`zoneId` 时区、日/周重置钟点、AFK 闲置秒数、每日/每周抽题数量与模组占比 | NeoForge 自动重载 |
| `config/module_shop_quest/season_pass.json` | 赛季 ID/名称、持续周数、**星币解锁付费线价格与附赠积分**、**`FORMULA` 快速积分公式曲线**、默认奖励与逐级自定义多物品/附魔/星币奖励 | 执行 `/quest reload` 或点界面右上角「热重载配置」立即生效 |
| `config/module_shop_quest/quest_pools.json` | `dailyPool` 与 `weeklyPool` 任务权重、过滤词、目标次数、星币+积分+物品奖励 | 执行 `/quest reload` 立即生效（未领任务奖励即时刷新，已抽进度不丢） |
| `config/module_shop_quest/online_rewards.json` | 每日活跃分钟档位与每周累计活跃分钟档位及对应奖励 | 执行 `/quest reload` 立即生效 |

### 游戏内 OP 极速调参指令（无需切出游戏改文件）

管理员（OP 权限等级 `>= 2`）可直接在游戏内使用 `/quest`（或别名 `/shopquest`）极速改表并实时热同步给全服在线玩家：

* `/quest reload` — 热重载全部 JSON 奖励与积分配置（OP 也可直接点击商店纪行页右上角的 **「热重载配置」** 按钮）。
* `/quest admin setunlockcost <星币价格> <解锁立得积分>` — 一键修改星币解锁付费积分线的价格与附赠积分并落盘。
* `/quest admin setcurve <最大等级> <基础每级积分> <每N级阶梯> <阶梯增量积分>` — 一键调整满级上限与升级积分曲线公式。
* `/quest admin setreward <等级> <free|premium> <星币数量> <true|false是否读取主手物品>` — **手持附魔/改名物品执行此命令**，即可把主手物品模板 + 星币直接写入对应等级奖励并热生效！
* `/quest admin points <add|set> <玩家> <积分数>` — 增减或设定指定玩家的当季纪行积分。
* `/quest admin unlockpass <玩家> <true|false>` — 为指定玩家解锁或重置当季进阶积分线。
* `/quest admin reset <玩家> <daily|weekly|online>` — 重置指定玩家的每日任务、每周任务或在线时长。
* `/quest admin nextseason <新赛季ID> <新赛季显示名>` — 开启新赛季（自动归档上赛季 7 天补领窗口并按比例结转积分）。

## 4. 外部模组 / KubeJS 联动接口

其他模组或脚本可直接调用 `cn.autoforged.module_shop_quest.api.QuestIntegrationApi` 推进自定义任务或读取玩家通行证状态：

```java
// 推进指定玩家的抽卡/股市/盲仓或自定义事件任务进度（服务端主线程调用）
QuestIntegrationApi.recordAction(player.getUUID(), "LOTTERY_PULL", "standard_pool", 1);
QuestIntegrationApi.recordAction(player.getUUID(), "CUSTOM_EVENT", "boss_raid_win", 1);

// 查询玩家当前赛季通行证等级、累计积分与进阶线解锁状态
int level = QuestIntegrationApi.passLevel(player.getUUID());
long points = QuestIntegrationApi.seasonPoints(player.getUUID());
boolean vip = QuestIntegrationApi.isPremiumUnlocked(player.getUUID());
```
