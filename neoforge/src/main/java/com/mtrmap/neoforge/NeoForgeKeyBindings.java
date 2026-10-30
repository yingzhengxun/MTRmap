package com.mtrmap.neoforge;

import com.mtrmap.client.MtrMapKeys;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** 把模组热键注册进 MC 的「按键绑定」列表（NeoForge 走 MOD 总线的注册事件）。 */
@EventBusSubscriber(modid = "rbmm", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class NeoForgeKeyBindings {

    private NeoForgeKeyBindings() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        MtrMapKeys.create();
        event.register(MtrMapKeys.openMap);
        event.register(MtrMapKeys.toggleNav);
        event.register(MtrMapKeys.openSettings);
    }
}