package cn.autoforged.module_shop_redpacket;

import cn.autoforged.module_shop_redpacket.network.RedPacketNetwork;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 模块商店附属 · 红包。 */
@Mod(RedPacketMod.MODID)
public class RedPacketMod {
    public static final String MODID = "module_shop_redpacket";

    public RedPacketMod(IEventBus modBus, ModContainer container) {
        // 只依赖公共 API：主版本 1、次版本 ≥ 1（1.1 起才有侧栏扩展点 ShopModules）。
        // 注意 ApiVersion 的常量会被 javac 内联成「编译时的值」，所以这里读运行时的 describe()。
        checkApiVersion(cn.autoforged.module_shop.api.ApiVersion.describe());
        container.registerConfig(ModConfig.Type.SERVER, RedPacketConfig.SPEC);
        modBus.addListener(RedPacketNetwork::register);
        NeoForge.EVENT_BUS.addListener(RedPacketMod::onCommands);
        NeoForge.EVENT_BUS.addListener(RedPacketMod::onChat);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> RedPacketManager.tick(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> RedPacketManager.reset());
        if (FMLEnvironment.dist == Dist.CLIENT) {
            cn.autoforged.module_shop_redpacket.client.RedPacketClient.init(modBus);
        }
    }

    static void checkApiVersion(String described) {
        String version = described == null ? "" : described.substring(described.lastIndexOf(' ') + 1);
        String[] parts = version.split("\\.");
        int major = parts.length > 0 ? parseInt(parts[0]) : -1;
        int minor = parts.length > 1 ? parseInt(parts[1]) : -1;
        if (major != 1 || minor < 1) {
            throw new IllegalStateException("module_shop_redpacket 需要模块商店公共 API 1.1+，当前：" + described);
        }
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void onChat(ServerChatEvent event) {
        // 口令红包：聊天内容正好等于某个有效口令 → 领取（聊天照常发出，像群里发口令一样）。
        RedPacketManager.onChat(event.getPlayer(), event.getRawText());
    }

    private static void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("redpacket")
                .then(Commands.literal("claim")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> {
                                    RedPacketManager.claim(ctx.getSource().getPlayerOrException(),
                                            StringArgumentType.getString(ctx, "id"), false);
                                    return 1;
                                })))
                .then(Commands.literal("info")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> {
                                    RedPacketManager.sendDetail(ctx.getSource().getPlayerOrException(),
                                            StringArgumentType.getString(ctx, "id"));
                                    return 1;
                                })))
                // /redpacket send <个数> <总额> <lucky|even> [祝福语]：不开界面也能发（无口令）。
                .then(Commands.literal("send")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                .then(Commands.argument("total", LongArgumentType.longArg(1L))
                                        .then(Commands.literal("lucky")
                                                .executes(ctx -> sendCmd(ctx, true, ""))
                                                .then(Commands.argument("greeting", StringArgumentType.greedyString())
                                                        .executes(ctx -> sendCmd(ctx, true,
                                                                StringArgumentType.getString(ctx, "greeting")))))
                                        .then(Commands.literal("even")
                                                .executes(ctx -> sendCmd(ctx, false, ""))
                                                .then(Commands.argument("greeting", StringArgumentType.greedyString())
                                                        .executes(ctx -> sendCmd(ctx, false,
                                                                StringArgumentType.getString(ctx, "greeting")))))))));
    }

    private static int sendCmd(CommandContext<CommandSourceStack> ctx, boolean lucky, String greeting)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Component error = RedPacketManager.send(player, IntegerArgumentType.getInteger(ctx, "count"),
                LongArgumentType.getLong(ctx, "total"), lucky, "", greeting);
        if (error != null) {
            player.sendSystemMessage(error.copy().withStyle(ChatFormatting.RED));
            return 0;
        }
        return 1;
    }
}
