package cn.autoforged.module_shop_stock.server;

import cn.autoforged.module_shop_stock.StockMarket;
import cn.autoforged.module_shop_stock.StockMarketConfig;
import cn.autoforged.module_shop_stock.data.StockMarketSavedData;
import cn.autoforged.module_shop_stock.market.StockDef;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 开服时把 {@code config/module_shop_stock/stocks.json} 里声明的股票合并进存档。
 */
public final class StockSeeder {
    private static final Logger LOG = LoggerFactory.getLogger("module_shop_stock/seeder");

    private StockSeeder() {
    }

    public static void backupLegacy(MinecraftServer server) {
        try {
            Path data = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("data").resolve("module_shop_stock.dat");
            if (!Files.isRegularFile(data)) {
                return;
            }
            CompoundTag tag = NbtIo.readCompressed(data, NbtAccounter.unlimitedHeap());
            String format = tag.getString("format");
            if (StockMarketSavedData.FORMAT.equals(format)) {
                return;
            }
            // zip-2.2 存档升级到 2.3（价格模型 v3 + 波动率口径迁移）；更早的存档沿用 pre-2.2 备份
            boolean from22 = StockMarketSavedData.FORMAT_2_2.equals(format);
            Path backup = data.resolveSibling(from22
                    ? "module_shop_stock.dat.pre-2.3.bak"
                    : "module_shop_stock.dat.pre-2.2.bak");
            if (Files.exists(backup)) {
                return;
            }
            Files.copy(data, backup);
            LOG.info("检测到旧版股市存档（format={}），已备份为 {}；加载时会把个股波动迁移到 2.3 的小时口径",
                    format.isEmpty() ? "无" : format, backup.getFileName());
        } catch (IOException | RuntimeException e) {
            LOG.warn("备份旧股市存档失败：{}", e.getMessage());
        }
    }

    public static void load(MinecraftServer server) {
        StockMarketSavedData data = StockMarketSavedData.get(server);
        data.seedIfEmpty();
        Path path = FMLPaths.CONFIGDIR.get().resolve("module_shop_stock").resolve("stocks.json");
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(raw);
            if (!root.isJsonArray()) {
                LOG.warn("stocks.json 顶层必须是数组，已跳过");
                return;
            }
            int added = 0;
            int updated = 0;
            JsonArray array = root.getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String symbol = StockMarket.normalizeSymbol(string(object, "symbol", ""));
                if (!StockMarket.isValidSymbol(symbol)) {
                    LOG.warn("stocks.json：非法代码 {}，已跳过", symbol);
                    continue;
                }
                String name = string(object, "name", symbol);
                String sector = string(object, "sector", "");
                long base = clampLong(object, "base", 100L, 1L, 1_000_000L);
                double vol = clampDouble(object, "vol", 0.022D, 0.002D, 0.35D);
                long depth = object.has("depth") || !object.has("float")
                        ? clampLong(object, "depth", 3_500L, 100L, 1_000_000L)
                        : Math.max(100L, clampLong(object, "float", 700_000L, 20_000L, 200_000_000L) / 200L);
                StockDef existing = data.def(symbol);
                if (existing == null) {
                    data.putDef(new StockDef(symbol, name, null, sector, base, vol, depth));
                    added++;
                } else {
                    existing.name = name.isEmpty() ? existing.name : name;
                    if (!sector.isEmpty()) {
                        existing.sector = sector;
                    }
                    existing.basePrice = base;
                    existing.volatility = vol;
                    existing.depth = depth;
                    updated++;
                }
            }
            data.markDirty();
            LOG.info("stocks.json：新增 {} 只，更新 {} 只", added, updated);
        } catch (IOException | RuntimeException e) {
            LOG.warn("读取 stocks.json 失败：{}", e.getMessage());
        }
        MarketEngine.recomputeIndex(data);
        data.revision++;
        data.markDirty();
        LOG.info("股市就绪：{} 只股票，手续费 {} bps", data.defs().size(), StockMarketConfig.FEE_BPS.get());
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static long clampLong(JsonObject object, String key, long fallback, long min, long max) {
        try {
            JsonElement element = object.get(key);
            long value = element == null || element.isJsonNull() ? fallback : element.getAsLong();
            return Math.max(min, Math.min(max, value));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double clampDouble(JsonObject object, String key, double fallback, double min, double max) {
        try {
            JsonElement element = object.get(key);
            double value = element == null || element.isJsonNull() ? fallback : element.getAsDouble();
            if (!Double.isFinite(value)) {
                return fallback;
            }
            return Math.max(min, Math.min(max, value));
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
