package com.mtrmap.fabric;

import com.mtrmap.MtrMapCommon;
import com.mtrmap.client.MtrMapClientCommon;
import com.mtrmap.platform.Platform;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Fabric 客户端入口：注入平台实现，并把客户端 tick / 断开连接事件
 * 转接到 {@link MtrMapClientCommon}。
 */
public class MtrMapFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Platform.set(new FabricPlatformImpl());
        MtrMapClientCommon.init();

        ClientTickEvents.END_CLIENT_TICK.register(MtrMapClientCommon::onClientTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> MtrMapClientCommon.onDisconnect());

        // 服务端告知地图 HTTP 端口：专用服务端上客户端读不到服务器那份配置，
        // 只能靠这个包知道地图服务监听在哪个端口
        //? if >=1.21.1 {
        /*ClientPlayNetworking.registerGlobalReceiver(MapPortPayload.TYPE, (payload, context) ->
                MtrMapCommon.onMapPortReceived(payload.port()));
        *///?} else {
        ClientPlayNetworking.registerGlobalReceiver(MtrMapCommon.PORT_CHANNEL,
                (client, handler, buf, responseSender) -> MtrMapCommon.onMapPortReceived(buf.readInt()));
        //?}
    }
}
