package cn.autoforged.module_shop_quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.autoforged.module_shop_quest.config.ConfigModels.PassLevelSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.PeriodClock;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestDefSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestPoolsConfig;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestTaskType;
import cn.autoforged.module_shop_quest.config.ConfigModels.SeasonPassConfig;
import cn.autoforged.module_shop_quest.config.QuestDataConfig;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuestCoreLogicTest {

    @Test
    void seasonPassFormulaAndDeluxeBonusLevelsAreConsistent() {
        SeasonPassConfig cfg = QuestDataConfig.createDefaultSeasonPass();
        List<PassLevelSpec> levels = cfg.resolvedLevels();
        assertEquals(30, levels.size());

        long prev = 0L;
        for (PassLevelSpec spec : levels) {
            assertTrue(spec.requiredPoints > prev, "等级阈值应严格递增");
            prev = spec.requiredPoints;
        }

        assertEquals(0, cfg.computeLevel(0L));
        assertEquals(1, cfg.computeLevel(100L));
        assertEquals(5, cfg.computeLevel(500L));

        // 典藏奖章直升 10 级校验（从 Lv.2 150 分直升 +10 级应精准落到 Lv.12 且保留溢出 50 分）
        long curPts = 250L; // Lv.2 + 50 溢出
        assertEquals(2, cfg.computeLevel(curPts));
        long delta = cfg.computePointsDeltaForBonusLevels(curPts, 10);
        long boosted = curPts + delta;
        assertEquals(12, cfg.computeLevel(boosted));
    }

    @Test
    void rolloverAndArchivedSnapshotRoundTripWork() {
        SeasonPassConfig cfg = QuestDataConfig.createDefaultSeasonPass();
        assertEquals(300L, cfg.computeRolloverPoints(1000L));
        assertEquals(600L, cfg.computeRolloverPoints(5000L));

        String snap = QuestDataConfig.serializeSeasonSnapshot(cfg);
        assertFalse(snap.isBlank());
        List<PassLevelSpec> parsed = QuestDataConfig.deserializeArchivedLevels(snap);
        assertEquals(30, parsed.size());
        assertEquals(1500L, parsed.get(29).premiumReward.money);
    }

    @Test
    void questPoolRespectsSoftDepsAndSafetyFilter() {
        QuestPoolsConfig pools = QuestDataConfig.createDefaultQuestPools();
        List<QuestDefSpec> drawnWithoutAddons = QuestPoolsConfig.drawQuests(
                pools.dailyPool, 4, 0.75D, Set.of("minecraft", "module_shop"), true, new Random(42L));
        assertEquals(4, drawnWithoutAddons.size());
        for (QuestDefSpec q : drawnWithoutAddons) {
            assertTrue(q.requiredMod.isBlank()
                    || "minecraft".equals(q.requiredMod)
                    || "module_shop".equals(q.requiredMod));
        }

        assertEquals(QuestTaskType.HIGH_RISK_FORBIDDEN, QuestTaskType.fromName("STOCK_SHORT_SELL"));
        assertEquals(QuestTaskType.HIGH_RISK_FORBIDDEN, QuestTaskType.fromName("LOTTERY_TEN_PULL"));
    }

    @Test
    void periodClockAndRequestIdAreDeterministic() {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        String dKey = PeriodClock.dayKey(1762819200L, shanghai, 4);
        String wKey = PeriodClock.weekKey(1762819200L, shanghai, 4, 1);
        assertNotNull(dKey);
        assertTrue(wKey.contains("-W"));

        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        String req = PeriodClock.formatRequestId("s1:test", id, "pass-deluxe", "season", "medal");
        assertEquals("quest:s1_test:123e4567-e89b-12d3-a456-426614174000:pass-deluxe:season:medal", req);
    }
}
