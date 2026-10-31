package com.mtrmap.neoforge;

import com.mtrmap.MtrMapCommon;
import com.mtrmap.platform.Platform;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod("rbmm")
public class MtrMapNeoForge {

    // NeoForge 会把 mod 事件总线注入到 @Mod 构造器里。
    // 网络注册（RegisterPayloadHandlersEvent）属于 mod 总线事件，所以必须拿 modEventBus 来挂监听。
    public MtrMapNeoForge(IEventBus modEventBus) {
        // 注入平台实现必须在读取配置之前（MtrMapConfig 依赖 Platform.getConfigDir()）。
        Platform.set(new NeoForgePlatformImpl());
        MtrMapCommon.init();
        NeoForgeNetwork.register(modEventBus);

        // 模组菜单里的「配置」按钮：打开本模组设置界面（只在客户端注册）
        if (net.neoforged.api.distmarker.Dist.CLIENT.isClient()) {
            net.neoforged.fml.ModList.get().getModContainerById("rbmm").ifPresent(container ->
                    container.registerExtensionPoint(
                            net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                            (mc, parent) -> new com.mtrmap.client.SettingsScreen(parent)));
        }
    }
}
