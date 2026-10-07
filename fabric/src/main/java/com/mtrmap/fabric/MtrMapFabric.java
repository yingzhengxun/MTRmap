package com.mtrmap.fabric;

import com.mtrmap.MtrMapCommon;
import com.mtrmap.platform.Platform;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
//? if >=1.19 {
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
//?} else {
/*// 1.18.2 / 1.16.5 还没有 command-api-v2，回调只有 (dispatcher, dedicated) 两个参数
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
*///?}
//? if >=1.21.1 {
/*import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
*///?}

import java.util.UUID;

/**
 * Fabric 主入口：注入平台实现，并把 Fabric 的生命周期 / 网络 / 命令事件
 * 转接到与加载器无关的 {@link MtrMapCommon}。
 */
public class MtrMapFabric implements ModInitializer {

    /** 请求里字符串（path / POST 体）的长度上限：导航任务 JSON 可能不小，给宽一点 */
    private static final int MAX_TEXT = 1 << 20;

    @Override
    public void onInitialize() {
        Platform.set(new FabricPlatformImpl());
        MtrMapCommon.init();

        // 接收客户端推送的头像；校验与入缓存的逻辑在 MtrMapCommon 里
        //? if >=1.21.1 {
        /*// 1.21 的 Fabric 网络要求先声明载荷类型，再注册接收器
        PayloadTypeRegistry.playC2S().register(AvatarPayload.TYPE, AvatarPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(AvatarPayload.TYPE, (payload, context) ->
                MtrMapCommon.onAvatarReceived(context.server(), payload.uuid(), payload.png()));
        // 地图端口是服务端 -> 客户端，类型两侧都要注册（客户端那边只负责解码）
        PayloadTypeRegistry.playS2C().register(MapPortPayload.TYPE, MapPortPayload.CODEC);
        // 游戏内地图取数：客户端请求（C2S）+ 服务端分块回包（S2C），
        // 与端口同步一样，载荷类型必须两侧都声明
        PayloadTypeRegistry.playC2S().register(MapRequestPayload.TYPE, MapRequestPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MapRequestPayload.TYPE, (payload, context) ->
                MtrMapCommon.onMapRequest(context.server(), context.player(),
                        payload.requestId(), payload.path(), payload.body()));
        PayloadTypeRegistry.playS2C().register(MapDataPayload.TYPE, MapDataPayload.CODEC);
        *///?} else {
        ServerPlayNetworking.registerGlobalReceiver(MtrMapCommon.AVATAR_CHANNEL,
                (server, player, handler, buf, responseSender) -> {
                    UUID uuid = buf.readUUID();
                    byte[] png = buf.readByteArray();
                    MtrMapCommon.onAvatarReceived(server, uuid, png);
                });
        // 游戏内地图取数请求：服务端按 path 取数后分块回给该玩家
        ServerPlayNetworking.registerGlobalReceiver(MtrMapCommon.MAP_REQUEST_CHANNEL,
                (server, player, handler, buf, responseSender) -> {
                    int requestId = buf.readVarInt();
                    String path = buf.readUtf(MAX_TEXT);
                    String body = buf.readUtf(MAX_TEXT);
                    MtrMapCommon.onMapRequest(server, player, requestId, path, body);
                });
        //?}

        ServerLifecycleEvents.SERVER_STARTED.register(MtrMapCommon::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(MtrMapCommon::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(MtrMapCommon::onServerTick);
        //? if >=1.19 {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                MtrMapCommon.registerCommands(dispatcher));
        //?} else {
        /*CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) ->
                MtrMapCommon.registerCommands(dispatcher));
        *///?}
    }
}
