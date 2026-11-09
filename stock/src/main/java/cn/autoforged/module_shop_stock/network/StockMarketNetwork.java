package cn.autoforged.module_shop_stock.network;

import cn.autoforged.module_shop_stock.StockMarketMod;
import cn.autoforged.module_shop_stock.client.StockMarketClient;
import cn.autoforged.module_shop_stock.server.StockServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class StockMarketNetwork {
    public static final String CHANNEL_VERSION = "3";

    private StockMarketNetwork() {
    }

    public record Request(CompoundTag tag) implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(StockMarketMod.MODID, "request"));
        public static final StreamCodec<FriendlyByteBuf, Request> CODEC =
                ByteBufCodecs.COMPOUND_TAG.map(Request::new, Request::tag).cast();

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Response(CompoundTag tag) implements CustomPacketPayload {
        public static final Type<Response> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(StockMarketMod.MODID, "response"));
        public static final StreamCodec<FriendlyByteBuf, Response> CODEC =
                ByteBufCodecs.COMPOUND_TAG.map(Response::new, Response::tag).cast();

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(CHANNEL_VERSION);
        registrar.playToServer(Request.TYPE, Request.CODEC, StockMarketNetwork::handleRequest);
        registrar.playToClient(Response.TYPE, Response.CODEC, StockMarketNetwork::handleResponse);
    }

    private static void handleRequest(Request payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                StockServer.handleRequest(player, payload.tag());
            }
        });
    }

    private static void handleResponse(Response payload, IPayloadContext context) {
        context.enqueueWork(() -> StockMarketClient.handle(payload.tag()));
    }

    public static void sendToPlayer(ServerPlayer player, CompoundTag tag) {
        PacketDistributor.sendToPlayer(player, new Response(tag));
    }

    public static void sendToServer(CompoundTag tag) {
        PacketDistributor.sendToServer(new Request(tag));
    }
}
