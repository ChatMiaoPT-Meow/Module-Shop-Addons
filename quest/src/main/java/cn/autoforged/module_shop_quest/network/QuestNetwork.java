package cn.autoforged.module_shop_quest.network;

import cn.autoforged.module_shop_quest.QuestMod;
import cn.autoforged.module_shop_quest.config.QuestTomlConfig;
import cn.autoforged.module_shop_quest.network.ChunkedPayloadCodec.ChunkAssembler;
import cn.autoforged.module_shop_quest.server.BattlePassService;
import cn.autoforged.module_shop_quest.server.OnlineTimeService;
import cn.autoforged.module_shop_quest.server.QuestAdminService;
import cn.autoforged.module_shop_quest.server.QuestManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 通行证与任务独立网络通道（协议版本 {@code "10"}）。
 * <p>
 * 彻底绕开 NBT {@code DataOutputStream.writeUTF()} 的 65535 字节单字符串上限与 C2S 32KB 载荷上限：
 * <ul>
 *     <li>所有 S2C/C2S {@link CompoundTag} 统一走自定义无长度受限二进制编码（字符串采用 32-bit UTF-8 长度前缀）+ GZIP 压缩；</li>
 *     <li>压缩后按方向自动分片（S2C 单片 {@code <= 240KB}，C2S 单片 {@code <= 20KB}），接收端按 {@code transferId} 重组并自动清理超时缓存；</li>
 *     <li>编解码与分发全链路异常兜底，绝不向 Netty 抛出未捕获异常导致玩家断线。</li>
 * </ul>
 */
public final class QuestNetwork {
    public static final String PROTOCOL_VERSION = "10";

    /** S2C 单片上限（<= 256KB，远低于 1MB 下行自定义包上限）。 */
    public static final int MAX_S2C_CHUNK_BYTES = ChunkedPayloadCodec.MAX_S2C_CHUNK_BYTES;
    /** C2S 单片上限（<= 20KB，远低于 32KB 上行自定义包上限）。 */
    public static final int MAX_C2S_CHUNK_BYTES = ChunkedPayloadCodec.MAX_C2S_CHUNK_BYTES;
    /** 单次传输最大解压后字节数保护（防恶意炸弹包）。 */
    public static final int MAX_C2S_DECOMPRESSED_BYTES = ChunkedPayloadCodec.MAX_C2S_DECOMPRESSED_BYTES;
    public static final int MAX_S2C_DECOMPRESSED_BYTES = ChunkedPayloadCodec.MAX_S2C_DECOMPRESSED_BYTES;
    public static final int MAX_C2S_CHUNKS = ChunkedPayloadCodec.MAX_C2S_CHUNKS;
    public static final int MAX_S2C_CHUNKS = ChunkedPayloadCodec.MAX_S2C_CHUNKS;
    public static final long REASSEMBLY_TIMEOUT_MS = ChunkedPayloadCodec.REASSEMBLY_TIMEOUT_MS;
    private static final int MAX_NESTING_DEPTH = 64;
    private static final int BINARY_CODEC_MAGIC = 0x4D535131; // "MSQ1"

    private static final AtomicInteger NEXT_S2C_TRANSFER_ID = new AtomicInteger(1);
    private static final AtomicInteger NEXT_C2S_TRANSFER_ID = new AtomicInteger(1);

    private static final ChunkAssembler SERVER_ASSEMBLER = new ChunkAssembler(MAX_C2S_CHUNKS, 16, REASSEMBLY_TIMEOUT_MS);
    private static final ChunkAssembler CLIENT_ASSEMBLER = new ChunkAssembler(MAX_S2C_CHUNKS, 32, REASSEMBLY_TIMEOUT_MS);

    private QuestNetwork() {
    }

    public static void resetRuntime() {
        SERVER_ASSEMBLER.clearAll();
        CLIENT_ASSEMBLER.clearAll();
    }

    public static void onPlayerLogout(UUID playerId) {
        if (playerId != null) {
            SERVER_ASSEMBLER.clearOwner(playerId.toString());
        }
    }

    // ================================================================ 分片载荷定义（纯二进制，不走 writeNbt/writeUTF）

    public record Request(int transferId, int chunkIndex, int totalChunks, byte[] data) implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(QuestMod.MODID, "request"));
        public static final StreamCodec<FriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.transferId);
                    buf.writeVarInt(payload.chunkIndex);
                    buf.writeVarInt(payload.totalChunks);
                    buf.writeByteArray(payload.data);
                },
                buf -> new Request(
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readByteArray(MAX_C2S_CHUNK_BYTES + 1024)
                )
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Response(int transferId, int chunkIndex, int totalChunks, byte[] data) implements CustomPacketPayload {
        public static final Type<Response> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(QuestMod.MODID, "response"));
        public static final StreamCodec<FriendlyByteBuf, Response> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.transferId);
                    buf.writeVarInt(payload.chunkIndex);
                    buf.writeVarInt(payload.totalChunks);
                    buf.writeByteArray(payload.data);
                },
                buf -> new Response(
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readByteArray(MAX_S2C_CHUNK_BYTES + 1024)
                )
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(Request.TYPE, Request.CODEC, QuestNetwork::handleRequest);
        registrar.playToClient(Response.TYPE, Response.CODEC, QuestNetwork::handleResponse);
    }

    // ================================================================ 发送接口（保持旧签名 100% 兼容）

    public static void sendToPlayer(ServerPlayer player, CompoundTag tag) {
        if (player == null || tag == null) {
            return;
        }
        try {
            EncodedPayload encoded = encodeTagCompressedWithStats(tag);
            List<byte[]> chunks = splitChunks(encoded.compressed(), MAX_S2C_CHUNK_BYTES);
            if (encoded.rawBytes() >= 32 * 1024 || chunks.size() > 1) {
                QuestMod.LOGGER.info(
                        "[{}] S2C 大载荷传输诊断（player={}, type={}）：raw={}B -> gzip={}B, chunks={}",
                        QuestMod.MODID, player.getGameProfile().getName(), tag.getString("type"),
                        encoded.rawBytes(), encoded.compressed().length, chunks.size());
            }
            int transferId = NEXT_S2C_TRANSFER_ID.getAndIncrement();
            int total = chunks.size();
            for (int i = 0; i < total; i++) {
                PacketDistributor.sendToPlayer(player, new Response(transferId, i, total, chunks.get(i)));
            }
        } catch (Throwable t) {
            QuestMod.LOGGER.error("[{}] S2C 编码/发送失败（player={}, type={}），已拦截防断线",
                    QuestMod.MODID, player.getGameProfile().getName(), tag.getString("type"), t);
            // 仅当不是 action_result 本身失败时才尝试发轻量错误提示，避免死循环
            if (!"action_result".equals(tag.getString("type"))) {
                sendSafeErrorBanner(player, "msg.module_shop_quest.network_error");
            }
        }
    }

    public static void sendToServer(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        try {
            EncodedPayload encoded = encodeTagCompressedWithStats(tag);
            List<byte[]> chunks = splitChunks(encoded.compressed(), MAX_C2S_CHUNK_BYTES);
            if (encoded.rawBytes() >= 32 * 1024 || chunks.size() > 1) {
                QuestMod.LOGGER.debug(
                        "[{}] C2S 分片发送（action={}）：raw={}B -> gzip={}B, chunks={}",
                        QuestMod.MODID, tag.getString("action"),
                        encoded.rawBytes(), encoded.compressed().length, chunks.size());
            }
            int transferId = NEXT_C2S_TRANSFER_ID.getAndIncrement();
            int total = chunks.size();
            for (int i = 0; i < total; i++) {
                PacketDistributor.sendToServer(new Request(transferId, i, total, chunks.get(i)));
            }
        } catch (Throwable t) {
            QuestMod.LOGGER.error("[{}] C2S 编码/发送失败（action={}）", QuestMod.MODID, tag.getString("action"), t);
        }
    }

    private static void sendSafeErrorBanner(ServerPlayer player, String messageKey) {
        try {
            CompoundTag err = new CompoundTag();
            err.putString("type", "action_result");
            err.putBoolean("ok", false);
            err.putString("message", messageKey);
            err.putString("sound", "error");
            byte[] compressed = encodeTagCompressed(err);
            PacketDistributor.sendToPlayer(player,
                    new Response(NEXT_S2C_TRANSFER_ID.getAndIncrement(), 0, 1, compressed));
        } catch (Throwable ignored) {
        }
    }

    public static void sendActionResult(ServerPlayer player, boolean ok, String messageOrKey,
                                        String soundKind, boolean includeSnapshot) {
        CompoundTag out = new CompoundTag();
        out.putString("type", "action_result");
        out.putBoolean("ok", ok);
        out.putString("message", messageOrKey == null ? "" : messageOrKey);
        out.putString("sound", soundKind == null ? "" : soundKind);
        if (includeSnapshot) {
            out.put("snapshot", QuestManager.buildSnapshotTag(player));
        }
        sendToPlayer(player, out);
    }

    // ================================================================ 接收与分片重组

    private static void handleRequest(Request payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            try {
                byte[] assembled = SERVER_ASSEMBLER.accept(
                        player.getUUID().toString(),
                        payload.transferId(),
                        payload.chunkIndex(),
                        payload.totalChunks(),
                        payload.data(),
                        System.currentTimeMillis()
                );
                if (assembled == null) {
                    return;
                }
                CompoundTag tag = decodeTagCompressed(assembled, MAX_C2S_DECOMPRESSED_BYTES);
                dispatchServerRequest(player, tag);
            } catch (Throwable t) {
                QuestMod.LOGGER.error("[{}] 处理 C2S 载荷失败（player={}, transferId={}, chunk={}/{}），已拦截防踢出",
                        QuestMod.MODID, player.getGameProfile().getName(),
                        payload.transferId(), payload.chunkIndex(), payload.totalChunks(), t);
                sendSafeErrorBanner(player, "msg.module_shop_quest.network_error");
            }
        });
    }

    private static void dispatchServerRequest(ServerPlayer player, CompoundTag tag) {
        if (!QuestTomlConfig.ENABLED.get()) {
            sendActionResult(player, false, "msg.module_shop_quest.disabled", "error", false);
            return;
        }
        String action = tag.getString("action");
        boolean isPingOnly = "ui_ping".equals(action) || "sync".equals(action);
        OnlineTimeService.touchUiActivity(player, !isPingOnly);

        switch (action) {
            case "sync" -> QuestManager.syncFullSnapshot(player);
            case "ui_ping" -> {
            }
            case "claim_quest" -> QuestManager.handleClaimQuest(player, tag.getString("period"), tag.getString("questId"));
            case "submit_quest_item" -> QuestManager.handleSubmitQuestItem(
                    player,
                    tag.getString("period"),
                    tag.getString("questId"),
                    tag.contains("targetIndex") ? tag.getInt("targetIndex") : -1
            );
            case "claim_online" -> QuestManager.handleClaimOnline(player, tag.getString("period"), tag.getString("tierId"));
            case "claim_pass" -> BattlePassService.handleClaimPassLevel(
                    player, tag.getInt("level"), tag.getBoolean("premium"), tag.getBoolean("prevSeason"));
            case "claim_all" -> QuestManager.handleClaimAll(player);
            case "unlock_premium" -> BattlePassService.handleUnlockTier(player, false);
            case "unlock_deluxe" -> BattlePassService.handleUnlockTier(player, true);
            case "buy_levels" -> BattlePassService.handleBuyLevels(player, Math.max(1, tag.getInt("count")));
            case "admin_fetch_config" -> QuestAdminService.handleFetchConfig(player);
            case "admin_fetch_registries" -> QuestAdminService.handleFetchRegistries(player);
            case "admin_save_config" -> QuestAdminService.handleSaveConfig(player, tag);
            case "admin_reload" -> {
                if (!player.hasPermissions(2)) {
                    sendActionResult(player, false, "msg.module_shop_quest.no_permission", "error", false);
                    return;
                }
                List<String> warnings = new ArrayList<>();
                QuestManager.reloadAllConfigs(player.server, warnings);
                QuestAdminService.sendAdminConfigPacket(player);
                sendActionResult(player, true, "msg.module_shop_quest.reloaded_ok", "claim", true);
            }
            default -> {
            }
        }
    }

    private static void handleResponse(Response payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                byte[] assembled = CLIENT_ASSEMBLER.accept(
                        "server",
                        payload.transferId(),
                        payload.chunkIndex(),
                        payload.totalChunks(),
                        payload.data(),
                        System.currentTimeMillis()
                );
                if (assembled == null) {
                    return;
                }
                CompoundTag tag = decodeTagCompressed(assembled, MAX_S2C_DECOMPRESSED_BYTES);
                cn.autoforged.module_shop_quest.client.QuestClient.handlePacket(tag);
            } catch (Throwable t) {
                QuestMod.LOGGER.error("[{}] 解码 S2C 载荷失败（transferId={}, chunk={}/{}），已拦截防断线",
                        QuestMod.MODID, payload.transferId(), payload.chunkIndex(), payload.totalChunks(), t);
            }
        });
    }

    // ================================================================ 无长度上限 NBT 二进制编解码 + GZIP + 分片工具

    public record EncodedPayload(int rawBytes, byte[] compressed) {
    }

    public static byte[] encodeTagCompressed(CompoundTag tag) throws IOException {
        return encodeTagCompressedWithStats(tag).compressed();
    }

    public static EncodedPayload encodeTagCompressedWithStats(CompoundTag tag) throws IOException {
        ByteArrayOutputStream rawBaos = new ByteArrayOutputStream(1024);
        try (DataOutputStream dos = new DataOutputStream(rawBaos)) {
            dos.writeInt(BINARY_CODEC_MAGIC);
            writeCompound(dos, tag == null ? new CompoundTag() : tag, 0);
        }
        byte[] raw = rawBaos.toByteArray();
        return new EncodedPayload(raw.length, ChunkedPayloadCodec.gzipCompress(raw));
    }

    public static CompoundTag decodeTagCompressed(byte[] compressed, int maxDecompressedBytes) throws IOException {
        if (compressed == null || compressed.length == 0) {
            return new CompoundTag();
        }
        int limit = Math.max(1024, maxDecompressedBytes);
        byte[] raw = ChunkedPayloadCodec.gzipDecompress(compressed, limit);

        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(raw))) {
            int magic = dis.readInt();
            if (magic != BINARY_CODEC_MAGIC) {
                throw new IOException("未知的二进制载荷魔数：0x" + Integer.toHexString(magic));
            }
            return readCompound(dis, 0, limit);
        }
    }

    public static List<byte[]> splitChunks(byte[] data, int maxChunkBytes) {
        return ChunkedPayloadCodec.splitChunks(data, maxChunkBytes);
    }

    private static void writeLongString(DataOutputStream out, String value) throws IOException {
        ChunkedPayloadCodec.writeLongString(out, value);
    }

    private static String readLongString(DataInputStream in, int maxLen) throws IOException {
        return ChunkedPayloadCodec.readLongString(in, maxLen);
    }

    private static void writeCompound(DataOutputStream out, CompoundTag tag, int depth) throws IOException {
        if (depth > MAX_NESTING_DEPTH) {
            throw new IOException("NBT 嵌套层数超限：" + depth);
        }
        var keys = tag.getAllKeys();
        out.writeInt(keys.size());
        for (String key : keys) {
            Tag val = tag.get(key);
            if (val == null) {
                writeLongString(out, key);
                out.writeByte(Tag.TAG_END);
                continue;
            }
            writeLongString(out, key);
            out.writeByte(val.getId());
            writeTagPayload(out, val, depth + 1);
        }
    }

    private static CompoundTag readCompound(DataInputStream in, int depth, int maxLen) throws IOException {
        if (depth > MAX_NESTING_DEPTH) {
            throw new IOException("NBT 嵌套层数超限：" + depth);
        }
        int size = in.readInt();
        if (size < 0 || size > 1_000_000) {
            throw new IOException("CompoundTag 键数量异常：" + size);
        }
        CompoundTag tag = new CompoundTag();
        for (int i = 0; i < size; i++) {
            String key = readLongString(in, maxLen);
            byte typeId = in.readByte();
            if (typeId == Tag.TAG_END) {
                continue;
            }
            Tag val = readTagPayload(in, typeId, depth + 1, maxLen);
            if (val != null) {
                tag.put(key, val);
            }
        }
        return tag;
    }

    private static void writeTagPayload(DataOutputStream out, Tag tag, int depth) throws IOException {
        switch (tag.getId()) {
            case Tag.TAG_BYTE -> out.writeByte(((ByteTag) tag).getAsByte());
            case Tag.TAG_SHORT -> out.writeShort(((ShortTag) tag).getAsShort());
            case Tag.TAG_INT -> out.writeInt(((IntTag) tag).getAsInt());
            case Tag.TAG_LONG -> out.writeLong(((LongTag) tag).getAsLong());
            case Tag.TAG_FLOAT -> out.writeFloat(((FloatTag) tag).getAsFloat());
            case Tag.TAG_DOUBLE -> out.writeDouble(((DoubleTag) tag).getAsDouble());
            case Tag.TAG_BYTE_ARRAY -> {
                byte[] arr = ((ByteArrayTag) tag).getAsByteArray();
                out.writeInt(arr.length);
                out.write(arr);
            }
            case Tag.TAG_STRING -> writeLongString(out, tag.getAsString());
            case Tag.TAG_LIST -> {
                ListTag list = (ListTag) tag;
                out.writeByte(list.getElementType());
                out.writeInt(list.size());
                for (Tag elem : list) {
                    writeTagPayload(out, elem, depth + 1);
                }
            }
            case Tag.TAG_COMPOUND -> writeCompound(out, (CompoundTag) tag, depth);
            case Tag.TAG_INT_ARRAY -> {
                int[] arr = ((IntArrayTag) tag).getAsIntArray();
                out.writeInt(arr.length);
                for (int v : arr) {
                    out.writeInt(v);
                }
            }
            case Tag.TAG_LONG_ARRAY -> {
                long[] arr = ((LongArrayTag) tag).getAsLongArray();
                out.writeInt(arr.length);
                for (long v : arr) {
                    out.writeLong(v);
                }
            }
            default -> throw new IOException("不支持的 NBT 类型 ID：" + tag.getId());
        }
    }

    private static Tag readTagPayload(DataInputStream in, byte typeId, int depth, int maxLen) throws IOException {
        if (depth > MAX_NESTING_DEPTH) {
            throw new IOException("NBT 嵌套层数超限：" + depth);
        }
        return switch (typeId) {
            case Tag.TAG_BYTE -> ByteTag.valueOf(in.readByte());
            case Tag.TAG_SHORT -> ShortTag.valueOf(in.readShort());
            case Tag.TAG_INT -> IntTag.valueOf(in.readInt());
            case Tag.TAG_LONG -> LongTag.valueOf(in.readLong());
            case Tag.TAG_FLOAT -> FloatTag.valueOf(in.readFloat());
            case Tag.TAG_DOUBLE -> DoubleTag.valueOf(in.readDouble());
            case Tag.TAG_BYTE_ARRAY -> {
                int len = in.readInt();
                if (len < 0 || len > maxLen) {
                    throw new IOException("ByteArrayTag 长度越界：" + len);
                }
                byte[] arr = new byte[len];
                in.readFully(arr);
                yield new ByteArrayTag(arr);
            }
            case Tag.TAG_STRING -> StringTag.valueOf(readLongString(in, maxLen));
            case Tag.TAG_LIST -> {
                byte elemType = in.readByte();
                int size = in.readInt();
                if (size < 0 || size > 1_000_000) {
                    throw new IOException("ListTag 长度异常：" + size);
                }
                ListTag list = new ListTag();
                for (int i = 0; i < size; i++) {
                    Tag elem = readTagPayload(in, elemType, depth + 1, maxLen);
                    if (elem != null) {
                        list.add(elem);
                    }
                }
                yield list;
            }
            case Tag.TAG_COMPOUND -> readCompound(in, depth, maxLen);
            case Tag.TAG_INT_ARRAY -> {
                int len = in.readInt();
                if (len < 0 || len > maxLen / 4) {
                    throw new IOException("IntArrayTag 长度越界：" + len);
                }
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = in.readInt();
                }
                yield new IntArrayTag(arr);
            }
            case Tag.TAG_LONG_ARRAY -> {
                int len = in.readInt();
                if (len < 0 || len > maxLen / 8) {
                    throw new IOException("LongArrayTag 长度越界：" + len);
                }
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = in.readLong();
                }
                yield new LongArrayTag(arr);
            }
            default -> throw new IOException("不支持的 NBT 类型 ID：" + typeId);
        };
    }
}
