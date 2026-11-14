package cn.autoforged.module_shop_stock.network;

import cn.autoforged.module_shop_stock.StockMarketMod;
import cn.autoforged.module_shop_stock.server.StockServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 股市网络层（Forge 1.20.1 {@link SimpleChannel}）：
 * 大体积快照与 K 线数据先经过 gzip 压缩并按 {@value #CHUNK_SIZE} 字节分片，
 * 接收端重组后再通过自定义 {@link NbtAccounter} 上限解压，避免触发原版 2 MiB {@code readNbt()} 上限。
 */
public final class StockMarketNetwork {
    private static final Logger LOGGER = LoggerFactory.getLogger("module_shop_stock/network");
    public static final String CHANNEL_VERSION = "3";

    private static final int CHUNK_SIZE = 512 * 1024;
    private static final long MAX_CLIENTBOUND_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_SERVERBOUND_BYTES = 8L * 1024L * 1024L;

    private static final AtomicInteger NEXT_SYNC_ID = new AtomicInteger(1);
    private static final ChunkAssembler CLIENT_ASSEMBLER = new ChunkAssembler(MAX_CLIENTBOUND_BYTES);
    private static final Map<UUID, ChunkAssembler> SERVER_ASSEMBLERS = new ConcurrentHashMap<>();

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(StockMarketMod.MODID, "main"))
            .networkProtocolVersion(() -> CHANNEL_VERSION)
            .clientAcceptedVersions(CHANNEL_VERSION::equals)
            .serverAcceptedVersions(CHANNEL_VERSION::equals)
            .simpleChannel();

    private StockMarketNetwork() {
    }

    public record Request(int syncId, int index, int total, byte[] data) {
        static void encode(Request msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.syncId);
            buf.writeVarInt(msg.index);
            buf.writeVarInt(msg.total);
            buf.writeByteArray(msg.data);
        }

        static Request decode(FriendlyByteBuf buf) {
            return new Request(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readByteArray(CHUNK_SIZE + 1024));
        }
    }

    public record Response(int syncId, int index, int total, byte[] data) {
        static void encode(Response msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.syncId);
            buf.writeVarInt(msg.index);
            buf.writeVarInt(msg.total);
            buf.writeByteArray(msg.data);
        }

        static Response decode(FriendlyByteBuf buf) {
            return new Response(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readByteArray(CHUNK_SIZE + 1024));
        }
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(Request.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Request::encode)
                .decoder(Request::decode)
                .consumerMainThread(StockMarketNetwork::handleRequest)
                .add();
        CHANNEL.messageBuilder(Response.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Response::encode)
                .decoder(Response::decode)
                .consumerMainThread(StockMarketNetwork::handleResponse)
                .add();
    }

    private static void handleRequest(Request payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.setPacketHandled(true);
        ServerPlayer player = ctx.getSender();
        if (player == null) {
            return;
        }
        UUID key = player.getUUID();
        ChunkAssembler assembler = SERVER_ASSEMBLERS.computeIfAbsent(key,
                ignored -> new ChunkAssembler(MAX_SERVERBOUND_BYTES));
        byte[] raw = assembler.accept(payload.syncId(), payload.index(), payload.total(), payload.data());
        if (raw == null) {
            return;
        }
        if (assembler.isEmpty()) {
            SERVER_ASSEMBLERS.remove(key, assembler);
        }
        CompoundTag tag = decompress(raw, "serverbound", MAX_SERVERBOUND_BYTES);
        if (tag != null) {
            StockServer.handleRequest(player, tag);
        }
    }

    private static void handleResponse(Response payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.setPacketHandled(true);
        byte[] raw = CLIENT_ASSEMBLER.accept(payload.syncId(), payload.index(), payload.total(), payload.data());
        if (raw == null) {
            return;
        }
        CompoundTag tag = decompress(raw, "clientbound", MAX_CLIENTBOUND_BYTES);
        if (tag == null) {
            return;
        }
        // 客户端处理类只在 DistExecutor 里触达，避免专用服务器加载客户端 UI 类导致崩溃。
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> cn.autoforged.module_shop_stock.client.StockMarketClient.handle(tag));
    }

    public static void sendToPlayer(ServerPlayer player, CompoundTag tag) {
        byte[] raw = compress(tag);
        int syncId = NEXT_SYNC_ID.getAndIncrement();
        int total = chunkCount(raw.length);
        for (int i = 0; i < total; i++) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new Response(syncId, i, total, slice(raw, i)));
        }
    }

    public static void sendToServer(CompoundTag tag) {
        byte[] raw = compress(tag);
        int syncId = NEXT_SYNC_ID.getAndIncrement();
        int total = chunkCount(raw.length);
        for (int i = 0; i < total; i++) {
            CHANNEL.sendToServer(new Request(syncId, i, total, slice(raw, i)));
        }
    }

    public static void forgetPlayer(UUID playerId) {
        if (playerId != null) {
            SERVER_ASSEMBLERS.remove(playerId);
        }
    }

    public static void forgetClient() {
        CLIENT_ASSEMBLER.clear();
    }

    public static void clearAssemblers() {
        SERVER_ASSEMBLERS.clear();
    }

    private static byte[] compress(CompoundTag tag) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        try {
            NbtIo.writeCompressed(tag == null ? new CompoundTag() : tag, out);
        } catch (IOException e) {
            throw new IllegalStateException("module_shop_stock：压缩同步数据失败", e);
        }
        return out.toByteArray();
    }

    private static CompoundTag decompress(byte[] raw, String direction, long maxTagBytes) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(raw)))) {
            return NbtIo.read(in, new NbtAccounter(maxTagBytes));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("module_shop_stock：忽略无法解析的 {} 数据包（{} 字节）：{}",
                    direction, raw.length, e.toString());
            return null;
        }
    }

    private static int chunkCount(int length) {
        return Math.max(1, (length + CHUNK_SIZE - 1) / CHUNK_SIZE);
    }

    private static byte[] slice(byte[] raw, int index) {
        int from = index * CHUNK_SIZE;
        int to = Math.min(raw.length, from + CHUNK_SIZE);
        if (from <= 0 && to >= raw.length) {
            return raw;
        }
        return Arrays.copyOfRange(raw, from, to);
    }

    private static final class ChunkAssembler {
        private static final int MAX_GROUPS = 4;
        private static final long TIMEOUT_MS = 30_000L;

        private final Map<Integer, Group> groups = new ConcurrentHashMap<>();
        private final long maxTotal;

        private ChunkAssembler(long maxTotal) {
            this.maxTotal = maxTotal;
        }

        byte[] accept(int id, int index, int total, byte[] part) {
            if (part == null || total <= 0 || index < 0 || index >= total) {
                return null;
            }
            if (total == 1) {
                return part.length > this.maxTotal ? null : part;
            }
            long now = System.currentTimeMillis();
            this.groups.entrySet().removeIf(e -> now - e.getValue().stamp > TIMEOUT_MS);
            if (this.groups.size() >= MAX_GROUPS && !this.groups.containsKey(id)) {
                evictOldest();
            }
            Group group = this.groups.computeIfAbsent(id, k -> new Group(total, now));
            synchronized (group) {
                if (group.parts.length != total) {
                    this.groups.remove(id);
                    return null;
                }
                group.stamp = now;
                if (group.parts[index] == null) {
                    group.parts[index] = part;
                    group.received++;
                    group.size += part.length;
                }
                if (group.size > this.maxTotal) {
                    this.groups.remove(id);
                    return null;
                }
                if (group.received < total) {
                    return null;
                }
                this.groups.remove(id);
                byte[] all = new byte[(int) group.size];
                int offset = 0;
                for (byte[] piece : group.parts) {
                    if (piece == null) {
                        return null;
                    }
                    System.arraycopy(piece, 0, all, offset, piece.length);
                    offset += piece.length;
                }
                return all;
            }
        }

        boolean isEmpty() {
            return this.groups.isEmpty();
        }

        void clear() {
            this.groups.clear();
        }

        private void evictOldest() {
            int oldestId = Integer.MIN_VALUE;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<Integer, Group> entry : this.groups.entrySet()) {
                if (entry.getValue().stamp < oldest) {
                    oldest = entry.getValue().stamp;
                    oldestId = entry.getKey();
                }
            }
            if (oldestId != Integer.MIN_VALUE) {
                this.groups.remove(oldestId);
            }
        }

        private static final class Group {
            private final byte[][] parts;
            private long stamp;
            private int received;
            private long size;

            private Group(int total, long stamp) {
                this.parts = new byte[total][];
                this.stamp = stamp;
            }
        }
    }
}
