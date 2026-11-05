# 模块商店附属 · 拼手气红包

装上之后，商店主界面左侧功能区会多出一个 **「红包」** 条目。

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
4. **详情**：点 **[ 详情 ]** 打开详情界面，看到发送者、祝福语、总额、已领 x/y 个、
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

## 构建

先在仓库根目录构建模块商店（得到 `build/libs/module_shop-1.0.3.jar` 和公共 API jar `module_shop-1.0.3-api.jar`），然后：

```bash
cd addons/red-packet
./gradlew build          # 产物：build/libs/module_shop_redpacket-1.0.0.jar
```

附属**只对公共 API jar 编译**（`gradle.properties` 的 `module_shop_api_jar`），代码里引用不到模块商店的任何内部类；
完整 jar（`module_shop_jar`）只用于 `runClient` / `runServer` 开发运行。
服务端和客户端都要装（需要模块商店 **1.0.3+**）。

## 给开发者

这个附属同时是「侧栏扩展点」的完整示例：

- `client/RedPacketClient.java`：`ShopModules.register(...)` 把「红包」挂进侧栏；
- `RedPacketManager.java`：只用 `ModuleShopApi`（`economy()` / `mail()`）动钱；
- `RedPacketMod.java`：启动时用 `ApiVersion.describe()` 检查公共 API ≥ 1.1；
- `network/RedPacketNetwork.java`：附属自己的网络包（不占用模块商店的通道）。
