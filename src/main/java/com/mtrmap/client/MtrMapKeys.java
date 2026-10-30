package com.mtrmap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mtrmap.MtrMapCommon;
import com.mtrmap.client.map.MapScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * 模组热键。
 *
 * 全部通过 MC 自带的按键绑定系统注册（选项 → 控制 → 按键绑定 里可改键），
 * 不再用 GLFW 直接轮询，这样和原版按键不会互相冲突、也能显示在按键列表里。
 *
 * 热键对象由各平台入口在客户端初始化时创建并注册，本类只负责每 tick 消费点击。
 */
public final class MtrMapKeys {

    public static final String CATEGORY = "key.categories.rbmm";

    /** 打开/关闭游戏内地图窗口（默认 F6） */
    public static KeyMapping openMap;
    /** 收起/展开游戏内导航（默认 PgUp） */
    public static KeyMapping toggleNav;
    /** 打开模组设置界面（默认未绑定，可在按键绑定里自行设置） */
    public static KeyMapping openSettings;

    private MtrMapKeys() {
    }

    /** 创建热键对象（客户端、注册前调用一次即可）。 */
    public static void create() {
        if (openMap != null) {
            return;
        }
        openMap = new KeyMapping("key.rbmm.open_map", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F6, CATEGORY);
        toggleNav = new KeyMapping("key.rbmm.toggle_nav", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_PAGE_UP, CATEGORY);
        openSettings = new KeyMapping("key.rbmm.open_settings", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    }

    /** 每 tick 消费一次按键（边沿由 MC 的 consumeClick 处理，按住不会连发）。 */
    public static void poll(Minecraft client) {
        if (openMap == null) {
            return;
        }
        while (openMap.consumeClick()) {
            toggleMap(client);
        }
        while (toggleNav.consumeClick()) {
            com.mtrmap.client.nav.NavController.toggleCollapsed();
        }
        while (openSettings.consumeClick()) {
            if (client.screen == null || client.screen instanceof MapScreen) {
                client.setScreen(new SettingsScreen(client.screen instanceof MapScreen
                        ? client.screen : null));
            }
        }
    }

    /** 地图窗口开关：只在没有任何界面、或当前界面就是地图时响应。 */
    private static void toggleMap(Minecraft client) {
        if (client.screen instanceof MapScreen) {
            client.setScreen(null);
        } else if (client.screen == null) {
            MtrMapCommon.LOGGER.info("打开地图窗口");
            client.setScreen(new MapScreen());
        }
    }
}