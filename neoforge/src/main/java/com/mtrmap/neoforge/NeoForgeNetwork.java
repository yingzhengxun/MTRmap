package com.mtrmap.neoforge;

import com.mtrmap.MtrMapCommon;
import com.mtrmap.client.MtrMapClientCommon;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.UUID;

/**
 * NeoForge 网络通道：承载客户端推送头像的载荷，以及服务端下发地图 HTTP 端口的载荷。
 * 收到后转交给与加载器无关的 {@link MtrMapCommon#onAvatarReceived} /
 * {@link MtrMapCommon#onMapPortReceived(int)}。
 *
 * <p>与 Forge 版（SimpleChannel）的差异：这里用 NeoForge 1.21.1 的自定义载荷 API，
 * 注册事件 {@link RegisterPayloadHandlersEvent} 是 mod 总线事件，故由 mod 构造器传入的
 * {@link IEventBus} 挂监听（{@link #register(IEventBus)}）。
 */
public final class NeoForgeNetwork {

    /** 网络协议版本：与 Forge 版保持一致，便于两端比对。 */
    private static final String PROTOCOL_VERSION = "1";

    private NeoForgeNetwork() {
    }

    /** 注册载荷处理器（在 mod 构造期拿到 mod 总线后调用一次）。 */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(NeoForgeNetwork::onRegisterPayloadHandlers);
    }

    private static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(
                AvatarPayload.TYPE,
                AvatarPayload.STREAM_CODEC,
                (payload, context) -> MtrMapCommon.onAvatarReceived(
                        MtrMapCommon.getCurrentServer(), payload.uuid(), payload.png()));

        // 地图端口是服务端 -> 客户端：客户端据此才知道地图服务监听在哪个端口
        registrar.playToClient(
                MapPortPayload.TYPE,
                MapPortPayload.STREAM_CODEC,
                (payload, context) -> MtrMapCommon.onMapPortReceived(payload.port()));

        // 游戏内地图取数：客户端请求（C2S）+ 服务端分块回包（S2C）
        registrar.playToServer(
                MapRequestPayload.TYPE,
                MapRequestPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        MtrMapCommon.onMapRequest(MtrMapCommon.getCurrentServer(), player,
                                payload.requestId(), payload.path(), payload.body());
                    }
                });
        registrar.playToClient(
                MapDataPayload.TYPE,
                MapDataPayload.STREAM_CODEC,
                (payload, context) -> MtrMapClientCommon.onMapDataReceived(
                        payload.requestId(), payload.index(), payload.total(), payload.chunk()));
    }

    /** 客户端把头像推给服务端。 */
    public static void sendAvatarToServer(UUID uuid, byte[] png) {
        PacketDistributor.sendToServer(new AvatarPayload(uuid, png));
    }

    /** 服务端把地图 HTTP 端口告诉某个玩家。 */
    public static void sendMapPort(ServerPlayer player, int port) {
        PacketDistributor.sendToPlayer(player, new MapPortPayload(port));
    }

    /** 客户端向服务端要地图数据。 */
    public static void sendMapRequest(int requestId, String path, String body) {
        PacketDistributor.sendToServer(new MapRequestPayload(requestId, path, body));
    }

    /** 服务端把地图数据的一个分块发给某个玩家。 */
    public static void sendMapData(ServerPlayer player, int requestId, int index, int total, byte[] chunk) {
        PacketDistributor.sendToPlayer(player, new MapDataPayload(requestId, index, total, chunk));
    }
}
