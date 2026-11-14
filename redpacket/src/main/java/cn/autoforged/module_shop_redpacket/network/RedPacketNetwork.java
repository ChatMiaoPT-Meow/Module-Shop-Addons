package cn.autoforged.module_shop_redpacket.network;

import cn.autoforged.module_shop_redpacket.RedPacketManager;
import cn.autoforged.module_shop_redpacket.RedPacketMod;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 两个通用 NBT 包（Forge {@link SimpleChannel}）：
 * <ul>
 *     <li>C2S {@link Request}：{@code type = status}（打开发红包界面要余额）/ {@code send}（发红包）；</li>
 *     <li>S2C {@link Response}：{@code type = status / send_result / detail}。</li>
 * </ul>
 * 协议版本固定：新旧 jar 混用时 Forge 会在登录时直接提示版本不一致。
 */
public final class RedPacketNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(RedPacketMod.MODID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();

    private RedPacketNetwork() {
    }

    public record Request(CompoundTag tag) {
        static void encode(Request msg, FriendlyByteBuf buf) {
            buf.writeNbt(msg.tag());
        }

        static Request decode(FriendlyByteBuf buf) {
            CompoundTag tag = buf.readNbt();
            return new Request(tag == null ? new CompoundTag() : tag);
        }
    }

    public record Response(CompoundTag tag) {
        static void encode(Response msg, FriendlyByteBuf buf) {
            buf.writeNbt(msg.tag());
        }

        static Response decode(FriendlyByteBuf buf) {
            CompoundTag tag = buf.readNbt();
            return new Response(tag == null ? new CompoundTag() : tag);
        }
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(Request.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Request::encode).decoder(Request::decode)
                .consumerMainThread(RedPacketNetwork::handleRequest).add();
        CHANNEL.messageBuilder(Response.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Response::encode).decoder(Response::decode)
                .consumerMainThread(RedPacketNetwork::handleResponse).add();
    }

    private static void handleRequest(Request payload, Supplier<NetworkEvent.Context> context) {
        ServerPlayer player = context.get().getSender();
        context.get().setPacketHandled(true);
        if (player == null) {
            return;
        }
        CompoundTag tag = payload.tag();
        switch (tag.getString("type")) {
            case "status" -> RedPacketManager.sendStatus(player);
            case "send" -> {
                Component error = RedPacketManager.send(player, tag.getInt("count"), tag.getLong("total"),
                        tag.getBoolean("lucky"), tag.getString("password"), tag.getString("greeting"));
                CompoundTag result = new CompoundTag();
                result.putString("type", "send_result");
                result.putBoolean("ok", error == null);
                if (error != null) {
                    result.putString("error", Component.Serializer.toJson(error));
                }
                sendToPlayer(player, result);
            }
            default -> {
            }
        }
    }

    // 客户端处理类只在 DistExecutor 里触达，专用服务器不会加载 Screen。
    private static void handleResponse(Response payload, Supplier<NetworkEvent.Context> context) {
        context.get().setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                cn.autoforged.module_shop_redpacket.client.RedPacketClient.handle(payload.tag()));
    }

    public static void sendToPlayer(ServerPlayer player, CompoundTag tag) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Response(tag));
    }

    public static void sendToServer(CompoundTag tag) {
        CHANNEL.sendToServer(new Request(tag));
    }
}
