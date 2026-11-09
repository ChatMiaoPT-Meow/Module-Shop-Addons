# 模块商店附属合集（Module Shop Addons）

[模块商店（Module Shop）](https://github.com/Ar-Zhe/Module-Shop) 的附属模组，Minecraft 1.21.1 · NeoForge 21.1.x。
每个目录都是一个**独立的 Gradle 项目**：单独构建、单独出 jar、各自的版本号，按需安装。

| 目录 | 模组 | modid | 版本 | 说明 |
| --- | --- | --- | --- | --- |
| [`redpacket/`](redpacket/) | module-shop: Red envelope | `module_shop_redpacket` | 1.0.1 | 拼手气 / 均分红包，聊天栏领取、口令红包 |
| [`stock/`](stock/) | module-shop: Stock market | `module_shop_stock` | 2.2.0 | 虚拟股市：订单簿、限价 / 市价、做空、K 线与新闻 |
| [`blind/`](blind/) | Module Shop Blind Vault | `module_shop_blind` | 3.3.0 | 遗迹盲仓暗标竞拍：多房间匹配、鉴定师与道具、AI 补位 |

## 前置与兼容

| 前置 | 范围 | 说明 |
| --- | --- | --- |
| 模块商店 | `[1.0.3,)`，构建用 1.0.6 | 两端都要装；附属只调用公共 API（同一主版本只增不改） |
| AtomLib | `[0.1.0-beta,)`，构建用 0.4.0 | 仅客户端；附属不直接调用 AtomLib，统一经过模块商店的 UI 套件 |

**前置库更新的影响**：
- 依赖范围都是开区间，模块商店 / AtomLib 出新版不需要重新发布附属。
- 附属页面由模块商店的 `AddonPageAdapter` 托管：附属代码抛异常或因 API 变化出现 `LinkageError` 时，只停用该页面，不会让商店崩溃。
- 模块商店 1.0.6 起，AtomLib 运行中不兼容会自动关掉 Skija 绘制、退回旧版界面；附属都带有原版回退界面。

## 构建

需要 JDK 21，并准备一份已经构建好的模块商店（提供 `module_shop-<版本>-api.jar`）：

```bash
# 假设模块商店主仓库克隆在本仓库旁边：../Module-Shop，并且已执行过 ./gradlew build
cd stock    && ./gradlew build -Pmodule_shop_root=../../Module-Shop
cd ../blind && ./gradlew build -Pmodule_shop_root=../../Module-Shop
cd ../redpacket && ./gradlew build -Pmodule_shop_root=../../Module-Shop
# 产物在各自的 build/libs/
```

也可以把 `module_shop-1.0.6-api.jar` 放进各项目的 `libs/`，或用 `-Pmodule_shop_api_jar=<文件>` 直接指定。
首次构建会把 AtomLib 发布包下载到各项目的 `.atomlib/` 并做 SHA-256 校验（版本与校验值见各自的 `gradle.properties`）。

## 许可

全部附属均为 MIT 许可，作者 ChatMiaoPT-Meow（见 [LICENSE](LICENSE)）。
