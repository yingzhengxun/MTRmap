package com.mtrmap.forge;

import com.mtrmap.client.MtrMapKeys;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 把模组热键注册进 MC 的「按键绑定」列表（Forge 走 MOD 总线的注册事件）。 */
@Mod.EventBusSubscriber(modid = "rbmm", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ForgeKeyBindings {

    private ForgeKeyBindings() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        MtrMapKeys.create();
        event.register(MtrMapKeys.openMap);
        event.register(MtrMapKeys.toggleNav);
        event.register(MtrMapKeys.openSettings);
    }
}