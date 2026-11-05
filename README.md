# module-shop: Red envelope · 拼手气红包

**模块商店（Module Shop）的附属模组**：在商店里发拼手气 / 均分红包，红包出现在聊天栏，点击或发口令领取。

| 项 | 值 |
| --- | --- |
| 显示名 | module-shop: Red envelope |
| modid | `module_shop_redpacket`（NeoForge 的 modid 只能是小写字母、数字、下划线） |
| 版本 | 1.0.0 |
| 游戏 / 加载器 | Minecraft 1.21.1 · NeoForge 21.1.x |
| 前置 | **模块商店 1.0.3+**（两端）· **AtomLib 0.1.0-beta**（仅客户端，模块商店本来就需要） |
| 许可 | MIT |

装上之后，商店主界面左侧功能区会多出一个 **「红包」** 条目。点它，发红包的表单直接显示在商店右侧内容区，
和其它模块一样用 AtomLib 绘制（红色信封头、分段按钮、Skija 输入框）。AtomLib 不可用（非 Windows）时改为弹出原版界面。

## 安装

| 位置 | 放进 `mods/` 的文件 |
| --- | --- |
| 服务端 | `module_shop-1.0.3.jar` + `module_shop_redpacket-1.0.0.jar`（服务端**不需要** AtomLib） |
| 客户端 | 上面两个 + `atomlib-1.21.1-neoforge-0.1.0-beta.jar` |

两端都要装红包附属（界面在客户端，红包逻辑在服务端）。

## 玩法

1. 打开商店 → 左侧点 **红包** → 填写：
   - **红包个数**、**总金额**
   - **拼手气红包**（金额随机）或 **普通红包**（均分）
   - **口令**（选填）：填了以后，玩家要在聊天栏发送这句口令才能领
   - **祝福语**（选填，默认「恭喜发财，大吉大利」）
2. 点 **塞钱进红包**：从余额里扣掉总金额，聊天栏里所有人都会看到红包：

   ```
   [红包] Steve 发了一个拼手气红包 「恭喜发财，大吉大利」
     共 10 个 · 总额 1000 星币
     [ 领取红包 ]  [ 详情 ]
   ```

3. **领取**
   - 普通红包：点 **[ 领取红包 ]** 即可；
   - 口令红包：在聊天栏发送口令（点 **[ 发送口令领取 ]** 会自动把口令填进聊天框）。
   - 领到多少只有**自己**能看到：`你领取了 Steve 的红包，获得 123 星币`。
4. **详情**：点 **[ 详情 ]** 打开详情界面（AtomLib 绘制），看到发送者、祝福语、总额、已领 x/y 个、
   每个人领了多少 / 什么时候领的、自己领到多少，领完后标出 **手气最佳**。
5. 红包被领完时聊天栏会公告用时和手气最佳；**过期没领完的部分自动退回**给发送者。

不想开界面也可以用指令（无口令）：

```
/redpacket send <个数> <总额> lucky|even [祝福语]
```

## 配置（`config/module_shop_redpacket-server.toml`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `maxCount` | 100 | 单个红包最多几份 |
| `minTotal` / `maxTotal` | 1 / 1000000 | 总额范围 |
| `expireMinutes` | 1440 | 多久没领完就过期退回 |
| `sendCooldownSeconds` | 10 | 同一玩家两次发红包的间隔 |
| `allowSelfClaim` | true | 能否领自己的红包 |
| `announceFinished` | true | 领完时是否公告（含手气最佳） |
| `keepHistoryHours` | 72 | 结束后的详情保留多久 |

## 安全性

- 钱全部走模块商店公共 API：发出用 `economy().take`（余额不足整笔失败），领取 / 退款用 `economy().add`；
  余额已到上限（`LIMIT_EXCEEDED`）时改用 `mail().sendMoneyMail` 发金钱邮件，钱不会卡在红包里。
  每笔收支都会出现在玩家账单里（API 自动记账）。
- 金额在发出时就拆好并写进存档（`data/module_shop_redpacket.dat`），所有份额之和恒等于总额；重启服务器后照样能领、照样到期退款。
- 每人每个红包只能领一次；校验全部在服务端。

## 使用的库

| 库 | 版本 | 用途 | 依赖方式 | 许可 |
| --- | --- | --- | --- | --- |
| NeoForge | 21.1.248 | 模组加载、事件、网络包（`CustomPacketPayload`）、配置（`ModConfigSpec`） | 运行环境 | LGPL-2.1 |
| Minecraft | 1.21.1 | 指令（Brigadier）、存档（`SavedData`）、聊天组件与点击事件 | 运行环境 | — |
| **模块商店公共 API** | 1.1.0（模块商店 1.0.3） | 动钱：`economy().take / add / balance / balanceView`；满额转邮件：`mail().sendMoneyMail`；侧栏页面：`api.client.ShopModules / AddonPage / ShopTheme` | `compileOnly module_shop-<版本>-api.jar`（只含公共 API 与 UI 套件） | MIT |
| **模块商店 UI 套件** | 随模块商店 | `Draw / Text / SkiaFrame / Button / SkiaTextField / Motion / ScrollController`：和内置页面同一套控件 | 同上（打包在 API jar 里） | MIT |
| **AtomLib** | 0.1.0-beta（beta3） | 客户端 Skija 渲染桥、字体（Noto Sans SC）与文字排版 | `compileOnly`，运行时由客户端安装的 AtomLib 提供 | Apache-2.0 |
| Skija | 0.143.18 | `Canvas` 绘制（运行时随 AtomLib 内置，只带 Windows 原生库） | 编译期作为 AtomLib 的传递依赖从 Maven Central 取 | Apache-2.0 |
| ModDevGradle | 2.0.144 | 构建 | Gradle 插件 | LGPL-2.1 |
| Parchment | 2024.11.17 | 参数名映射（只影响开发） | Gradle | CC0 |

红包本身没有打包任何第三方库，jar 里只有自己的类与语言文件。

## 源码结构

```
src/main/java/cn/autoforged/module_shop_redpacket/
  RedPacketMod.java            入口：注册配置 / 网络 / 指令 / 聊天口令 / tick；检查公共 API ≥ 1.1
  RedPacketManager.java        服务端逻辑：发、领、口令、详情、过期退款（只调用 ModuleShopApi）
  RedPacketConfig.java         服务端配置
  data/RedPacket*.java         红包数据、存档（SavedData）、金额拆分算法
  network/RedPacketNetwork.java  两个 NBT 包（C2S 请求 / S2C 回包），不占用模块商店的通道
  client/
    RedPacketClient.java       ShopModules.registerPage(...) 挂进侧栏；处理回包
    RedPacketPage.java         内容区页面（AtomLib）
    RedPacketDetailAtomScreen  详情界面（AtomLib）
    RedPacketSendScreen / RedPacketDetailScreen   无 AtomLib 时的原版回退界面
    RedPacketState.java        两套界面共用的表单状态
src/main/resources/assets/module_shop_redpacket/lang/   zh_cn / en_us
```

## 构建

先在仓库根目录构建模块商店（得到 `build/libs/module_shop-1.0.3.jar` 和公共 API jar `module_shop-1.0.3-api.jar`），然后：

```bash
cd addons/red-packet
./gradlew build          # 产物：build/libs/module_shop_redpacket-1.0.0.jar
```

附属**只对公共 API jar（含 UI 套件）+ AtomLib / Skija 编译**，这些 jar 的路径由 `build.gradle` 从仓库根目录的
`gradle.properties` 自动推出（主体版本号、AtomLib 版本）——**主体升级后直接重新构建即可，不用改任何配置**。
AtomLib 从主体下载好的本地 Maven 仓库（`.atomlib/maven`）取，Skija 作为它的传递依赖从 Maven Central 取。
需要对别处的 jar 编译时用 `-Pmodule_shop_api_jar=...`（以及 `module_shop_jar` / `atomlib_maven`）覆盖。

### 与主体版本的兼容

- 依赖声明是 `module_shop [1.0.3,)`：之后的模块商店版本都能装；
- 只用公共 API 与 UI 套件，模块商店承诺这两部分在 1.x 内只增不改 → **已发布的红包 jar 在新版本主体上照样能用，不用跟着更新**；
- 启动时检查公共 API 主版本是 1、次版本 ≥ 1（API 出现不兼容的 2.x 时会明确报错，而不是运行中出问题）；
- 万一红包页面在某个新版本上出错，商店会停用这个页面并自动改用原版的发红包界面，商店和其它模块不受影响。

## 给开发者

这个附属同时是「侧栏扩展点」的完整示例：

- `client/RedPacketClient.java`：`ShopModules.registerPage(...)` 把「红包」作为内容区页面挂进侧栏；
- `client/RedPacketPage.java`：内容区页面（`AddonPage`），用模块商店的 UI 套件与 `ShopTheme` 绘制；
- `client/RedPacketDetailAtomScreen.java`：AtomLib 版详情界面；`RedPacketSendScreen` / `RedPacketDetailScreen` 是无 AtomLib 时的回退；
- `RedPacketManager.java`：只用 `ModuleShopApi`（`economy()` / `mail()`）动钱；
- `RedPacketMod.java`：启动时用 `ApiVersion.describe()` 检查公共 API ≥ 1.1；
- `network/RedPacketNetwork.java`：附属自己的网络包（不占用模块商店的通道）。
