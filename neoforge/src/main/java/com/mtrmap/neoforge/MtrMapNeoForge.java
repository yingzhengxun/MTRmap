package com.mtrmap.neoforge;

import com.mtrmap.MtrMapCommon;
import com.mtrmap.platform.Platform;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod("mtrmap")
public class MtrMapNeoForge {

    // NeoForge 会把 mod 事件总线注入到 @Mod 构造器里。
    // 网络注册（RegisterPayloadHandlersEvent）属于 mod 总线事件，所以必须拿 modEventBus 来挂监听。
    public MtrMapNeoForge(IEventBus modEventBus) {
        // 注入平台实现必须在读取配置之前（MtrMapConfig 依赖 Platform.getConfigDir()）。
        Platform.set(new NeoForgePlatformImpl());
        MtrMapCommon.init();
        NeoForgeNetwork.register(modEventBus);
    }
}
