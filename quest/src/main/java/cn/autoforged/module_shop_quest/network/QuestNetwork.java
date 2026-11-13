package cn.autoforged.module_shop_quest.network;

import cn.autoforged.module_shop_quest.QuestMod;
import cn.autoforged.module_shop_quest.config.QuestTomlConfig;
import cn.autoforged.module_shop_quest.server.BattlePassService;
import cn.autoforged.module_shop_quest.server.OnlineTimeService;
import cn.autoforged.module_shop_quest.server.QuestAdminService;
import cn.autoforged.module_shop_quest.server.QuestManager;
import java.util.ArrayList;
import java.util.List;
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
 * 通行证与任务独立网络通道（协议版本 {@code "9"}）。
 */
public final class QuestNetwork {
    public static final String PROTOCOL_VERSION = "9";

    private QuestNetwork() {
    }

    public record Request(CompoundTag tag) implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(QuestMod.MODID, "request"));
        public static final StreamCodec<FriendlyByteBuf, Request> CODEC =
                ByteBufCodecs.COMPOUND_TAG.map(Request::new, Request::tag).cast();

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Response(CompoundTag tag) implements CustomPacketPayload {
        public static final Type<Response> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(QuestMod.MODID, "response"));
        public static final StreamCodec<FriendlyByteBuf, Response> CODEC =
                ByteBufCodecs.COMPOUND_TAG.map(Response::new, Response::tag).cast();

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

    private static void handleRequest(Request payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
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
        });
    }

    private static void handleResponse(Response payload, IPayloadContext context) {
        context.enqueueWork(() -> cn.autoforged.module_shop_quest.client.QuestClient.handlePacket(payload.tag()));
    }

    public static void sendToPlayer(ServerPlayer player, CompoundTag tag) {
        PacketDistributor.sendToPlayer(player, new Response(tag));
    }

    public static void sendToServer(CompoundTag tag) {
        PacketDistributor.sendToServer(new Request(tag));
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
