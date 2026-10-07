package com.mtrmap;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mtrmap.config.MtrMapConfig;
import com.mtrmap.server.AvatarHandler;
import com.mtrmap.server.MapDataCollector;
import com.mtrmap.server.MapHttpServer;
import com.mtrmap.server.MapRequestRouter;
import com.mtrmap.server.NavTaskStore;
import com.mtrmap.server.PlayerTracker;
import com.mtrmap.server.RailPathFinder;
import com.mtrmap.server.TripStore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

// MC 1.17 起自带了 slf4j-api；1.16.5 只有 log4j2，所以日志门面按版本二选一。
// 两边的 Logger 都有 info/warn/error/debug 与 {} 占位符，调用处无需分支。
//? if >=1.17 {
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//?} else {
/*import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
*///?}

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * MTR 地图模组在服务端（及通用侧）的逻辑，与具体加载器无关。
 *
 * <p>在服务器启动时启动 HTTP 服务器（端口由 mods/mapconfig/mtrmap.json 配置，默认 1145），
 * 在服务器关闭时停止；每个 tick 末刷新玩家位置；提供
 * {@code /mtrmap showdepots <true|false>} 命令；并接收客户端经网络通道推送的头像。
 *
 * <p>该类的各个回调由平台模块（fabric / forge）的事件转接器调用。
 */
public final class MtrMapCommon {

    //? if >=1.17 {
    public static final Logger LOGGER = LoggerFactory.getLogger("MTR Map");
    //?} else {
    /*public static final Logger LOGGER = LogManager.getLogger("MTR Map");
    *///?}

    /** 客户端推送头像所使用的网络通道 */
    //? if >=1.21.1 {
    /*public static final ResourceLocation AVATAR_CHANNEL =
            ResourceLocation.fromNamespaceAndPath("mtrmap", "avatar");
    *///?} else {
    public static final ResourceLocation AVATAR_CHANNEL = new ResourceLocation("mtrmap", "avatar");
    //?}

    /** 服务端把地图 HTTP 端口同步给客户端所使用的网络通道 */
    //? if >=1.21.1 {
    /*public static final ResourceLocation PORT_CHANNEL =
            ResourceLocation.fromNamespaceAndPath("mtrmap", "port");
    *///?} else {
    public static final ResourceLocation PORT_CHANNEL = new ResourceLocation("mtrmap", "port");
    //?}

    /** 游戏内地图窗口向服务端取数所使用的网络通道（客户端 -> 服务端） */
    //? if >=1.21.1 {
    /*public static final ResourceLocation MAP_REQUEST_CHANNEL =
            ResourceLocation.fromNamespaceAndPath("mtrmap", "map_request");
    *///?} else {
    public static final ResourceLocation MAP_REQUEST_CHANNEL = new ResourceLocation("mtrmap", "map_request");
    //?}

    /** 服务端分块回传地图数据所使用的网络通道（服务端 -> 客户端） */
    //? if >=1.21.1 {
    /*public static final ResourceLocation MAP_DATA_CHANNEL =
            ResourceLocation.fromNamespaceAndPath("mtrmap", "map_data");
    *///?} else {
    public static final ResourceLocation MAP_DATA_CHANNEL = new ResourceLocation("mtrmap", "map_data");
    //?}

    /**
     * 地图数据回包的分块大小（字节）。
     *
     * <p>线网大时整份 JSON 可达数百 KB，一次性塞进一个自定义包既会长时间占住网络线程，
     * 也容易撞上各加载器的包体积限制，所以按这个大小切片后再逐块发送。
     */
    public static final int MAP_CHUNK_SIZE = 30000;

    private static MinecraftServer currentServer;
    /** 端口顺延：配置端口与最终实际端口（相等或不曾启动时为 -1 表示无需提示） */
    private static int portShiftFrom = -1;
    private static int portShiftTo = -1;
    /** 连顺延的余地都没有、服务完全起不来时记下配置端口（-1 表示没有这种情况） */
    private static int portFailedPort = -1;
    /** 已经收到过端口提示的玩家，避免每 tick 重复刷屏 */
    private static final java.util.Set<UUID> portNoticeSent = new java.util.HashSet<>();
    /** 已经同步过地图端口的玩家，避免每 tick 重复发包 */
    private static final java.util.Set<UUID> portSynced = new java.util.HashSet<>();

    /**
     * 服务端同步过来的地图 HTTP 端口（只有客户端会用到；0 表示还没收到）。
     *
     * <p>专用服务端上地图服务跑在服务器那台机器上，客户端只知道服务器的地址、不知道地图服务监听
     * 的是哪个端口（服务器上的 mtrmap.json 客户端读不到），所以由服务端主动告知。
     * 单人游戏时服务端与客户端在同一个进程里，这个值与本地配置相同，行为不变。
     */
    private static volatile int serverMapPort = 0;

    /** 地图 HTTP 端口：服务端同步过就用它，否则退回本地配置（单人游戏时两者一致）。 */
    public static int getMapPort() {
        int synced = serverMapPort;
        return synced > 0 ? synced : MtrMapConfig.getActivePort();
    }

    /** 客户端收到服务端同步过来的地图端口。 */
    public static void onMapPortReceived(int port) {
        serverMapPort = port > 0 ? port : 0;
        LOGGER.info("收到服务端同步的地图服务端口：{}", port);
    }

    /** 断开连接：忘掉上一个服务器的端口，下次进服重新同步。 */
    public static void clearMapPort() {
        serverMapPort = 0;
    }

    private MtrMapCommon() {
    }

    /** 加载配置等通用初始化。 */
    public static void init() {
        LOGGER.info("正在初始化 MTR Map 模组...");
        MtrMapConfig.load();
        LOGGER.info("MTR Map 模组初始化完成");
    }

    /** 服务器启动：起 HTTP、采集线网数据。 */
    public static void onServerStarted(MinecraftServer server) {
        currentServer = server;
        PlayerTracker.clear();
        NavTaskStore.clear();
        TripStore.clear();
        RailPathFinder.clear();
        MapDataCollector.captureRailwayData(server);
        portShiftFrom = -1;
        portShiftTo = -1;
        portFailedPort = -1;
        portNoticeSent.clear();
        portSynced.clear();
        try {
            int configuredPort = MtrMapConfig.getPort();
            // 端口被占用时 start 会自动往后顺延，返回真正绑定成功的端口
            int port = MapHttpServer.start(configuredPort, server);
            MtrMapConfig.setActivePort(port);
            if (port != configuredPort) {
                // 顺延了：记下来，等玩家进游戏后在消息栏里提示一次
                portShiftFrom = configuredPort;
                portShiftTo = port;
                LOGGER.warn("MTR Map HTTP 端口 {} 已被占用，已自动顺延到 {}", configuredPort, port);
            } else {
                LOGGER.info("MTR Map HTTP 服务器已启动，监听端口 {}", port);
            }
        } catch (Exception e) {
            // 顺延范围内的端口全部被占用：服务起不来，同样等玩家进游戏后提示一次
            portFailedPort = MtrMapConfig.getPort();
            LOGGER.error("MTR Map HTTP 服务器启动失败：{} 端口以后的 {} 个端口都不可用",
                    portFailedPort, MapHttpServer.MAX_PORT_ATTEMPTS, e);
        }
    }

    /** 服务器关闭：停 HTTP、清数据。 */
    public static void onServerStopping(MinecraftServer server) {
        try {
            MapHttpServer.stop();
            LOGGER.info("MTR Map HTTP 服务器已停止");
        } catch (Exception e) {
            LOGGER.error("MTR Map HTTP 服务器停止失败", e);
        }
        MtrMapConfig.setActivePort(0);
        portShiftFrom = -1;
        portShiftTo = -1;
        portFailedPort = -1;
        portNoticeSent.clear();
        portSynced.clear();
        currentServer = null;
        NavTaskStore.clear();
        TripStore.clear();
        MapDataCollector.clearRailwayData();
    }

    /** 每个 tick 末更新玩家位置。 */
    public static void onServerTick(MinecraftServer server) {
        PlayerTracker.update(server);
        syncMapPort(server);
        notifyPortStatus(server);
    }

    /**
     * 把地图 HTTP 端口同步给每个在线玩家（每人只发一次）。
     *
     * <p>专用服务端上玩家在别的机器上玩，客户端不知道地图服务监听在哪个端口，
     * 必须由服务端告知；客户端据此拼出 {@code http://<服务器地址>:<端口>} 访问 F6 窗口与导航接口。
     * 放在 tick 里而不是玩家登录事件里，是为了不依赖各加载器的登录事件。
     */
    private static void syncMapPort(MinecraftServer server) {
        int port = MtrMapConfig.getActivePort();
        java.util.Set<UUID> online = new java.util.HashSet<>();
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            online.add(uuid);
            if (portSynced.contains(uuid)) {
                continue;
            }
            try {
                // 发送成功才记账：个别加载器在玩家刚进来时还没准备好自定义包，
                // 失败就留着下一 tick 再发，不要一次性放弃。
                com.mtrmap.platform.Platform.get().sendMapPort(player, port);
                portSynced.add(uuid);
            } catch (Exception e) {
                MtrMapCommon.LOGGER.warn("同步地图端口给玩家失败，稍后重试", e);
            }
        }
        // 玩家退出后从集合里移除，下次进服重新同步（重复发送无害）
        portSynced.retainAll(online);
    }

    /**
     * 端口出问题时（顺延了、或顺延范围内全被占满导致服务起不来），
     * 在玩家进游戏后往消息栏发一条提示。
     *
     * <p>这些情况都发生在服务器启动那一刻，那时通常没有玩家在线，所以要等玩家进来时补发；
     * 每位玩家只提示一次。
     */
    private static void notifyPortStatus(MinecraftServer server) {
        boolean failed = portFailedPort >= 0;
        boolean shifted = portShiftTo >= 0 && portShiftTo != portShiftFrom;
        if (!failed && !shifted) {
            return;
        }
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!portNoticeSent.add(player.getUUID())) {
                continue;
            }
            if (failed) {
                player.sendSystemMessage(literal("§c[MTR Map] " + portFailedPort + "端口以后的 "
                        + MapHttpServer.MAX_PORT_ATTEMPTS + " 个端口已被占满，地图服务无法开启！"));
            } else {
                player.sendSystemMessage(literal("§e[MTR Map] 端口 " + portShiftFrom + " 已被占用，"
                        + "地图服务已顺延到 " + portShiftTo + "；网页请用「服务器地址:" + portShiftTo
                        + "」访问，游戏内 F6 窗口会自动使用这个端口。"));
            }
        }
    }

    /** 注册 /mtrmap showdepots <true|false> 命令。 */
    public static void registerCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mtrmap")
                .then(Commands.literal("showdepots")
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(context -> {
                                    boolean value = BoolArgumentType.getBool(context, "value");
                                    MtrMapConfig.setShowDepots(value);
                                    MtrMapConfig.save();
                                    // 1.19.2 的 sendSuccess 直接收 Component，1.20.1 起才收 Supplier
                                    //? if >=1.20.1 {
                                    context.getSource().sendSuccess(() ->
                                            literal("§a车厂显示已设置为: " + value), false);
                                    //?} else {
                                    /*context.getSource().sendSuccess(
                                            literal("§a车厂显示已设置为: " + value), false);
                                    *///?}
                                    return 1;
                                }))));
    }

    /**
     * 构造一个纯文本 Component。
     *
     * <p>{@code Component.literal} 是 MC 1.19 才加的，1.18.2 与 1.16.5 只能用
     * {@code TextComponent}；老分支里写全限定名，免得给老版本引入一个在新版本已经删掉的 import。
     */
    //? if >=1.19 {
    private static Component literal(String text) {
        return Component.literal(text);
    }
    //?} else {
    /*private static Component literal(String text) {
        return new net.minecraft.network.chat.TextComponent(text);
    }
    *///?}

    /** 收到客户端推送的头像：仅接受当前在线玩家对应的头像。 */
    public static void onAvatarReceived(MinecraftServer server, UUID uuid, byte[] png) {
        if (server == null || uuid == null || png == null || png.length == 0) {
            return;
        }
        server.execute(() -> {
            if (server.getPlayerList().getPlayer(uuid) != null) {
                AvatarHandler.putAvatar(uuid.toString(), png);
            }
        });
    }

    /**
     * 收到游戏内地图窗口的数据请求：在服务端线程上取数，并把结果分块发回。
     *
     * <p>和网页走的 HTTP 端点共用 {@link MapRequestRouter} 这一套取数逻辑，
     * 区别只是回包走模组网络包，游戏内地图因此不再依赖 1145 端口。
     *
     * @param path 形如 {@code /api/data}，可带查询串（{@code /api/trips?uuid=...}）
     * @param body POST 体（JSON 文本），GET 请求传空串
     */
    public static void onMapRequest(MinecraftServer server, net.minecraft.server.level.ServerPlayer player,
                                    int requestId, String path, String body) {
        if (server == null || player == null) {
            return;
        }
        UUID sender = player.getUUID();
        server.execute(() -> {
            try {
                String json = MapRequestRouter.handle(server, path, body, sender);
                sendMapChunks(player, requestId, json);
            } catch (Throwable t) {
                // 玩家可能在取数期间断开：只记一行日志，不影响服务器
                LOGGER.warn("处理地图数据请求失败：{}", path, t);
            }
        });
    }

    /** 把一份 JSON 文本切成若干块发给客户端（按 requestId 归属，客户端负责拼回）。 */
    private static void sendMapChunks(net.minecraft.server.level.ServerPlayer player, int requestId, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        int total = Math.max(1, (bytes.length + MAP_CHUNK_SIZE - 1) / MAP_CHUNK_SIZE);
        for (int index = 0; index < total; index++) {
            int offset = index * MAP_CHUNK_SIZE;
            int length = Math.min(MAP_CHUNK_SIZE, bytes.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(bytes, offset, chunk, 0, length);
            com.mtrmap.platform.Platform.get().sendMapData(player, requestId, index, total, chunk);
        }
    }

    public static MinecraftServer getCurrentServer() {
        return currentServer;
    }

    /**
     * 读空一个输入流。
     *
     * <p>{@code InputStream.readAllBytes} 是 Java 9 才加的，而 1.16.5 的编译目标是 Java 8，
     * 所以这里手写一份等价的实现，所有版本共用。
     */
    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * 解析 JSON 字符串。
     *
     * <p>静态的 {@code JsonParser.parseString} 是 Gson 2.8.6 才加的，MC 1.16.5 自带的 Gson
     * 更老，只能用实例方法；1.18.2 起用静态写法。
     */
    public static com.google.gson.JsonElement parseJson(String json) {
        //? if >=1.18.2 {
        return com.google.gson.JsonParser.parseString(json);
        //?} else {
        /*return new com.google.gson.JsonParser().parse(json);
        *///?}
    }
}
