package com.atoms.Bshop.blind.network;

import com.atoms.Bshop.blind.BlindAuctionMod;
import com.atoms.Bshop.blind.server.BlindVaultEngine;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** 附属自有网络协议。客户端只发动作，服务端按玩家下发各自的快照（每人只看到自己的情报）。 */
public final class BlindAuctionNetwork {
    private static final String PROTOCOL = "7";

    public static final int SET_ZONE = 0;
    public static final int SET_ROLE = 1;
    public static final int USE_TOOL = 2;
    public static final int SET_SINGLE = 4;
    public static final int START = 5;
    public static final int CANCEL_QUEUE = 6;
    public static final int BID = 7;
    public static final int LEAVE_RESULT = 8;

    private BlindAuctionNetwork() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BlindAuctionMod.MODID, path));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar(PROTOCOL);
        r.playToServer(RequestSyncPacket.TYPE, RequestSyncPacket.CODEC, RequestSyncPacket::handle);
        r.playToServer(ActionPacket.TYPE, ActionPacket.CODEC, ActionPacket::handle);
        r.playToClient(SnapshotPacket.TYPE, SnapshotPacket.CODEC, SnapshotPacket::handle);
        r.playToClient(ToastPacket.TYPE, ToastPacket.CODEC, ToastPacket::handle);
    }

    public static void requestSync() {
        PacketDistributor.sendToServer(new RequestSyncPacket());
    }

    public static void sendAction(int action, int a, int b, long amount) {
        PacketDistributor.sendToServer(new ActionPacket(action, a, b, amount));
    }

    public static void sendSnapshot(ServerPlayer player, CompoundTag tag) {
        PacketDistributor.sendToPlayer(player, new SnapshotPacket(tag));
    }

    public static void sendToast(ServerPlayer player, String message) {
        PacketDistributor.sendToPlayer(player, new ToastPacket(message));
    }

    public record RequestSyncPacket() implements CustomPacketPayload {
        public static final Type<RequestSyncPacket> TYPE = BlindAuctionNetwork.type("request_sync");
        public static final StreamCodec<FriendlyByteBuf, RequestSyncPacket> CODEC =
                StreamCodec.of((buf, pkt) -> {}, buf -> new RequestSyncPacket());

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        static void handle(RequestSyncPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) BlindVaultEngine.syncPlayer(sp);
            });
        }
    }

    public record ActionPacket(int action, int a, int b, long amount) implements CustomPacketPayload {
        public static final Type<ActionPacket> TYPE = BlindAuctionNetwork.type("action");
        public static final StreamCodec<FriendlyByteBuf, ActionPacket> CODEC = StreamCodec.of(
                (buf, pkt) -> {
                    buf.writeVarInt(pkt.action);
                    buf.writeVarInt(pkt.a);
                    buf.writeVarInt(pkt.b);
                    buf.writeVarLong(pkt.amount);
                },
                buf -> new ActionPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        static void handle(ActionPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer sp)) return;
                switch (pkt.action) {
                    case SET_ZONE -> BlindVaultEngine.setZone(sp, pkt.a);
                    case SET_ROLE -> BlindVaultEngine.setRole(sp, pkt.a);
                    case USE_TOOL -> BlindVaultEngine.useTool(sp, pkt.a, pkt.b);
                    case SET_SINGLE -> BlindVaultEngine.setSingle(sp, pkt.a == 1);
                    case START -> BlindVaultEngine.startMatch(sp);
                    case CANCEL_QUEUE -> BlindVaultEngine.cancelQueue(sp);
                    case BID -> BlindVaultEngine.submitBid(sp, pkt.amount);
                    case LEAVE_RESULT -> BlindVaultEngine.leaveResult(sp);
                    default -> { }
                }
            });
        }
    }

    public record SnapshotPacket(CompoundTag tag) implements CustomPacketPayload {
        public static final Type<SnapshotPacket> TYPE = BlindAuctionNetwork.type("snapshot");
        public static final StreamCodec<FriendlyByteBuf, SnapshotPacket> CODEC = StreamCodec.of(
                (buf, pkt) -> buf.writeNbt(pkt.tag), buf -> new SnapshotPacket(buf.readNbt()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        static void handle(SnapshotPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.atoms.Bshop.blind.client.BlindAuctionState.apply(pkt.tag));
        }
    }

    public record ToastPacket(String message) implements CustomPacketPayload {
        public static final Type<ToastPacket> TYPE = BlindAuctionNetwork.type("toast");
        public static final StreamCodec<FriendlyByteBuf, ToastPacket> CODEC = StreamCodec.of(
                (buf, pkt) -> buf.writeUtf(pkt.message, 200), buf -> new ToastPacket(buf.readUtf(200)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        static void handle(ToastPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.atoms.Bshop.blind.client.BlindAuctionState.pushToast(pkt.message));
        }
    }
}
