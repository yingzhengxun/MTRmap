package com.mtrmap.neoforge;

import com.mtrmap.MtrMapCommon;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge 服务端事件转接：生命周期 / tick / 命令。
 *
 * <p>{@link EventBusSubscriber} 默认 bus = GAME、默认 value = 两端，
 * 因此客户端集成服务器与专用服务器都会生效。
 * 1.21.1 的 tick 事件已拆分为 {@code ServerTickEvent.Pre/Post}，Post 即原 Phase.END。
 */
@EventBusSubscriber(modid = "mtrmap")
public final class NeoForgeEventHandler {

    private NeoForgeEventHandler() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MtrMapCommon.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MtrMapCommon.onServerStopping(event.getServer());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MtrMapCommon.onServerTick(event.getServer());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        MtrMapCommon.registerCommands(event.getDispatcher());
    }
}
