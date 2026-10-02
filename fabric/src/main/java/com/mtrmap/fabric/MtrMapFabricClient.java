package com.mtrmap.fabric;

import com.mtrmap.client.MtrMapClientCommon;
import com.mtrmap.platform.Platform;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

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
    }
}
