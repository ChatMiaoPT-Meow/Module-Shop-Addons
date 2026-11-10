package cn.autoforged.module_shop_stock.server;

import cn.autoforged.module_shop_stock.StockMarketConfig;
import cn.autoforged.module_shop_stock.data.StockMarketSavedData;
import cn.autoforged.module_shop_stock.market.AuditEntry;
import cn.autoforged.module_shop_stock.market.Quote;
import cn.autoforged.module_shop_stock.market.StockDef;
import cn.autoforged.module_shop_stock.market.model.NewsModel;
import cn.autoforged.module_shop_stock.market.model.PriceModelV3;
import cn.autoforged.module_shop_stock.market.model.PriceParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 游戏时间驱动的自动新闻播报系统（内置 120 条行业、个股与宏观突发事件库）。
 */
public final class NewsEngine {
    private static final Logger LOG = LoggerFactory.getLogger("module_shop_stock/news");
    private static final Random RANDOM = new Random();
    /** v3 模板缩放后的单条幅度封顶（%）。 */
    private static final int TEMPLATE_SCALED_CAP = 8;

    private record Template(String match, boolean positive, int minPct, int maxPct, String headline) {
    }

    private static final List<Template> TEMPLATES = List.of(
            // ==================== 1. 农业板块 (FARM / BREW / PAST) · 15 条 ====================
            new Template("农业", true, 6, 16, "金胡萝卜与附魔金苹果采购订单激增，丰穗农团季度营收大涨"),
            new Template("农业", true, 5, 13, "全自动村民农场灌溉技术突破，主城粮仓储备创历史新高"),
            new Template("农业", true, 7, 17, "骨粉催化自动收割阵列全面普及，单位农田亩产提升 40%"),
            new Template("农业", true, 6, 14, "甘露酿造推出陈年发光浆果气泡酒，主城酒馆订货量翻倍"),
            new Template("农业", true, 5, 12, "翠谷牧业改良高产绵羊与哞菇培育体系，皮革与奶制品出口激增"),
            new Template("农业", true, 8, 18, "下界疣无菌温室扩产成功，跨服药水原料招标由农业板块包揽"),
            new Template("农业", true, 6, 15, "蜜脾与蜂蜜块工业化采集线投产，农业深加工毛利率显著改善"),
            new Template("农业", true, 5, 11, "主城庆典采购大宗南瓜派与曲奇，农业板块迎来消费旺季"),
            new Template("农业", false, -14, -5, "罕见寒潮侵袭主世界平原产区，小麦与甜菜根减产预期升温"),
            new Template("农业", false, -12, -4, "掠夺者巡逻队袭扰边境农庄，农产品跨区漕运成本阶段性上升"),
            new Template("农业", false, -15, -6, "苦力怕夜间潜入二号种子库自爆，珍稀可可豆育种进度受阻"),
            new Template("农业", false, -11, -4, "甘蔗连作导致河岸土壤肥力下降，造纸与制糖原料报价承压"),
            new Template("农业", false, -13, -5, "梅雨季延长引发粮仓霉变风险，农业合作社计提库存减值"),
            new Template("农业", false, -10, -4, "野生狐狸群夜袭甜浆果种植园，甘露酿造短期原浆供应吃紧"),
            new Template("农业", false, -16, -7, "史莱姆潮汛泛滥淹没低洼牧场，翠谷牧业紧急转移牲畜资产"),

            // ==================== 2. 矿业板块 (MINE / NETR / CRYS) · 15 条 ====================
            new Template("矿业", true, 8, 19, "深层钻探队在 Y=-54 发现高纯度远古残骸富矿带，矿业估值飙升"),
            new Template("矿业", true, 6, 15, "时运 III 自动化选矿流水线投产，钻石与绿宝石精炼率提升 28%"),
            new Template("矿业", true, 9, 21, "下界合金集团研发TNT盾构联采工艺，远古残骸日均开采量翻番"),
            new Template("矿业", true, 7, 16, "紫晶地质探明超大型天然紫水晶母岩洞穴，光学晶体订单爆满"),
            new Template("矿业", true, 6, 14, "急迫 II 信标阵列覆盖全矿区，深板岩金矿与红石矿采掘效率大增"),
            new Template("矿业", true, 8, 17, "主城铁匠铺联名追加下界合金锭长期合约，矿业板块获主力抢筹"),
            new Template("矿业", true, 5, 13, "粗铁与粗铜高频磁选提纯技术落地，矿渣副产品回收收益超预期"),
            new Template("矿业", true, 7, 15, "山地绿宝石伴生矿脉储量上修 35%，深岩矿业宣布上调年度分红"),
            new Template("矿业", false, -16, -6, "深暗之域矿井触发潜声尖啸体警报，三号主矿脉被迫临时封井"),
            new Template("矿业", false, -13, -5, "下界岩浆海渗漏导致玄武岩矿区停工检修，短期矿石产出受阻"),
            new Template("矿业", false, -18, -7, "监守者游荡至深层采掘面，深岩矿业紧急撤离井下作业团队"),
            new Template("矿业", false, -14, -5, "紫晶母岩在爆破作业中意外损毁，紫晶地质下调本季出货指引"),
            new Template("矿业", false, -12, -4, "钻石镐耐久损耗补贴标准上调，矿山一线采掘人工成本增加"),
            new Template("矿业", false, -15, -6, "废弃矿坑毒蜘蛛群落暴发，浅层铁矿与煤矿运输巷道暂时封闭"),
            new Template("矿业", false, -11, -4, "国际矿石现货库存阶段性高企，大宗原矿冶炼加工费率小幅回落"),

            // ==================== 3. 工业板块 (RED / PIST / COPP) · 15 条 ====================
            new Template("工业", true, 7, 18, "零刻脉冲高频红石芯片量产成功，全服自动分类仓储换代潮开启"),
            new Template("工业", true, 6, 15, "粘性活塞与侦测器精密制造良率突破 99%，活塞动力获大额基建订单"),
            new Template("工业", true, 8, 19, "铜芯重工发布新一代避雷针与铜灯储能阵列，工业自动化需求井喷"),
            new Template("工业", true, 6, 14, "合成器（Crafter）全自动流水线交付使用，工业板块生产效率跃升"),
            new Template("工业", true, 7, 16, "红石比较器模拟信号算法升级，高精度多倍熔炉阵列销量大增"),
            new Template("工业", true, 5, 13, "史莱姆块与蜂蜜块双模飞行器引擎定型，活塞动力斩获军工订单"),
            new Template("工业", true, 8, 17, "氧化铜纳米防锈涂层专利获批，铜芯重工海外管道工程中标"),
            new Template("工业", true, 6, 14, "全服刷铁机与刷石机标准模组化改造启动，红石工坊营收再创新高"),
            new Template("工业", false, -15, -6, "高频红石中继器过载引发连锁短路，工业园区限电检修两日"),
            new Template("工业", false, -12, -4, "下界石英原料报价跳涨，红石比较器与光学传感器毛利率承压"),
            new Template("工业", false, -14, -5, "粘液球现货供应短缺，粘性活塞生产线被迫切换为普通活塞排产"),
            new Template("工业", false, -13, -5, "服务器高频红石卡顿治理新规出台，超大型脉冲装置面临合规整改"),
            new Template("工业", false, -11, -4, "铜锭深度氧化除锈工序耗时超预期，铜芯重工部分批次延迟交付"),
            new Template("工业", false, -16, -6, "三号红石实验车间发生TNT复制机走火事故，短期产能受限"),
            new Template("工业", false, -10, -4, "精密铁砧锻造模具磨损率上升，工业零部件本季维护费用增加"),

            // ==================== 4. 远航板块 (VOID / PORT / NAUT) · 15 条 ====================
            new Template("远航", true, 9, 22, "末地外岛联合舰队满载鞘翅与龙息返航，远航贸易利润翻倍"),
            new Template("远航", true, 7, 17, "折跃门信标导航阵列完成扩容，跨维度货运损耗率降至历史新低"),
            new Template("远航", true, 8, 18, "瀚海船务打通暖水海洋珊瑚航线，海晶石与海绵进口贸易额暴涨"),
            new Template("远航", true, 6, 15, "烟花火箭推进效率提升 30%，末界远航超视距探索队发现三座新末地城"),
            new Template("远航", true, 7, 16, "下界顶层基岩冰道高速干线全线贯通，折跃航运货运时效缩短 75%"),
            new Template("远航", true, 6, 14, "海洋之心与潮涌核心批量列装护航船队，深海远洋航线安全性大增"),
            new Template("远航", true, 8, 19, "潜影盒模块化集装箱标准确立，单船吞吐量跃升 27 倍"),
            new Template("远航", true, 5, 13, "藏宝图勘探队在沉船遗迹起获大批海盗金银储备，瀚海船务派发特别股息"),
            new Template("远航", false, -19, -7, "虚空磁暴干扰末地折跃门坐标，两支远航商船队延误入港"),
            new Template("远航", false, -15, -6, "潜影贝壳战略库存告急，远航集装箱租赁费率大幅上调"),
            new Template("远航", false, -14, -5, "远古守卫者封锁海底神殿海峡，瀚海船务三条主力商路被迫绕行"),
            new Template("远航", false, -17, -6, "末影龙复苏波动波及中央传送门，末界远航暂停近地客运航班"),
            new Template("远航", false, -12, -4, "下界恶魂火球袭击高空冰道枢纽，折跃航运启动紧急抢修预案"),
            new Template("远航", false, -13, -5, "火药与纸张原料涨价推升烟花火箭成本，高空探索队利润率收窄"),
            new Template("远航", false, -11, -4, "寒带冰洋浮冰封冻港口航道，北境远洋货轮周转天数拉长"),

            // ==================== 5. 能源板块 (BLAZ / LAVA / SOLR) · 15 条 ====================
            new Template("能源", true, 8, 18, "下界要塞双刷烈焰人核心阵列并网，烈焰棒燃料日产量创历史峰值"),
            new Template("能源", true, 6, 15, "滴水石锥无限熔岩釜阵列大规模商用，熔岩热电发电边际成本归零"),
            new Template("能源", true, 7, 16, "耀光光伏研发阳光传感器全天候追踪模组，沙漠电站出力提升 35%"),
            new Template("能源", true, 6, 14, "干海带块全自动闭环生物质燃料炉投产，清洁冶炼能源订单激增"),
            new Template("能源", true, 7, 17, "冬季供暖与大型超熔炉阵列用电负荷攀升，能源板块迎来量价齐升"),
            new Template("能源", true, 5, 12, "煤炭块高压液化提纯技术落地，单位燃料热值提升 22%"),
            new Template("能源", true, 8, 19, "烈焰能源与星辉炼金签署十年独家烈焰粉供应协议，锁定期货溢价"),
            new Template("能源", true, 6, 13, "主城路灯网全面切换为蛙明灯与萤石冷光源，节能补贴集中到账"),
            new Template("能源", false, -15, -6, "下界要塞抗火药水补给中断，烈焰能源二号采集场临时减产"),
            new Template("能源", false, -13, -5, "主世界连续雷暴阴雨天气压制光伏出力，耀光光伏下调月度发电量"),
            new Template("能源", false, -14, -5, "铁桶回收价格大幅波动，熔岩热电罐装熔岩跨区配送毛利下滑"),
            new Template("能源", false, -12, -4, "烈焰粉仓储车间发生高温自燃险情，能源板块安全巡检全面升级"),
            new Template("能源", false, -11, -4, "地毯复制燃料机在部分私服扩散，传统煤炭与熔岩报价短期承压"),
            new Template("能源", false, -15, -6, "下界传送门输能管道遭遇岩浆怪堵门，跨界能源调配延迟"),
            new Template("能源", false, -10, -4, "耐火砖炉膛例行大修启动，三座主力热电高炉轮流停机一周"),

            // ==================== 6. 基建板块 (RAIL / OBSD / BRCK) · 15 条 ====================
            new Template("基建", true, 6, 15, "跨服动力铁轨干线三期工程获批，铁道基建中标百亿星币总包合同"),
            new Template("基建", true, 7, 16, "末地黑曜石柱再生循环采掘体系建成，黑曜筑城高防建材成本大降"),
            new Template("基建", true, 5, 13, "主城新城区规划落地，深板岩砖、石英砖与海晶石灯需求全线爆发"),
            new Template("基建", true, 7, 17, "蓝冰高速矿车混动轨道测试时速突破 72m/s，铁道基建估值重估"),
            new Template("基建", true, 6, 14, "防爆哭泣黑曜石装甲墙通过凋灵冲击测试，黑曜筑城军工订单激增"),
            new Template("基建", true, 5, 12, "混凝土粉末自动凝固流水线满负荷运转，棱晶建材季度净利增长 45%"),
            new Template("基建", true, 6, 15, "凝灰岩与铜格栅装饰材料成为新版本建筑潮流，建材板块领涨大盘"),
            new Template("基建", true, 8, 18, "四号跨海大桥与海底玻璃隧道顺利合龙，基建板块迎来竣工结算潮"),
            new Template("基建", false, -13, -5, "黄金与红石原矿涨价推高动力铁轨造价，铁道基建毛利率阶段性收窄"),
            new Template("基建", false, -14, -5, "沙砾与沙子采砂场环保限产，混凝土与玻璃幕墙交付周期延长"),
            new Template("基建", false, -12, -4, "凋灵越狱事件波及边境基建工地，黑曜筑城计提工程抢修准备金"),
            new Template("基建", false, -11, -4, "主城地产开发节奏放缓，普通石砖与圆石大宗批发价小幅阴跌"),
            new Template("基建", false, -15, -6, "苦力怕连续炸毁北线高架铁轨路基，铁道基建启动全线加固工程"),
            new Template("基建", false, -10, -4, "效率 V 钻石镐补贴退坡，黑曜石人工采掘单价环比上升"),
            new Template("基建", false, -13, -5, "雨季山体滑坡阻断黏土采掘场运输线，红砖与陶瓦供应偏紧"),

            // ==================== 7. 炼金板块 (ALCH / ENCH / SOUL) · 15 条 ====================
            new Template("炼金", true, 8, 19, "经验修补与耐久 III 附魔书馆藏扩容，附魔书院高端订单排至下季度"),
            new Template("炼金", true, 7, 17, "幽匿催发体高密度经验浓缩技术突破，幽匿生物研发估值大幅跃升"),
            new Template("炼金", true, 6, 15, "迅捷、力量 II 与抗火药水军团级集采落地，星辉炼金产线满载运行"),
            new Template("炼金", true, 9, 21, "远古城市回响碎片提纯工艺告捷，复苏指南针与灵魂科技商业化提速"),
            new Template("炼金", true, 6, 14, "末影人经验塔与猪灵金剑塔双核并网，附魔书院附魔成本下降 40%"),
            new Template("炼金", true, 7, 16, "神龟药水与缓降药水在公会团战中大受欢迎，特种药剂销量翻倍"),
            new Template("炼金", true, 8, 18, "青金石催化附魔词条锁定算法立项，炼金板块吸引大量风投资金"),
            new Template("炼金", true, 5, 13, "萤石粉与红石粉微胶囊缓释技术量产，长效药水溢价率提升 25%"),
            new Template("炼金", false, -16, -6, "村民图书管理员交易重平衡传闻发酵，附魔书院二级市场报价巨震"),
            new Template("炼金", false, -17, -7, "幽匿尖啸体实验样本在三号实验室意外激活，幽匿生物停产整顿"),
            new Template("炼金", false, -13, -5, "恶魂之泪与闪烁西瓜片采购价飙升，再生与治疗药水成本承压"),
            new Template("炼金", false, -14, -5, "女巫沼泽自然保护区禁猎令生效，玻璃瓶与发光粉原料渠道收紧"),
            new Template("炼金", false, -12, -4, "高阶附魔随机词条洗练损耗超预期，附魔书院季度研发费用激增"),
            new Template("炼金", false, -15, -6, "龙息采集队在末地遭遇虚空风暴，滞留药水生产线暂时停摆"),
            new Template("炼金", false, -11, -4, "普通力量与治疗药水市场饱和，低端药剂掀起价格战"),

            // ==================== 8. 金融与宏观板块 (EMRD / GOLD / GILD / *) · 15 条 ====================
            new Template("金融", true, 6, 15, "绿宝银行宣布下调跨服结算手续费并推出持仓质押信贷，交投活跃度飙升"),
            new Template("金融", true, 7, 16, "主城央行增持下界金锭与绿宝石外汇储备，金锭信托净值创历史新高"),
            new Template("金融", true, 5, 13, "鎏金保险推出全服探险掉落物全额理赔险，保费收入环比暴涨 60%"),
            new Template("金融", true, 8, 17, "袭击印记（Bad Omen）改版后村庄贸易稳定度提升，绿宝银行坏账率大降"),
            new Template("金融", true, 6, 14, "猪灵堡垒镶金黑石债券超额认购 4.2 倍，金融板块全面走强"),
            new Template("金融", false, -14, -5, "袭击农场（Raid Farm）绿宝石产出收紧，绿宝银行流动性准备金承压"),
            new Template("金融", false, -13, -5, "下界僵尸猪灵金粒通胀预期升温，金锭信托短期兑付利差扩大"),
            new Template("金融", false, -15, -6, "大型公会远征下界团灭触发巨额装备理赔，鎏金保险单季赔付率飙升"),
            new Template("*", true, 6, 15, "主城商会宣布下调大宗交易准备金率，场内买盘与做市商流动性显著充裕"),
            new Template("*", true, 7, 16, "跨服贸易博览会签约额超预期，核心龙头资产获机构与做市商联合增持"),
            new Template("*", true, 5, 13, "全服在线活跃玩家数创月度新高，消费、基建与资源板块全面回暖"),
            new Template("*", true, 8, 18, "股市平准基金宣布启动蓝筹股托底增持计划，市场做多情绪高涨"),
            new Template("*", false, -14, -5, "大额获利盘集中兑现离场，市场避险情绪升温引发短线宽幅震荡"),
            new Template("*", false, -12, -4, "边境传送门维护升级导致物流周转放缓，高估值板块普遍迎来技术性回调"),
            new Template("*", false, -15, -6, "杠杆空头资金借势狙击高标股，场内多空博弈加剧、盘面波动率急剧放大")
    );

    private NewsEngine() {
    }

    public static void reset() {
    }

    public static int templateCount() {
        return TEMPLATES.size();
    }

    public static void tick(MinecraftServer server) {
        if (!StockMarketConfig.ENABLED.get() || !StockMarketConfig.AUTO_NEWS_ENABLED.get()) {
            return;
        }
        if (!MarketEngine.sessionOpen()) {
            return;
        }
        StockMarketSavedData data = StockMarketSavedData.get(server);
        long gameTime = server.overworld().getGameTime();
        int minTicks = Math.max(200, StockMarketConfig.AUTO_NEWS_MIN_TICKS.get());
        int maxTicks = Math.max(minTicks + 20, StockMarketConfig.AUTO_NEWS_MAX_TICKS.get());

        if (data.nextAutoNewsTick <= 0L || data.nextAutoNewsTick < gameTime - maxTicks * 2L) {
            data.nextAutoNewsTick = gameTime + randomBetween(Math.min(600, minTicks), Math.min(1800, maxTicks));
            data.markDirty();
            return;
        }
        if (gameTime < data.nextAutoNewsTick) {
            return;
        }

        data.nextAutoNewsTick = gameTime + randomBetween(minTicks, maxTicks);
        triggerAutoNews(server, data);
    }

    private static void triggerAutoNews(MinecraftServer server, StockMarketSavedData data) {
        List<StockDef> candidates = new ArrayList<>();
        for (StockDef def : data.defs().values()) {
            if (def.enabled && !def.halted) {
                candidates.add(def);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        StockDef target = candidates.get(RANDOM.nextInt(candidates.size()));
        Template tpl = pickTemplate(target);
        boolean v3 = MarketEngine.isV3();
        int maxImpact = v3 ? StockMarketConfig.PM_AUTO_NEWS_MAX_IMPACT.get() : StockMarketConfig.AUTO_NEWS_MAX_IMPACT.get();
        int lowAbs = Math.min(Math.abs(tpl.minPct), Math.abs(tpl.maxPct));
        int highAbs = Math.max(Math.abs(tpl.minPct), Math.abs(tpl.maxPct));
        if (v3) {
            // v3：模板幅度整体缩小（默认 x0.35，封顶 8），公允价/现价再各吸收一部分
            double scale = StockMarketConfig.PM_NEWS_TEMPLATE_SCALE.get();
            lowAbs = NewsModel.scaleTemplatePct(lowAbs, scale, TEMPLATE_SCALED_CAP);
            highAbs = NewsModel.scaleTemplatePct(highAbs, scale, TEMPLATE_SCALED_CAP);
        }
        int rawPct = tpl.positive ? randomBetween(lowAbs, highAbs) : -randomBetween(lowAbs, highAbs);
        int percent = Math.max(-maxImpact, Math.min(maxImpact, rawPct));
        if (percent == 0) {
            percent = tpl.positive ? 5 : -5;
        }

        applyNewsImpact(server, data, target, percent, tpl.headline, "AUTO_NEWS");
    }

    public static void applyNewsImpact(MinecraftServer server, StockMarketSavedData data, StockDef def,
            int percent, String text, String actor) {
        Quote quote = MarketEngine.quoteOf(data, def);
        long prev = quote.price;
        double absPct = Math.abs(percent) / 100.0D;
        long clamped;
        if (MarketEngine.isV3()) {
            // v3：先推公允价，现价只吃一部分，余下靠现价追公允价；波动与趋势温和抬升
            PriceParams params = StockMarketConfig.priceParams();
            PriceModelV3.Env env = MarketEngine.envOf(params, def, quote, 1.0D);
            PriceModelV3.State state = MarketEngine.stateOf(quote);
            double spot = NewsModel.apply(params, state, percent, env.sigmaBase);
            double[] band = PriceModelV3.band(params, env, state.price);
            state.price = PriceModelV3.clamp(spot, band[0], band[1]);
            MarketEngine.writeBack(quote, state);
            clamped = quote.price;
        } else {
            long moved = Math.round(prev * (1.0D + percent / 100.0D));
            if (moved == prev && percent != 0) {
                moved = prev + (percent > 0 ? 1L : -1L);
            }
            clamped = MarketEngine.clamp(def, quote, moved);
            quote.moveTo(clamped);

            quote.newsBoost = Math.max(quote.newsBoost, absPct * 2.4D);
            quote.volCluster = Math.min(2.5D, quote.volCluster + absPct * 1.8D);
            quote.trend = (percent >= 0 ? 1.0D : -1.0D)
                    * Math.max(MarketEngine.legacyVolatility(def) * 0.45D, absPct * 0.18D);
        }

        long nowSec = System.currentTimeMillis() / 1000L;
        long wickHigh = Math.max(prev, clamped) + Math.max(1L, Math.round(clamped * absPct * 0.25D));
        long wickLow = Math.max(1L, Math.min(prev, clamped) - Math.max(1L, Math.round(clamped * absPct * 0.25D)));
        long burstVol = Math.max(80L, Math.round(350.0D + Math.abs(percent) * 65.0D));
        quote.sampleWithWick(nowSec, StockMarketConfig.BAR_SECONDS.get(), StockMarketConfig.BAR_LIMIT.get(),
                MarketEngine.clamp(def, quote, wickHigh), MarketEngine.clamp(def, quote, wickLow), burstVol);
        MarketMakerEngine.rebuildOrderBook(data, def, quote);

        TradeService.matchAll(server, data);
        TradeService.checkShortLiquidations(server, data);
        MarketEngine.recomputeIndex(data);

        long nowMs = System.currentTimeMillis();
        data.setLastNews(def.symbol, percent, text, nowMs);
        data.revision++;
        data.markDirty();

        String signPct = (percent >= 0 ? "+" : "") + percent + "%";
        data.addAudit(new AuditEntry(nowMs, actor, "news", def.logName() + " " + signPct + " " + text));
        LOG.info("[股市快讯] {} {} {}", def.logName(), signPct, text);

        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            online.displayClientMessage(Component.translatable("msg.stock.news_broadcast",
                    def.logName(), signPct, text), false);
        }
        StockServer.afterAdminChange(server, data);
    }

    private static Template pickTemplate(StockDef def) {
        List<Template> matched = new ArrayList<>();
        String sector = def.sector == null ? "" : def.sector;
        for (Template t : TEMPLATES) {
            if ("*".equals(t.match) || sector.contains(t.match) || def.symbol.equalsIgnoreCase(t.match)) {
                matched.add(t);
            }
        }
        if (matched.isEmpty()) {
            return TEMPLATES.get(RANDOM.nextInt(TEMPLATES.size()));
        }
        return matched.get(RANDOM.nextInt(matched.size()));
    }

    private static int randomBetween(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + RANDOM.nextInt(max - min + 1);
    }
}
