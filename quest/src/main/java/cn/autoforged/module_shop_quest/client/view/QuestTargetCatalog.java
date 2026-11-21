package cn.autoforged.module_shop_quest.client.view;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 面向大型整合包（30,000+ 物品/实体/群系/结构）的缓存索引与类 JEI 检索目录。
 * <p>
 * 仅在语言变化或注册表/服务端候选项变更时惰性重建小写索引，查询结果按条件缓存，
 * 渲染帧内零 {@code getHoverName()} 遍历、零 GC 抖动。
 */
public final class QuestTargetCatalog {
    public enum Kind {
        ITEM,
        BLOCK,
        ENTITY,
        BIOME,
        STRUCTURE
    }

    public record Entry(
            Kind kind,
            String id,
            String modId,
            String displayName,
            String nameLower,
            String idLower,
            ItemStack icon,
            boolean primaryCategory
    ) {
        public boolean matches(String[] terms) {
            for (String term : terms) {
                if (term.isEmpty()) {
                    continue;
                }
                if (term.startsWith("@")) {
                    String modQ = term.substring(1);
                    if (!modQ.isEmpty() && !modId.contains(modQ)) {
                        return false;
                    }
                } else if (!nameLower.contains(term) && !idLower.contains(term)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final String[] POPULAR_ITEM_IDS = {
            "minecraft:diamond", "minecraft:emerald", "minecraft:golden_apple",
            "minecraft:enchanted_golden_apple", "minecraft:netherite_ingot", "minecraft:netherite_scrap",
            "minecraft:nether_star", "minecraft:totem_of_undying", "minecraft:experience_bottle",
            "minecraft:diamond_block", "minecraft:emerald_block", "minecraft:gold_ingot",
            "minecraft:iron_ingot", "minecraft:golden_carrot", "minecraft:cooked_beef",
            "minecraft:cooked_salmon", "minecraft:bread", "minecraft:torch",
            "minecraft:coal", "minecraft:lapis_lazuli", "minecraft:redstone",
            "minecraft:quartz", "minecraft:amethyst_shard", "minecraft:ender_pearl",
            "minecraft:blaze_rod", "minecraft:shulker_shell", "minecraft:elytra",
            "minecraft:beacon", "minecraft:trident", "minecraft:name_tag"
    };

    private static final String[] POPULAR_BLOCK_IDS = {
            "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:iron_ore",
            "minecraft:deepslate_iron_ore", "minecraft:gold_ore", "minecraft:coal_ore",
            "minecraft:emerald_ore", "minecraft:ancient_debris", "minecraft:oak_log",
            "minecraft:spruce_log", "minecraft:birch_log", "minecraft:stone",
            "minecraft:cobblestone", "minecraft:deepslate", "minecraft:obsidian",
            "minecraft:netherrack", "minecraft:end_stone", "minecraft:glowstone"
    };

    private static final String[] POPULAR_ENTITY_IDS = {
            "minecraft:zombie", "minecraft:skeleton", "minecraft:creeper",
            "minecraft:spider", "minecraft:enderman", "minecraft:witch",
            "minecraft:blaze", "minecraft:wither_skeleton", "minecraft:ghast",
            "minecraft:piglin", "minecraft:pillager", "minecraft:vindicator",
            "minecraft:ravager", "minecraft:warden", "minecraft:ender_dragon",
            "minecraft:wither", "minecraft:slime", "minecraft:drowned",
            "minecraft:phantom", "minecraft:shulker", "minecraft:guardian",
            "minecraft:elder_guardian", "minecraft:cow", "minecraft:pig",
            "minecraft:sheep", "minecraft:chicken", "minecraft:villager"
    };

    private static String cachedLang = "";
    private static int cachedItemCount = -1;
    private static int cachedBlockCount = -1;
    private static int cachedEntityCount = -1;
    private static int cachedEntityTagRev = -1;
    private static int cachedWorldRev = -1;

    private static List<Entry> allItems = List.of();
    private static List<Entry> allBlocks = List.of();
    private static List<Entry> allEntities = List.of();
    private static List<Entry> allBiomes = List.of();
    private static List<Entry> allStructures = List.of();

    private static final Map<String, String> ITEM_NAME_MAP = new HashMap<>();
    private static final Map<String, String> BLOCK_NAME_MAP = new HashMap<>();
    private static final Map<String, String> ENTITY_NAME_MAP = new HashMap<>();
    private static final Map<String, String> BIOME_NAME_MAP = new HashMap<>();
    private static final Map<String, String> STRUCTURE_NAME_MAP = new HashMap<>();

    private static String lastQueryKey = "";
    private static List<Entry> lastQueryResult = List.of();

    private QuestTargetCatalog() {
    }

    private static String currentLangCode() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getLanguageManager() != null) {
                String sel = mc.getLanguageManager().getSelected();
                if (sel != null) {
                    return sel;
                }
            }
        } catch (Throwable ignored) {
        }
        return "default";
    }

    private static void checkLangReset() {
        String lang = currentLangCode();
        if (!lang.equals(cachedLang)) {
            cachedLang = lang;
            cachedItemCount = -1;
            cachedBlockCount = -1;
            cachedEntityCount = -1;
            cachedWorldRev = -1;
            lastQueryKey = "";
            lastQueryResult = List.of();
        }
    }

    private static void ensureItemsBuilt() {
        checkLangReset();
        int count = BuiltInRegistries.ITEM.size();
        if (count == cachedItemCount && !allItems.isEmpty()) {
            return;
        }
        cachedItemCount = count;
        ITEM_NAME_MAP.clear();

        List<Entry> out = new ArrayList<>(count);
        Set<Item> added = new LinkedHashSet<>(count);

        for (String idStr : POPULAR_ITEM_IDS) {
            ResourceLocation rl = ResourceLocation.tryParse(idStr);
            if (rl == null) continue;
            Item it = BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.AIR);
            if (it != Items.AIR && added.add(it)) {
                out.add(makeItemEntry(it, rl));
            }
        }
        for (Item it : BuiltInRegistries.ITEM) {
            if (it == Items.AIR || !added.add(it)) {
                continue;
            }
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(it);
            out.add(makeItemEntry(it, rl));
        }

        allItems = Collections.unmodifiableList(out);
        lastQueryKey = "";
    }

    private static Entry makeItemEntry(Item it, ResourceLocation rl) {
        ItemStack stack = new ItemStack(it, 1);
        String id = rl.toString();
        String name = stripFormatting(stack.getHoverName().getString());
        if (name.isBlank()) {
            name = prettifyPath(rl.getPath());
        }
        ITEM_NAME_MAP.put(id.toLowerCase(Locale.ROOT), name);
        return new Entry(
                Kind.ITEM,
                id,
                rl.getNamespace().toLowerCase(Locale.ROOT),
                name,
                name.toLowerCase(Locale.ROOT),
                id.toLowerCase(Locale.ROOT),
                stack,
                it instanceof BlockItem
        );
    }

    private static void ensureBlocksBuilt() {
        checkLangReset();
        int count = BuiltInRegistries.BLOCK.size();
        if (count == cachedBlockCount && !allBlocks.isEmpty()) {
            return;
        }
        cachedBlockCount = count;
        BLOCK_NAME_MAP.clear();

        List<Entry> out = new ArrayList<>(count);
        Set<Block> added = new LinkedHashSet<>(count);

        for (String idStr : POPULAR_BLOCK_IDS) {
            ResourceLocation rl = ResourceLocation.tryParse(idStr);
            if (rl == null) continue;
            Block b = BuiltInRegistries.BLOCK.getOptional(rl).orElse(Blocks.AIR);
            if (b != Blocks.AIR && added.add(b)) {
                out.add(makeBlockEntry(b, rl));
            }
        }
        for (Block b : BuiltInRegistries.BLOCK) {
            if (b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR || !added.add(b)) {
                continue;
            }
            ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(b);
            out.add(makeBlockEntry(b, rl));
        }

        allBlocks = Collections.unmodifiableList(out);
        lastQueryKey = "";
    }

    private static Entry makeBlockEntry(Block block, ResourceLocation rl) {
        Item asItem = block.asItem();
        ItemStack icon = (asItem != Items.AIR) ? new ItemStack(asItem, 1) : new ItemStack(Items.STONE, 1);
        String id = rl.toString();
        String name = stripFormatting(block.getName().getString());
        if (name.isBlank()) {
            name = prettifyPath(rl.getPath());
        }
        BLOCK_NAME_MAP.put(id.toLowerCase(Locale.ROOT), name);
        return new Entry(
                Kind.BLOCK,
                id,
                rl.getNamespace().toLowerCase(Locale.ROOT),
                name,
                name.toLowerCase(Locale.ROOT),
                id.toLowerCase(Locale.ROOT),
                icon,
                asItem != Items.AIR
        );
    }

    private static void ensureEntitiesBuilt(int worldRev) {
        checkLangReset();
        int count = BuiltInRegistries.ENTITY_TYPE.size();
        if (count == cachedEntityCount && cachedEntityTagRev == worldRev && !allEntities.isEmpty()) {
            return;
        }
        cachedEntityCount = count;
        cachedEntityTagRev = worldRev;
        ENTITY_NAME_MAP.clear();

        List<Entry> out = new ArrayList<>(count + 32);
        Set<EntityType<?>> added = new LinkedHashSet<>(count);

        for (String idStr : POPULAR_ENTITY_IDS) {
            ResourceLocation rl = ResourceLocation.tryParse(idStr);
            if (rl == null) continue;
            EntityType<?> et = BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
            if (et != null && added.add(et)) {
                out.add(makeEntityEntry(et, rl));
            }
        }
        for (EntityType<?> et : BuiltInRegistries.ENTITY_TYPE) {
            if (!added.add(et)) {
                continue;
            }
            ResourceLocation rl = BuiltInRegistries.ENTITY_TYPE.getKey(et);
            out.add(makeEntityEntry(et, rl));
        }
        for (String tagId : cn.autoforged.module_shop_quest.client.QuestClientState.syncedEntityTags()) {
            if (tagId == null || tagId.isBlank()) continue;
            String norm = tagId.trim();
            String withoutHash = norm.startsWith("#") ? norm.substring(1) : norm;
            ResourceLocation rl = ResourceLocation.tryParse(withoutHash);
            String ns = rl != null ? rl.getNamespace() : "minecraft";
            String path = rl != null ? rl.getPath() : withoutHash;
            String disp = "实体标签 · " + prettifyPath(path);
            ENTITY_NAME_MAP.put(norm.toLowerCase(Locale.ROOT), disp);
            out.add(new Entry(
                    Kind.ENTITY,
                    norm,
                    ns.toLowerCase(Locale.ROOT),
                    disp,
                    disp.toLowerCase(Locale.ROOT),
                    norm.toLowerCase(Locale.ROOT),
                    new ItemStack(Items.NAME_TAG, 1),
                    true
            ));
        }

        allEntities = Collections.unmodifiableList(out);
        lastQueryKey = "";
    }

    private static Entry makeEntityEntry(EntityType<?> et, ResourceLocation rl) {
        String id = rl.toString();
        String name = stripFormatting(et.getDescription().getString());
        if (name.isBlank()) {
            name = prettifyPath(rl.getPath());
        }
        boolean isLiving = et.getCategory() != MobCategory.MISC;
        SpawnEggItem egg = SpawnEggItem.byId(et);
        ItemStack icon;
        if (egg != null) {
            icon = new ItemStack(egg, 1);
        } else {
            icon = switch (et.getCategory()) {
                case MONSTER -> new ItemStack(Items.ZOMBIE_HEAD, 1);
                case CREATURE -> new ItemStack(Items.COOKED_BEEF, 1);
                case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE, AXOLOTLS -> new ItemStack(Items.COD, 1);
                case AMBIENT -> new ItemStack(Items.FEATHER, 1);
                default -> new ItemStack(Items.SPAWNER, 1);
            };
        }
        ENTITY_NAME_MAP.put(id.toLowerCase(Locale.ROOT), name);
        return new Entry(
                Kind.ENTITY,
                id,
                rl.getNamespace().toLowerCase(Locale.ROOT),
                name,
                name.toLowerCase(Locale.ROOT),
                id.toLowerCase(Locale.ROOT),
                icon,
                isLiving
        );
    }

    private static void ensureWorldRegistriesBuilt(List<String> syncedBiomes, List<String> syncedStructures, int revision) {
        checkLangReset();
        if (cachedWorldRev == revision && (!allBiomes.isEmpty() || !allStructures.isEmpty())) {
            return;
        }
        cachedWorldRev = revision;
        BIOME_NAME_MAP.clear();
        STRUCTURE_NAME_MAP.clear();

        LinkedHashSet<String> biomeIds = new LinkedHashSet<>();
        if (syncedBiomes != null) {
            for (String s : syncedBiomes) {
                if (s != null && !s.isBlank()) biomeIds.add(s.trim());
            }
        }
        LinkedHashSet<String> structureIds = new LinkedHashSet<>();
        if (syncedStructures != null) {
            for (String s : syncedStructures) {
                if (s != null && !s.isBlank()) structureIds.add(s.trim());
            }
        }

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                mc.level.registryAccess().lookup(Registries.BIOME).ifPresent(lookup ->
                        lookup.listElementIds().forEach(k -> biomeIds.add(k.location().toString())));
                mc.level.registryAccess().lookup(Registries.STRUCTURE).ifPresent(lookup ->
                        lookup.listElementIds().forEach(k -> structureIds.add(k.location().toString())));
            }
        } catch (Throwable ignored) {
        }

        Language lang = Language.getInstance();
        List<Entry> bList = new ArrayList<>(biomeIds.size());
        for (String id : biomeIds) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            String ns = rl != null ? rl.getNamespace() : "minecraft";
            String path = rl != null ? rl.getPath() : id;
            String key = "biome." + ns + "." + path;
            String name = lang.has(key) ? stripFormatting(lang.getOrDefault(key)) : prettifyPath(path);
            BIOME_NAME_MAP.put(id.toLowerCase(Locale.ROOT), name);
            bList.add(new Entry(
                    Kind.BIOME,
                    id,
                    ns.toLowerCase(Locale.ROOT),
                    name,
                    name.toLowerCase(Locale.ROOT),
                    id.toLowerCase(Locale.ROOT),
                    biomeIcon(path),
                    true
            ));
        }

        List<Entry> sList = new ArrayList<>(structureIds.size());
        for (String id : structureIds) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            String ns = rl != null ? rl.getNamespace() : "minecraft";
            String path = rl != null ? rl.getPath() : id;
            String key = "structure." + ns + "." + path;
            String name = lang.has(key) ? stripFormatting(lang.getOrDefault(key)) : prettifyPath(path);
            STRUCTURE_NAME_MAP.put(id.toLowerCase(Locale.ROOT), name);
            sList.add(new Entry(
                    Kind.STRUCTURE,
                    id,
                    ns.toLowerCase(Locale.ROOT),
                    name,
                    name.toLowerCase(Locale.ROOT),
                    id.toLowerCase(Locale.ROOT),
                    structureIcon(path),
                    true
            ));
        }

        allBiomes = Collections.unmodifiableList(bList);
        allStructures = Collections.unmodifiableList(sList);
        lastQueryKey = "";
    }

    public static List<Entry> search(Kind kind, String rawQuery, boolean primaryOnly,
                                     List<String> syncedBiomes, List<String> syncedStructures, int worldRev) {
        String q = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        String cacheKey = kind.name() + "|" + primaryOnly + "|" + worldRev + "|" + cachedLang + "|" + q;
        if (cacheKey.equals(lastQueryKey)) {
            return lastQueryResult;
        }

        List<Entry> source = switch (kind) {
            case ITEM -> { ensureItemsBuilt(); yield allItems; }
            case BLOCK -> { ensureBlocksBuilt(); yield allBlocks; }
            case ENTITY -> { ensureEntitiesBuilt(worldRev); yield allEntities; }
            case BIOME -> { ensureWorldRegistriesBuilt(syncedBiomes, syncedStructures, worldRev); yield allBiomes; }
            case STRUCTURE -> { ensureWorldRegistriesBuilt(syncedBiomes, syncedStructures, worldRev); yield allStructures; }
        };

        String[] terms = q.isEmpty() ? new String[0] : q.split("\\s+");
        List<Entry> filtered = new ArrayList<>(Math.min(256, source.size()));
        for (Entry e : source) {
            if (primaryOnly && !e.primaryCategory()) {
                continue;
            }
            if (terms.length == 0 || e.matches(terms)) {
                filtered.add(e);
            }
        }
        lastQueryKey = cacheKey;
        lastQueryResult = Collections.unmodifiableList(filtered);
        return lastQueryResult;
    }

    public static String resolveDisplayName(Kind kind, String rawId,
                                            List<String> syncedBiomes, List<String> syncedStructures, int worldRev) {
        if (rawId == null || rawId.isBlank() || "*".equals(rawId.trim())) {
            return switch (kind) {
                case ENTITY -> "任意怪物";
                case BLOCK -> "任意方块";
                case BIOME -> "任意群系";
                case STRUCTURE -> "任意结构";
                default -> "任意物品";
            };
        }
        String norm = rawId.trim().toLowerCase(Locale.ROOT);
        if (norm.startsWith("#")) {
            return "标签 " + rawId.trim();
        }
        switch (kind) {
            case ITEM -> {
                ensureItemsBuilt();
                String hit = ITEM_NAME_MAP.get(norm);
                if (hit != null) return hit;
            }
            case BLOCK -> {
                ensureBlocksBuilt();
                String hit = BLOCK_NAME_MAP.get(norm);
                if (hit != null) return hit;
            }
            case ENTITY -> {
                ensureEntitiesBuilt(worldRev);
                String hit = ENTITY_NAME_MAP.get(norm);
                if (hit != null) return hit;
            }
            case BIOME -> {
                ensureWorldRegistriesBuilt(syncedBiomes, syncedStructures, worldRev);
                String hit = BIOME_NAME_MAP.get(norm);
                if (hit != null) return hit;
            }
            case STRUCTURE -> {
                ensureWorldRegistriesBuilt(syncedBiomes, syncedStructures, worldRev);
                String hit = STRUCTURE_NAME_MAP.get(norm);
                if (hit != null) return hit;
            }
        }
        return rawId.trim();
    }

    public static ItemStack resolveIcon(Kind kind, String rawId) {
        if (rawId == null || rawId.isBlank() || rawId.startsWith("#") || "*".equals(rawId.trim())) {
            return switch (kind) {
                case ENTITY -> new ItemStack(Items.IRON_SWORD);
                case BLOCK -> new ItemStack(Items.IRON_PICKAXE);
                case BIOME -> new ItemStack(Items.GRASS_BLOCK);
                case STRUCTURE -> new ItemStack(Items.COMPASS);
                default -> new ItemStack(Items.CHEST);
            };
        }
        ResourceLocation rl = ResourceLocation.tryParse(rawId.trim().toLowerCase(Locale.ROOT));
        if (rl == null) {
            return new ItemStack(Items.PAPER);
        }
        return switch (kind) {
            case ITEM -> {
                Item it = BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.AIR);
                yield it != Items.AIR ? new ItemStack(it) : new ItemStack(Items.CHEST);
            }
            case BLOCK -> {
                Block b = BuiltInRegistries.BLOCK.getOptional(rl).orElse(Blocks.AIR);
                Item it = b != Blocks.AIR ? b.asItem() : Items.AIR;
                yield it != Items.AIR ? new ItemStack(it) : new ItemStack(Items.STONE);
            }
            case ENTITY -> {
                EntityType<?> et = BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
                SpawnEggItem egg = et != null ? SpawnEggItem.byId(et) : null;
                yield egg != null ? new ItemStack(egg) : new ItemStack(Items.ZOMBIE_HEAD);
            }
            case BIOME -> biomeIcon(rl.getPath());
            case STRUCTURE -> structureIcon(rl.getPath());
        };
    }

    private static ItemStack biomeIcon(String path) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (p.contains("nether") || p.contains("crimson") || p.contains("warped") || p.contains("basalt") || p.contains("soul")) {
            return new ItemStack(Items.NETHERRACK);
        }
        if (p.contains("end")) {
            return new ItemStack(Items.END_STONE);
        }
        if (p.contains("desert") || p.contains("badlands") || p.contains("beach")) {
            return new ItemStack(Items.SAND);
        }
        if (p.contains("snow") || p.contains("ice") || p.contains("frozen") || p.contains("grove") || p.contains("peaks")) {
            return new ItemStack(Items.SNOW_BLOCK);
        }
        if (p.contains("ocean") || p.contains("river") || p.contains("swamp") || p.contains("mangrove")) {
            return new ItemStack(Items.WATER_BUCKET);
        }
        if (p.contains("forest") || p.contains("taiga") || p.contains("jungle") || p.contains("cherry")) {
            return new ItemStack(Items.OAK_SAPLING);
        }
        if (p.contains("cave") || p.contains("deep_dark")) {
            return new ItemStack(Items.DEEPSLATE);
        }
        return new ItemStack(Items.GRASS_BLOCK);
    }

    private static ItemStack structureIcon(String path) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (p.contains("village")) return new ItemStack(Items.BELL);
        if (p.contains("fortress") || p.contains("bastion")) return new ItemStack(Items.NETHER_BRICKS);
        if (p.contains("end_city")) return new ItemStack(Items.PURPUR_BLOCK);
        if (p.contains("monument") || p.contains("shipwreck") || p.contains("ruin")) return new ItemStack(Items.PRISMARINE);
        if (p.contains("mansion") || p.contains("outpost")) return new ItemStack(Items.DARK_OAK_LOG);
        if (p.contains("stronghold") || p.contains("ancient_city") || p.contains("trial")) return new ItemStack(Items.CHISELED_STONE_BRICKS);
        return new ItemStack(Items.COMPASS);
    }

    private static String prettifyPath(String path) {
        if (path == null || path.isBlank()) return "";
        String[] parts = path.split("[_/]");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        return sb.toString();
    }

    private static String stripFormatting(String s) {
        return s == null ? "" : s.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }
}
