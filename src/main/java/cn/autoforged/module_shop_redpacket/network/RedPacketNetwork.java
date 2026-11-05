package cn.autoforged.module_shop_redpacket.network;

import cn.autoforged.module_shop_redpacket.RedPacketManager;
import cn.autoforged.module_shop_redpacket.RedPacketMod;
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

/**
 * 两个通用 NBT 包：
 * <ul>
 *     <li>C2S {@link Request}：{@code type = status}（打开发红包界面要余额）/ {@code send}（发红包）；</li>
 *     <li>S2C {@link Response}：{@code type = status / send_result / detail}。</li>
 * </ul>
 */
public final class RedPacketNetwork {
    private RedPacketNetwork() {
    }

    public record Request(CompoundTag tag) implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(RedPacketMod.MODID, "request"));
        public static final StreamCodec<FriendlyByteBuf, Request> CODEC =
                ByteBufCodecs.COMPOUND_TAG.map(Request::new, Request::tag).cast();

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Response(CompoundTag tag) implements CustomPacketPayload {
        public static final Type<Response> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(RedPacketMod.MODID, "response"));
        public static final StreamCodec<FriendlyByteBuf, Response> CODEC =
                ByteBufCodecs.COMPOUND_TAG.map(Response::new, Response::tag).cast();

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(Request.TYPE, Request.CODEC, RedPacketNetwork::handleRequest);
        registrar.playToClient(Response.TYPE, Response.CODEC, RedPacketNetwork::handleResponse);
    }

    private static void handleRequest(Request payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            CompoundTag tag = payload.tag();
            switch (tag.getString("type")) {
                case "status" -> RedPacketManager.sendStatus(player);
                case "send" -> {
                    var error = RedPacketManager.send(player, tag.getInt("count"), tag.getLong("total"),
                            tag.getBoolean("lucky"), tag.getString("password"), tag.getString("greeting"));
                    CompoundTag result = new CompoundTag();
                    result.putString("type", "send_result");
                    result.putBoolean("ok", error == null);
                    if (error != null) {
                        result.putString("error", net.minecraft.network.chat.Component.Serializer.toJson(
                                error, player.registryAccess()));
                    }
                    sendToPlayer(player, result);
                }
                default -> {
                }
            }
        });
    }

    // 客户端处理类只在 lambda 里触达，专用服务器不会加载 Screen。
    private static void handleResponse(Response payload, IPayloadContext context) {
        context.enqueueWork(() -> cn.autoforged.module_shop_redpacket.client.RedPacketClient.handle(payload.tag()));
    }

    public static void sendToPlayer(ServerPlayer player, CompoundTag tag) {
        PacketDistributor.sendToPlayer(player, new Response(tag));
    }

    public static void sendToServer(CompoundTag tag) {
        PacketDistributor.sendToServer(new Request(tag));
    }
}
