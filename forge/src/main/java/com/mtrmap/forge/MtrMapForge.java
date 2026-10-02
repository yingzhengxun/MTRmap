package com.mtrmap.forge;

import com.mtrmap.MtrMapCommon;
import com.mtrmap.platform.Platform;
import net.minecraftforge.fml.common.Mod;

@Mod("mtrmap")
public class MtrMapForge {

    public MtrMapForge() {
        // 注入平台实现必须在读取配置之前（MtrMapConfig 依赖 Platform.getConfigDir()）。
        Platform.set(new ForgePlatformImpl());
        MtrMapCommon.init();
        ForgeNetwork.register();
    }
}
