package cn.autoforged.module_shop_stock.market.bars;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * K 线周期。1m 为源周期（宽度取配置 {@code barSeconds}），其余全部由它聚合；日线按自然日、周线按 ISO 周（周一开始），
 * 与 {@code MarketEngine.rollDayIfNeeded} 同一个服务器本地时钟。纯 Java，不依赖 Minecraft。
 */
public enum BarPeriod {
    M1("1m", 60, 1440, 2880),
    M5("5m", 300, 864, 2016),
    M15("15m", 900, 672, 1344),
    H1("60m", 3600, 720, 1440),
    D1("1d", 0, 260, 520),
    W1("1w", 0, 104, 208);

    public static final int MIN_LIMIT = 20;
    private static final BarPeriod[] VALUES = values();

    private final String id;
    private final int fixedSeconds;
    private final int defaultLimit;
    private final int maxLimit;

    BarPeriod(String id, int fixedSeconds, int defaultLimit, int maxLimit) {
        this.id = id;
        this.fixedSeconds = fixedSeconds;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
    }

    /** 协议与配置里的短名：1m / 5m / 15m / 60m / 1d / 1w。 */
    public String id() {
        return this.id;
    }

    public int defaultLimit() {
        return this.defaultLimit;
    }

    public int maxLimit() {
        return this.maxLimit;
    }

    public boolean isCalendar() {
        return this == D1 || this == W1;
    }

    /** 名义宽度（秒），日 86400、周 604800，仅供客户端识别。 */
    public int nominalSeconds() {
        return switch (this) {
            case D1 -> 86_400;
            case W1 -> 604_800;
            default -> this.fixedSeconds;
        };
    }

    /** 兼容旧客户端：缺省或无法识别时按 1m。支持别名 1h。 */
    public static BarPeriod parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return M1;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if ("1h".equals(key) || "h1".equals(key)) {
            return H1;
        }
        for (BarPeriod period : VALUES) {
            if (period.id.equals(key)) {
                return period;
            }
        }
        return M1;
    }

    public static BarPeriod[] all() {
        return VALUES.clone();
    }

    /**
     * 包含 {@code epochSec} 的那根柱的起点（epoch 秒）。{@code sourceSeconds} 只对 1m 生效（源周期宽度）。
     * 5m/15m/60m 对齐到 {@code zone} 的本地整点；日=本地零点；周=本地周一零点。
     */
    public long start(long epochSec, int sourceSeconds, ZoneId zone) {
        switch (this) {
            case M1 -> {
                long width = Math.max(1L, sourceSeconds);
                return epochSec - Math.floorMod(epochSec, width);
            }
            case D1 -> {
                LocalDate date = Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate();
                return date.atStartOfDay(zone).toEpochSecond();
            }
            case W1 -> {
                LocalDate date = Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                return date.atStartOfDay(zone).toEpochSecond();
            }
            default -> {
                long offset = zone.getRules().getOffset(Instant.ofEpochSecond(epochSec)).getTotalSeconds();
                long local = epochSec + offset;
                return local - Math.floorMod(local, (long) this.fixedSeconds) - offset;
            }
        }
    }
}
