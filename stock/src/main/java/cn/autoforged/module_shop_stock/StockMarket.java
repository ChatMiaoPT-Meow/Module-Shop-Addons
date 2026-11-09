package cn.autoforged.module_shop_stock;

import cn.autoforged.module_shop_stock.market.StockDef;
import java.util.List;
import java.util.Locale;

/**
 * 内置的 24 只股票（覆盖农业、矿业、工业、远航、能源、基建、炼金、金融八大板块）。
 */
public final class StockMarket {
    public static final List<StockDef> SEED = List.of(
            // 1. 农业板块
            new StockDef("FARM", "", "stock.module_shop_stock.farm", "农业", 100L, 0.036D, 4_200L),
            new StockDef("BREW", "", "stock.module_shop_stock.brew", "农业", 68L, 0.042D, 4_800L),
            new StockDef("PAST", "", "stock.module_shop_stock.past", "农业", 85L, 0.034D, 5_000L),
            // 2. 矿业板块
            new StockDef("MINE", "", "stock.module_shop_stock.mine", "矿业", 240L, 0.052D, 3_000L),
            new StockDef("NETR", "", "stock.module_shop_stock.netr", "矿业", 620L, 0.078D, 1_800L),
            new StockDef("CRYS", "", "stock.module_shop_stock.crys", "矿业", 180L, 0.058D, 3_200L),
            // 3. 工业板块
            new StockDef("RED", "", "stock.module_shop_stock.red", "工业", 80L, 0.062D, 4_500L),
            new StockDef("PIST", "", "stock.module_shop_stock.pist", "工业", 135L, 0.050D, 3_800L),
            new StockDef("COPP", "", "stock.module_shop_stock.copp", "工业", 56L, 0.046D, 5_500L),
            // 4. 远航板块
            new StockDef("VOID", "", "stock.module_shop_stock.void", "远航", 500L, 0.085D, 2_200L),
            new StockDef("PORT", "", "stock.module_shop_stock.port", "远航", 310L, 0.068D, 2_600L),
            new StockDef("NAUT", "", "stock.module_shop_stock.naut", "远航", 150L, 0.054D, 3_600L),
            // 5. 能源板块
            new StockDef("BLAZ", "", "stock.module_shop_stock.blaz", "能源", 210L, 0.064D, 3_100L),
            new StockDef("LAVA", "", "stock.module_shop_stock.lava", "能源", 95L, 0.048D, 4_600L),
            new StockDef("SOLR", "", "stock.module_shop_stock.solr", "能源", 125L, 0.052D, 3_900L),
            // 6. 基建板块
            new StockDef("RAIL", "", "stock.module_shop_stock.rail", "基建", 110L, 0.038D, 4_800L),
            new StockDef("OBSD", "", "stock.module_shop_stock.obsd", "基建", 280L, 0.044D, 3_400L),
            new StockDef("BRCK", "", "stock.module_shop_stock.brck", "基建", 64L, 0.032D, 6_000L),
            // 7. 炼金板块
            new StockDef("ALCH", "", "stock.module_shop_stock.alch", "炼金", 190L, 0.072D, 2_900L),
            new StockDef("ENCH", "", "stock.module_shop_stock.ench", "炼金", 360L, 0.066D, 2_400L),
            new StockDef("SOUL", "", "stock.module_shop_stock.soul", "炼金", 420L, 0.082D, 2_000L),
            // 8. 金融板块
            new StockDef("EMRD", "", "stock.module_shop_stock.emrd", "金融", 160L, 0.040D, 5_200L),
            new StockDef("GOLD", "", "stock.module_shop_stock.gold", "金融", 260L, 0.045D, 4_400L),
            new StockDef("GILD", "", "stock.module_shop_stock.gild", "金融", 140L, 0.050D, 4_000L)
    );

    private StockMarket() {
    }

    public static boolean isValidSymbol(String symbol) {
        if (symbol == null) {
            return false;
        }
        int length = symbol.length();
        if (length < 2 || length > 8) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = symbol.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    public static String normalizeSymbol(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }
}
