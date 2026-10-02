package com.mtrmap.forge;

import com.mtrmap.client.MtrMapClientCommon;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge 客户端事件转接：客户端 tick 与断开连接。
 * 用 {@code value = Dist.CLIENT} 限定只在客户端注册，避免在专用服务器上触碰客户端类。
 */
@Mod.EventBusSubscriber(modid = "mtrmap", value = Dist.CLIENT)
public final class ForgeClientEventHandler {

    private static boolean clientInitialized = false;

    private ForgeClientEventHandler() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            if (!clientInitialized) {
                clientInitialized = true;
                // 启动客户端通用逻辑（Xaero 叠加层数据轮询；未装 Xaero 时内部会直接跳过）。
                // 这里懒加载而非 FMLClientSetupEvent：本类挂的是 FORGE 事件总线，
                // 而 FMLClientSetupEvent 走 MOD 总线，两者不通用。
                MtrMapClientCommon.init();
            }
            MtrMapClientCommon.onClientTick(Minecraft.getInstance());
        }
    }

    // 1.19（Forge 41.x）把内部类从 LoggedOutEvent 改名成了 LoggingOut；
    // 1.16.5 / 1.18.2 用的还是旧名字。
    //? if >=1.19 {
    @SubscribeEvent
    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        MtrMapClientCommon.onDisconnect();
    }
    //?} else {
    /*@SubscribeEvent
    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        MtrMapClientCommon.onDisconnect();
    }
    *///?}
}
