package com.mtrmap.forge;

import com.mtrmap.MtrMapCommon;
import com.mtrmap.platform.Platform;
import net.minecraftforge.fml.common.Mod;

@Mod("rbmm")
public class MtrMapForge {

    public MtrMapForge() {
        // 注入平台实现必须在读取配置之前（MtrMapConfig 依赖 Platform.getConfigDir()）。
        Platform.set(new ForgePlatformImpl());
        MtrMapCommon.init();
        ForgeNetwork.register();

        // 模组菜单里的「配置」按钮：打开本模组设置界面（只在客户端注册）
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT) {
        // 内置 MCEF：先把随包的原生库解出来，并关掉 MCEF 的联网下载（改成走本地镜像）
        com.mtrmap.client.cef.CefSetup.prepare();
            net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(
                    net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory(
                            (mc, parent) -> new com.mtrmap.client.SettingsScreen(parent)));
        }
    }
}
