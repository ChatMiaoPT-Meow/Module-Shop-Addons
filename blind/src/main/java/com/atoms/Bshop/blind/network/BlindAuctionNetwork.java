package com.atoms.Bshop.blind.network;

import com.atoms.Bshop.blind.BlindAuctionMod;
import com.atoms.Bshop.blind.server.BlindVaultEngine;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
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

/** 附属自有网络协议。客户端只发动作，服务端按玩家下发各自的快照（每人只看到自己的情报）。 */
public final class BlindAuctionNetwork {
    private static final String PROTOCOL = "8";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BlindAuctionMod.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static int packetId = 0;

    public static final int SET_ZONE = 0;
    public static final int SET_ROLE = 1;
    public static final int USE_TOOL = 2;
    public static final int SET_SINGLE = 4;
    public static final int START = 5;
    public static final int CANCEL_QUEUE = 6;
    public static final int BID = 7;
    public static final int LEAVE_RESULT = 8;

    private BlindAuctionNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(packetId++, RequestSyncPacket.class, RequestSyncPacket::encode,
                RequestSyncPacket::decode, RequestSyncPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(packetId++, ActionPacket.class, ActionPacket::encode,
                ActionPacket::decode, ActionPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(packetId++, SnapshotPacket.class, SnapshotPacket::encode,
                SnapshotPacket::decode, SnapshotPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(packetId++, ToastPacket.class, ToastPacket::encode,
                ToastPacket::decode, ToastPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void requestSync() {
        CHANNEL.sendToServer(new RequestSyncPacket());
    }

    public static void sendAction(int action, int a, int b, long amount) {
        CHANNEL.sendToServer(new ActionPacket(action, a, b, amount));
    }

    public static void sendSnapshot(ServerPlayer player, CompoundTag tag) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SnapshotPacket(tag));
    }

    public static void sendToast(ServerPlayer player, String message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ToastPacket(message));
    }

    public record RequestSyncPacket() {
        static void encode(RequestSyncPacket pkt, FriendlyByteBuf buf) {}

        static RequestSyncPacket decode(FriendlyByteBuf buf) {
            return new RequestSyncPacket();
        }

        static void handle(RequestSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sp = ctx.get().getSender();
                if (sp != null) BlindVaultEngine.syncPlayer(sp);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public record ActionPacket(int action, int a, int b, long amount) {
        static void encode(ActionPacket pkt, FriendlyByteBuf buf) {
            buf.writeVarInt(pkt.action);
            buf.writeVarInt(pkt.a);
            buf.writeVarInt(pkt.b);
            buf.writeVarLong(pkt.amount);
        }

        static ActionPacket decode(FriendlyByteBuf buf) {
            return new ActionPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarLong());
        }

        static void handle(ActionPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sp = ctx.get().getSender();
                if (sp == null) return;
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
            ctx.get().setPacketHandled(true);
        }
    }

    public record SnapshotPacket(CompoundTag tag) {
        static void encode(SnapshotPacket pkt, FriendlyByteBuf buf) {
            buf.writeNbt(pkt.tag);
        }

        static SnapshotPacket decode(FriendlyByteBuf buf) {
            CompoundTag tag = buf.readNbt();
            return new SnapshotPacket(tag == null ? new CompoundTag() : tag);
        }

        static void handle(SnapshotPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    com.atoms.Bshop.blind.client.BlindAuctionState.apply(pkt.tag)));
            ctx.get().setPacketHandled(true);
        }
    }

    public record ToastPacket(String message) {
        static void encode(ToastPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.message, 200);
        }

        static ToastPacket decode(FriendlyByteBuf buf) {
            return new ToastPacket(buf.readUtf(200));
        }

        static void handle(ToastPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    com.atoms.Bshop.blind.client.BlindAuctionState.pushToast(pkt.message)));
            ctx.get().setPacketHandled(true);
        }
    }
}
