# module-shop: Stock market · 虚拟股市

模块商店的附属模组：在商店左侧功能区增加「股市」页面。

| 项 | 值 |
| --- | --- |
| modid | `module_shop_stock` |
| 版本 | 2.2.0 |
| 前置 | 模块商店 1.0.3+（两端）· AtomLib 0.1.0-beta 起（仅客户端，已验证 0.4.0） |
| 许可 | MIT |

## 功能

- 股票定义存在存档里，管理员在商店界面里发行新股、改名、调参、停牌、下架与强制清算。
- 订单簿撮合：市价 / 限价委托、手续费、做空（按限价冻结保证金）。
- 行情用 OHLCV 柱记录：K 线、成交量、指数、自选；底栏新闻滚动播报。
- 资金全部经模块商店公共 `EconomyApi` 结算；入账失败会记进存档的 `pendingPayouts`，每 tick 重试。

## 存档兼容

2.2 存档带 `format="zip-2.2"` 标记。没有该标记的旧存档（2.0.x）首次启动前会备份为
`module_shop_stock.dat.pre-2.2.bak` 再迁移。

## 构建

```bash
./gradlew build -Pmodule_shop_root=<模块商店主仓库目录>   # 产物：build/libs/module_shop_stock-2.2.0.jar
```

开发记录见 `docs/`。
