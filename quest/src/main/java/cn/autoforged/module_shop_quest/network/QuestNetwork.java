package cn.autoforged.module_shop_quest.network;

import cn.autoforged.module_shop_quest.QuestMod;
import cn.autoforged.module_shop_quest.config.QuestTomlConfig;
import cn.autoforged.module_shop_quest.server.BattlePassService;
import cn.autoforged.module_shop_quest.server.OnlineTimeService;
import cn.autoforged.module_shop_quest.server.QuestAdminService;
import cn.autoforged.module_shop_quest.server.QuestManager;
import java.util.ArrayList;
import java.util.List;
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

/**
 * 通行证与任务独立网络通道（Forge {@link SimpleChannel}，协议版本 {@code "9"}）。
 */
public final class QuestNetwork {
    public static final String PROTOCOL_VERSION = "9";
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(QuestMod.MODID, "main"))
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .simpleChannel();

    private QuestNetwork() {
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
                .consumerMainThread(QuestNetwork::handleRequest).add();
        CHANNEL.messageBuilder(Response.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Response::encode).decoder(Response::decode)
                .consumerMainThread(QuestNetwork::handleResponse).add();
    }

    private static void handleRequest(Request payload, Supplier<NetworkEvent.Context> context) {
        ServerPlayer player = context.get().getSender();
        context.get().setPacketHandled(true);
        if (player == null) {
            return;
        }
        if (!QuestTomlConfig.ENABLED.get()) {
            sendActionResult(player, false, "msg.module_shop_quest.disabled", "error", false);
            return;
        }
        CompoundTag tag = payload.tag();
        String action = tag.getString("action");
        boolean isPingOnly = "ui_ping".equals(action) || "sync".equals(action);
        OnlineTimeService.touchUiActivity(player, !isPingOnly);

        switch (action) {
            case "sync" -> QuestManager.syncFullSnapshot(player);
            case "ui_ping" -> {
            }
            case "claim_quest" -> QuestManager.handleClaimQuest(player, tag.getString("period"), tag.getString("questId"));
            case "claim_online" -> QuestManager.handleClaimOnline(player, tag.getString("period"), tag.getString("tierId"));
            case "claim_pass" -> BattlePassService.handleClaimPassLevel(
                    player, tag.getInt("level"), tag.getBoolean("premium"), tag.getBoolean("prevSeason"));
            case "claim_all" -> QuestManager.handleClaimAll(player);
            case "unlock_premium" -> BattlePassService.handleUnlockTier(player, false);
            case "unlock_deluxe" -> BattlePassService.handleUnlockTier(player, true);
            case "buy_levels" -> BattlePassService.handleBuyLevels(player, Math.max(1, tag.getInt("count")));
            case "admin_fetch_config" -> QuestAdminService.handleFetchConfig(player);
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

    private static void handleResponse(Response payload, Supplier<NetworkEvent.Context> context) {
        context.get().setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                cn.autoforged.module_shop_quest.client.QuestClient.handlePacket(payload.tag()));
    }

    public static void sendToPlayer(ServerPlayer player, CompoundTag tag) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Response(tag));
    }

    public static void sendToServer(CompoundTag tag) {
        CHANNEL.sendToServer(new Request(tag));
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
}
