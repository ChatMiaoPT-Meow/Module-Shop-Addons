package cn.autoforged.module_shop_quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.autoforged.module_shop_quest.config.ConfigModels.ItemRewardSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.PassLevelSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.PeriodClock;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestDefSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestPoolsConfig;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestTaskType;
import cn.autoforged.module_shop_quest.config.ConfigModels.RewardBundleSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.SeasonPassConfig;
import cn.autoforged.module_shop_quest.config.ConfigModels.SubTargetState;
import cn.autoforged.module_shop_quest.config.ConfigModels.TaskTarget;
import cn.autoforged.module_shop_quest.config.QuestDataConfig;
import cn.autoforged.module_shop_quest.network.ChunkedPayloadCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

        long curPts = 250L;
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

    @Test
    void huge200LevelSeasonPassGzipChunkedRoundTripWorks() throws IOException {
        SeasonPassConfig huge = QuestDataConfig.createDefaultSeasonPass();
        huge.seasonId = "s_ultra_200";
        huge.seasonName = "超大 200 级压力测试赛季";
        huge.pointsRule.maxLevel = 200;
        huge.levels.clear();

        for (int lv = 1; lv <= 200; lv++) {
            List<ItemRewardSpec> freeItems = new ArrayList<>();
            List<ItemRewardSpec> premItems = new ArrayList<>();
            for (int k = 0; k < 15; k++) {
                ItemRewardSpec f = new ItemRewardSpec(
                        "minecraft:diamond_sword", 1, "星途神剑·Lv" + lv + "#" + k + "-" + UUID.randomUUID());
                f.enchantments.put("minecraft:sharpness", 5);
                f.enchantments.put("minecraft:unbreaking", 3);
                f.enchantments.put("minecraft:mending", 1);
                freeItems.add(f);

                ItemRewardSpec p = new ItemRewardSpec(
                        "minecraft:netherite_pickaxe", 1, "星辉矿镐·Lv" + lv + "#" + k + "-" + UUID.randomUUID());
                p.enchantments.put("minecraft:efficiency", 5);
                p.enchantments.put("minecraft:fortune", 3);
                p.enchantments.put("minecraft:unbreaking", 3);
                premItems.add(p);
            }
            huge.levels.add(new PassLevelSpec(
                    lv, lv * 120L, lv % 5 == 0,
                    new RewardBundleSpec(lv * 50L, 0L, freeItems),
                    new RewardBundleSpec(lv * 150L, 0L, premItems)
            ));
        }

        String passJson = QuestDataConfig.toCompactJson(huge);
        int jsonBytes = passJson.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(jsonBytes > 200_000, "200级x15附魔物品配置应远超 65535 字节（实测=" + jsonBytes + "B）");

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("action", "admin_save_config");
        payload.put("passJson", passJson);

        byte[] compressed = ChunkedPayloadCodec.encodeStringMapCompressed(payload);
        assertTrue(compressed.length < jsonBytes / 2, "GZIP 压缩比应显著缩小载荷体积");

        List<byte[]> c2sChunks = ChunkedPayloadCodec.splitChunks(compressed, ChunkedPayloadCodec.MAX_C2S_CHUNK_BYTES);
        assertTrue(c2sChunks.size() >= 2, "超大 C2S 载荷应自动切分为多片");
        for (byte[] chunk : c2sChunks) {
            assertTrue(chunk.length <= ChunkedPayloadCodec.MAX_C2S_CHUNK_BYTES, "C2S 单片不得超过 20KB");
        }

        ChunkedPayloadCodec.ChunkAssembler assembler = new ChunkedPayloadCodec.ChunkAssembler(64, 8, 5_000L);
        byte[] merged = null;
        for (int i = 0; i < c2sChunks.size(); i += 2) {
            byte[] res = assembler.accept("player-1", 42, i, c2sChunks.size(), c2sChunks.get(i), 1_000L);
            if (merged == null) {
                merged = res;
            }
        }
        assertNull(assembler.accept("player-1", 42, 0, c2sChunks.size(), c2sChunks.get(0), 1_050L));
        assertNull(merged, "未收齐所有分片前不应返回重组结果");
        for (int i = 1; i < c2sChunks.size(); i += 2) {
            byte[] res = assembler.accept("player-1", 42, i, c2sChunks.size(), c2sChunks.get(i), 1_100L);
            if (res != null) {
                merged = res;
            }
        }
        assertNotNull(merged, "收齐所有分片后应成功重组");
        assertEquals(0, assembler.pendingCount(), "重组完成后应立即清除缓存");

        Map<String, String> decoded = ChunkedPayloadCodec.decodeStringMapCompressed(
                merged, ChunkedPayloadCodec.MAX_C2S_DECOMPRESSED_BYTES);
        assertEquals("admin_save_config", decoded.get("action"));
        assertEquals(passJson, decoded.get("passJson"));

        SeasonPassConfig reparsed = QuestDataConfig.parseSeasonPassJson(decoded.get("passJson"));
        assertEquals(200, reparsed.resolvedLevels().size());
        assertEquals(15, reparsed.resolvedLevels().get(199).premiumReward.items.size());

        List<byte[]> s2cChunks = ChunkedPayloadCodec.splitChunks(compressed, ChunkedPayloadCodec.MAX_S2C_CHUNK_BYTES);
        for (byte[] chunk : s2cChunks) {
            assertTrue(chunk.length <= ChunkedPayloadCodec.MAX_S2C_CHUNK_BYTES, "S2C 单片不得超过 240KB");
        }
        byte[] gzStr = ChunkedPayloadCodec.gzipString(passJson);
        assertEquals(passJson, ChunkedPayloadCodec.gunzipString(gzStr, ChunkedPayloadCodec.MAX_S2C_DECOMPRESSED_BYTES));

        assertNull(assembler.accept("player-2", 99, 0, 3, new byte[]{1, 2, 3}, 1_000L));
        assertEquals(1, assembler.pendingCount());
        assertNotNull(assembler.accept("player-2", 100, 0, 1, new byte[]{9}, 7_000L));
        assertEquals(0, assembler.pendingCount());
        assertThrows(IOException.class, () -> assembler.accept("player-2", 101, 5, 3, new byte[]{1}, 7_100L));
    }

    @Test
    void multiTargetQuestsBatchSubmitAndLegacyCompatibilityWork() {
        QuestDefSpec multiKill = new QuestDefSpec(
                "d_multi_hunt", "多目标狩猎", "击杀指定生物", "combat", "minecraft",
                QuestTaskType.VANILLA_KILL_MOB,
                List.of(
                        new TaskTarget("minecraft:zombie", "僵尸", 5),
                        new TaskTarget("#minecraft:skeletons", "骷髅类", 3),
                        new TaskTarget("minecraft:cow", "牛", 2)
                ),
                10,
                new RewardBundleSpec(100L, 80L, List.of())
        );
        assertEquals(10, multiKill.totalTargetCount());
        assertEquals(10, multiKill.targetCount);
        assertTrue(multiKill.hasMultiTargets());

        List<SubTargetState> killStates = SubTargetState.fromDef(multiKill);
        assertEquals(3, killStates.size());
        assertFalse(SubTargetState.allCompleted(killStates));

        assertFalse(SubTargetState.advanceAll(killStates, "minecraft:zombie_villager", Set.of("minecraft:undead"), 1, true, true));
        assertTrue(SubTargetState.advanceAll(killStates, "minecraft:zombie", Set.of("minecraft:undead"), 3, true, true));
        assertEquals(3, killStates.get(0).progress);
        assertEquals(3, SubTargetState.totalProgress(killStates));

        assertTrue(SubTargetState.advanceAll(killStates, "minecraft:stray", Set.of("minecraft:skeletons"), 3, true, true));
        assertTrue(killStates.get(1).isCompleted());
        assertFalse(SubTargetState.allCompleted(killStates));

        assertTrue(SubTargetState.advanceAll(killStates, "minecraft:cow", Set.of(), 2, true, false));
        assertTrue(killStates.get(2).isCompleted());
        assertFalse(SubTargetState.allCompleted(killStates));

        assertTrue(SubTargetState.advanceAll(killStates, "minecraft:zombie", Set.of(), 5, true, true));
        assertEquals(5, killStates.get(0).progress);
        assertEquals(10, SubTargetState.totalProgress(killStates));
        assertTrue(SubTargetState.allCompleted(killStates));

        QuestDefSpec wildcardKill = new QuestDefSpec(
                "d_any_enemy", "击杀怪物", "击杀任意敌对生物", "combat", "minecraft",
                QuestTaskType.fromName("KILL_ENTITY"), "", 2, 10, new RewardBundleSpec());
        assertEquals(QuestTaskType.VANILLA_KILL_MOB, wildcardKill.parsedType());
        List<SubTargetState> wildStates = SubTargetState.fromDef(wildcardKill);
        assertFalse(SubTargetState.advanceAll(wildStates, "minecraft:cow", Set.of(), 1, true, false));
        assertTrue(SubTargetState.advanceAll(wildStates, "minecraft:creeper", Set.of(), 2, true, true));
        assertTrue(SubTargetState.allCompleted(wildStates));

        QuestDefSpec submitDef = new QuestDefSpec(
                "d_submit_mats", "物资补给", "提交指定物资", "submit", "minecraft",
                QuestTaskType.SUBMIT_ITEM,
                List.of(
                        new TaskTarget("minecraft:iron_ingot", "铁锭", 10),
                        new TaskTarget("#minecraft:logs", "任意原木", 8)
                ),
                12,
                new RewardBundleSpec(120L, 60L, List.of())
        );
        List<SubTargetState> submitStates = SubTargetState.fromDef(submitDef);
        assertEquals(0, SubTargetState.consumeSubmittedItem(submitStates, "minecraft:dirt", Set.of(), 64, -1));
        assertEquals(4, SubTargetState.consumeSubmittedItem(submitStates, "minecraft:iron_ingot", Set.of(), 4, -1));
        assertEquals(4, submitStates.get(0).progress);
        assertFalse(SubTargetState.allCompleted(submitStates));
        assertEquals(6, SubTargetState.consumeSubmittedItem(submitStates, "minecraft:iron_ingot", Set.of(), 20, -1));
        assertEquals(8, SubTargetState.consumeSubmittedItem(submitStates, "minecraft:oak_log", Set.of("minecraft:logs"), 16, -1));
        assertTrue(SubTargetState.allCompleted(submitStates));
        assertEquals(18, SubTargetState.totalProgress(submitStates));

        String legacyJson = "{\"dailyPool\":[{\"id\":\"old_mine\",\"title\":\"旧挖矿\",\"type\":\"VANILLA_BREAK_BLOCK\",\"targetFilter\":\"_ore\",\"targetCount\":8,\"weight\":10}]}";
        QuestPoolsConfig legacyPools = QuestDataConfig.parseQuestPoolsJson(legacyJson);
        assertEquals(1, legacyPools.dailyPool.size());
        QuestDefSpec legacyDef = legacyPools.dailyPool.get(0);
        assertEquals(1, legacyDef.resolvedTargets().size());
        assertEquals("_ore", legacyDef.resolvedTargets().get(0).target);
        assertEquals(8, legacyDef.resolvedTargets().get(0).count);

        List<SubTargetState> migrated = SubTargetState.fromLegacy("_ore", 8, 5);
        assertEquals(1, migrated.size());
        assertEquals(5, migrated.get(0).progress);
        assertTrue(SubTargetState.advanceAll(migrated, "minecraft:deepslate_diamond_ore", Set.of(), 3, false, true));
        assertTrue(SubTargetState.allCompleted(migrated));

        assertEquals(QuestTaskType.VISIT_BIOME, QuestTaskType.fromName("VISIT_BIOME"));
        assertEquals(QuestTaskType.VISIT_STRUCTURE, QuestTaskType.fromName("VISIT_STRUCTURE"));
        assertEquals(QuestTaskType.VANILLA_BREAK_BLOCK, QuestTaskType.fromName("MINE_BLOCK"));
        assertEquals(QuestTaskType.VANILLA_CRAFT_ITEM, QuestTaskType.fromName("CRAFT_ITEM"));
        assertEquals("biome", QuestTaskType.VISIT_BIOME.targetKind());
        assertEquals("structure", QuestTaskType.VISIT_STRUCTURE.targetKind());
        assertEquals("entity", QuestTaskType.VANILLA_KILL_MOB.targetKind());
        assertEquals("block", QuestTaskType.VANILLA_BREAK_BLOCK.targetKind());
        assertEquals("item", QuestTaskType.SUBMIT_ITEM.targetKind());
    }
}
