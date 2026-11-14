# 模块商店附属合集（Module Shop Addons · Forge 1.20.1）

[模块商店（Module Shop）](https://github.com/Ar-Zhe/Module-Shop) 的 **Minecraft 1.20.1 · Forge 47.4.9**（ForgeGradle `6.0.24` / Java 17）移植分支（`forge-1.20.1`）。
每个目录都是一个**独立的 Gradle 项目**：单独构建、单独出 jar、各自的版本号，按需安装。

| 目录 | 启动器展示名 (`displayName`) | modid | 版本 | 主包 JAR 文件名 | 说明 |
| --- | --- | --- | --- | --- | --- |
| [`redpacket/`](redpacket/) | `Module-Shop Red Packet 1.1.0 for Forge 1.20.1` | `module_shop_redpacket` | 1.1.0 | `Module-Shop-RedPacket-1.1.0-for-Forge-1.20.1.jar` | 拼手气 / 均分红包，聊天栏领取、口令红包 |
| [`stock/`](stock/) | `Module-Shop Stock Market 2.4.0 for Forge 1.20.1` | `module_shop_stock` | 2.4.0 | `Module-Shop-Stock-2.4.0-for-Forge-1.20.1.jar` | 虚拟股市：订单簿、限价 / 市价、做空、K 线与新闻 |
| [`blind/`](blind/) | `Module-Shop Blind Vault 3.6.0 for Forge 1.20.1` | `module_shop_blind` | 3.6.0 | `Module-Shop-Blind-3.6.0-for-Forge-1.20.1.jar` | 遗迹盲仓暗标竞拍：多房间匹配、鉴定师与道具、AI 补位 |
| [`quest/`](quest/) | `Module-Shop Quest & Pass 1.0.0 for Forge 1.20.1` | `module_shop_quest` | 1.0.0 | `Module-Shop-Quest-1.0.0-for-Forge-1.20.1.jar` | 双层赛季通行证、每日/每周任务、防挂机在线奖励与游戏内管理面板 |

## 前置与兼容

| 前置 | 范围 | 说明 |
| --- | --- | --- |
| Forge | `[47,)`（构建用 `1.20.1-47.4.9`） | Minecraft `1.20.1` |
| 模块商店 | `[1.1.0,)`（构建用 `module_shop-1.1.0-forge-1.20.1-api.jar`） | 两端都要装；附属只调用公共 API（同一主版本只增不改） |
| AtomLib | `[0.1.0-beta,)`（构建用 `atomlib-1.20.1-forge-0.4.0.jar`） | 仅客户端；各子项目 `libs/atomlib-1.20.1-forge-0.4.0.jar` 供离线校验与解包（已由 `.gitignore` 忽略，不入库） |

## 离线编译与单测

需要 **JDK 17**，并提前构建好同级 `E:\心自安然项目\Module-Shop-1.20.1`（提供 `build/libs/module_shop-1.1.0-forge-1.20.1-api.jar`），各附属 `libs/` 下需放置 `atomlib-1.20.1-forge-0.4.0.jar`（SHA-256 校验与 Skija 解包已内联在各 `build.gradle` 中）：

```powershell
# PowerShell（以 blind / redpacket / stock / quest 依次构建）
Set-Location "E:\心自安然项目\Module-Shop-Addons-1.20.1\blind"
.\gradlew.bat build "-Dorg.gradle.java.home=C:/Users/RuriSunny/AppData/Roaming/.minecraft/runtime/java-runtime-beta" "-Pmodule_shop_root=E:/心自安然项目/Module-Shop-1.20.1" --offline --no-daemon

Set-Location "E:\心自安然项目\Module-Shop-Addons-1.20.1\redpacket"
.\gradlew.bat build "-Dorg.gradle.java.home=C:/Users/RuriSunny/AppData/Roaming/.minecraft/runtime/java-runtime-beta" "-Pmodule_shop_root=E:/心自安然项目/Module-Shop-1.20.1" --offline --no-daemon

Set-Location "E:\心自安然项目\Module-Shop-Addons-1.20.1\stock"
.\gradlew.bat test build "-Dorg.gradle.java.home=C:/Users/RuriSunny/AppData/Roaming/.minecraft/runtime/java-runtime-beta" "-Pmodule_shop_root=E:/心自安然项目/Module-Shop-1.20.1" --offline --no-daemon

Set-Location "E:\心自安然项目\Module-Shop-Addons-1.20.1\quest"
.\gradlew.bat test build "-Dorg.gradle.java.home=C:/Users/RuriSunny/AppData/Roaming/.minecraft/runtime/java-runtime-beta" "-Pmodule_shop_root=E:/心自安然项目/Module-Shop-1.20.1" --offline --no-daemon
```

产物位于各自子项目的 `build/libs/`。

## 许可

全部附属均为 MIT 许可，作者 ChatMiaoPT-Meow（见 [LICENSE](LICENSE)）。
