package com.mtrmap.forge;

import com.mtrmap.MtrMapCommon;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
// 服务器生命周期事件的包路径分三代：
//   1.18.2+  net.minecraftforge.event.server
//   1.17.1   net.minecraftforge.fmlserverevents（1.19 之前的过渡包）
//   1.16.5   net.minecraftforge.fml.event.server
//? if >=1.18.2 {
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
//?} else if >=1.17 {
/*import net.minecraftforge.fmlserverevents.FMLServerStartedEvent;
import net.minecraftforge.fmlserverevents.FMLServerStoppingEvent;
*///?} else {
/*import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
*///?}

/**
 * Forge 服务端事件转接：生命周期 / tick / 命令。
 * 默认 bus = FORGE，默认 value = 两端，因此客户端集成服务器与专用服务器都会生效。
 */
@Mod.EventBusSubscriber(modid = "mtrmap")
public final class ForgeEventHandler {

    private ForgeEventHandler() {
    }

    //? if >=1.18.2 {
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MtrMapCommon.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MtrMapCommon.onServerStopping(event.getServer());
    }
    //?} else {
    /*@SubscribeEvent
    public static void onServerStarted(FMLServerStartedEvent event) {
        MtrMapCommon.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(FMLServerStoppingEvent event) {
        MtrMapCommon.onServerStopping(event.getServer());
    }
    *///?}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            // ServerTickEvent.getServer() 是 1.19 才加的；1.18.2 / 1.17.1 / 1.16.5 只能从
            // ServerLifecycleHooks 取，且该类的包路径换过两次：
            //   1.18.2+  net.minecraftforge.server
            //   1.17.1   net.minecraftforge.fmllegacy.server
            //   1.16.5   net.minecraftforge.fml.server
            //? if >=1.19 {
            MtrMapCommon.onServerTick(event.getServer());
            //?} else if >=1.18.2 {
            /*MtrMapCommon.onServerTick(net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer());
            *///?} else if >=1.17 {
            /*MtrMapCommon.onServerTick(net.minecraftforge.fmllegacy.server.ServerLifecycleHooks.getCurrentServer());
            *///?} else {
            /*MtrMapCommon.onServerTick(net.minecraftforge.fml.server.ServerLifecycleHooks.getCurrentServer());
            *///?}
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        MtrMapCommon.registerCommands(event.getDispatcher());
    }
}
