package com.mtrmap.neoforge;

import com.mtrmap.client.MtrMapClientCommon;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * NeoForge 客户端事件转接：客户端 tick 与断开连接。
 * 用 {@code value = Dist.CLIENT} 限定只在客户端注册，避免在专用服务器上触碰客户端类。
 */
@EventBusSubscriber(modid = "mtrmap", value = Dist.CLIENT)
public final class NeoForgeClientEventHandler {

    private static boolean clientInitialized = false;

    private NeoForgeClientEventHandler() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!clientInitialized) {
            clientInitialized = true;
            // 启动客户端通用逻辑（导航轮询、游戏内地图窗口的 F6 按键监听）。
            // 这里懒加载而非 FMLClientSetupEvent：本类挂的是 GAME 事件总线，
            // 而 FMLClientSetupEvent 走 MOD 总线，两者不通用。
            MtrMapClientCommon.init();
        }
        MtrMapClientCommon.onClientTick(Minecraft.getInstance());
    }

    @SubscribeEvent
    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        MtrMapClientCommon.onDisconnect();
    }
}
