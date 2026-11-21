package cn.autoforged.module_shop_quest.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 纯 Java GZIP 压缩、无 65535 限制长字符串二进制编码与分片重组核心（零 Minecraft 依赖，支持离线单元测试）。
 */
public final class ChunkedPayloadCodec {
    public static final int MAX_S2C_CHUNK_BYTES = 240 * 1024;
    public static final int MAX_C2S_CHUNK_BYTES = 20 * 1024;
    public static final int MAX_C2S_DECOMPRESSED_BYTES = 16 * 1024 * 1024;
    public static final int MAX_S2C_DECOMPRESSED_BYTES = 32 * 1024 * 1024;
    public static final int MAX_C2S_CHUNKS = 512;
    public static final int MAX_S2C_CHUNKS = 256;
    public static final long REASSEMBLY_TIMEOUT_MS = 30_000L;

    private static final int STRING_MAP_MAGIC = 0x4D535153; // "MSQS"

    private ChunkedPayloadCodec() {
    }

    public static byte[] gzipCompress(byte[] raw) throws IOException {
        byte[] safe = raw == null ? new byte[0] : raw;
        ByteArrayOutputStream gzBaos = new ByteArrayOutputStream(Math.max(256, safe.length / 3));
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzBaos)) {
            gzip.write(safe);
            gzip.finish();
        }
        return gzBaos.toByteArray();
    }

    public static byte[] gzipDecompress(byte[] compressed, int maxDecompressedBytes) throws IOException {
        if (compressed == null || compressed.length == 0) {
            return new byte[0];
        }
        int limit = Math.max(1024, maxDecompressedBytes);
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, Math.max(1024, compressed.length * 4)))) {
            byte[] buf = new byte[8192];
            int total = 0;
            int read;
            while ((read = gis.read(buf)) >= 0) {
                total += read;
                if (total > limit) {
                    throw new IOException("解压载荷超过安全上限：" + total + " > " + limit);
                }
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        }
    }

    public static byte[] gzipString(String value) throws IOException {
        String safe = value == null ? "" : value;
        return gzipCompress(safe.getBytes(StandardCharsets.UTF_8));
    }

    public static String gunzipString(byte[] compressed, int maxDecompressedBytes) throws IOException {
        byte[] raw = gzipDecompress(compressed, maxDecompressedBytes);
        return new String(raw, StandardCharsets.UTF_8);
    }

    public static void writeLongString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public static String readLongString(DataInputStream in, int maxLen) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > maxLen) {
            throw new IOException("字符串长度越界：" + len);
        }
        if (len == 0) {
            return "";
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static byte[] encodeStringMapCompressed(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream rawBaos = new ByteArrayOutputStream(1024);
        try (DataOutputStream dos = new DataOutputStream(rawBaos)) {
            dos.writeInt(STRING_MAP_MAGIC);
            int size = entries == null ? 0 : entries.size();
            dos.writeInt(size);
            if (entries != null) {
                for (Map.Entry<String, String> e : entries.entrySet()) {
                    writeLongString(dos, e.getKey());
                    writeLongString(dos, e.getValue());
                }
            }
        }
        return gzipCompress(rawBaos.toByteArray());
    }

    public static Map<String, String> decodeStringMapCompressed(byte[] compressed, int maxDecompressedBytes) throws IOException {
        byte[] raw = gzipDecompress(compressed, maxDecompressedBytes);
        Map<String, String> out = new LinkedHashMap<>();
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(raw))) {
            int magic = dis.readInt();
            if (magic != STRING_MAP_MAGIC) {
                throw new IOException("未知的字符串映射载荷魔数：0x" + Integer.toHexString(magic));
            }
            int size = dis.readInt();
            if (size < 0 || size > 100_000) {
                throw new IOException("映射项数量异常：" + size);
            }
            for (int i = 0; i < size; i++) {
                String k = readLongString(dis, maxDecompressedBytes);
                String v = readLongString(dis, maxDecompressedBytes);
                out.put(k, v);
            }
        }
        return out;
    }

    public static List<byte[]> splitChunks(byte[] data, int maxChunkBytes) {
        if (data == null || data.length == 0) {
            return List.of(new byte[0]);
        }
        int chunkSize = Math.max(1024, maxChunkBytes);
        if (data.length <= chunkSize) {
            return List.of(data);
        }
        int total = (data.length + chunkSize - 1) / chunkSize;
        List<byte[]> chunks = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int from = i * chunkSize;
            int to = Math.min(data.length, from + chunkSize);
            chunks.add(Arrays.copyOfRange(data, from, to));
        }
        return chunks;
    }

    /** 线程安全分片重组器（带乱序重组、超时自动清理与单用户并发配额保护）。 */
    public static final class ChunkAssembler {
        private final int maxTotalChunks;
        private final int maxPendingPerOwner;
        private final long timeoutMs;
        private final Map<String, PendingTransfer> pending = new ConcurrentHashMap<>();

        private static final class PendingTransfer {
            final String owner;
            final int totalChunks;
            final byte[][] parts;
            int receivedCount = 0;
            int totalBytes = 0;
            long lastTouchMs;

            PendingTransfer(String owner, int totalChunks, long nowMs) {
                this.owner = owner;
                this.totalChunks = totalChunks;
                this.parts = new byte[totalChunks][];
                this.lastTouchMs = nowMs;
            }
        }

        public ChunkAssembler(int maxTotalChunks, int maxPendingPerOwner, long timeoutMs) {
            this.maxTotalChunks = Math.max(1, maxTotalChunks);
            this.maxPendingPerOwner = Math.max(1, maxPendingPerOwner);
            this.timeoutMs = Math.max(1000L, timeoutMs);
        }

        public synchronized byte[] accept(String owner, int transferId, int chunkIndex, int totalChunks,
                                          byte[] data, long nowMs) throws IOException {
            evictExpired(nowMs);
            if (totalChunks <= 0 || totalChunks > maxTotalChunks) {
                throw new IOException("非法分片总数：" + totalChunks);
            }
            if (chunkIndex < 0 || chunkIndex >= totalChunks) {
                throw new IOException("非法分片序号：" + chunkIndex + "/" + totalChunks);
            }
            byte[] safeData = data == null ? new byte[0] : data;
            if (totalChunks == 1) {
                return safeData;
            }

            String key = owner + ":" + transferId;
            PendingTransfer entry = pending.get(key);
            if (entry == null) {
                enforceOwnerQuota(owner);
                entry = new PendingTransfer(owner, totalChunks, nowMs);
                pending.put(key, entry);
            } else if (entry.totalChunks != totalChunks) {
                pending.remove(key);
                throw new IOException("分片总数不一致：" + totalChunks + " != " + entry.totalChunks);
            }

            entry.lastTouchMs = nowMs;
            if (entry.parts[chunkIndex] == null) {
                entry.parts[chunkIndex] = safeData;
                entry.receivedCount++;
                entry.totalBytes += safeData.length;
            }
            if (entry.receivedCount < entry.totalChunks) {
                return null;
            }

            pending.remove(key);
            byte[] merged = new byte[entry.totalBytes];
            int offset = 0;
            for (byte[] part : entry.parts) {
                System.arraycopy(part, 0, merged, offset, part.length);
                offset += part.length;
            }
            return merged;
        }

        public synchronized int pendingCount() {
            return pending.size();
        }

        public synchronized void clearOwner(String owner) {
            pending.entrySet().removeIf(e -> owner.equals(e.getValue().owner));
        }

        public synchronized void clearAll() {
            pending.clear();
        }

        private void evictExpired(long nowMs) {
            Iterator<Map.Entry<String, PendingTransfer>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                if (nowMs - it.next().getValue().lastTouchMs > timeoutMs) {
                    it.remove();
                }
            }
        }

        private void enforceOwnerQuota(String owner) {
            int count = 0;
            String oldestKey = null;
            long oldestMs = Long.MAX_VALUE;
            for (Map.Entry<String, PendingTransfer> e : pending.entrySet()) {
                if (owner.equals(e.getValue().owner)) {
                    count++;
                    if (e.getValue().lastTouchMs < oldestMs) {
                        oldestMs = e.getValue().lastTouchMs;
                        oldestKey = e.getKey();
                    }
                }
            }
            if (count >= maxPendingPerOwner && oldestKey != null) {
                pending.remove(oldestKey);
            }
        }
    }
}
