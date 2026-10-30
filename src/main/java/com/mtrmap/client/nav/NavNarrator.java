package com.mtrmap.client.nav;

import com.mtrmap.config.MtrMapConfig;

/**
 * 讲述人语音播报。
 *
 * 直接调用 Minecraft 客户端自带的 text2speech（com.mojang.text2speech.Narrator），
 * 用反射拿实例，避免在纯服务端环境加载客户端独有的类；是否播报由模组设置里的
 * 「讲述人播报」开关控制。
 */
public final class NavNarrator {

    private static Object narrator;
    private static java.lang.reflect.Method sayMethod;
    private static boolean unavailable;

    private NavNarrator() {
    }

    public static void speak(String text) {
        if (!MtrMapConfig.isTtsEnabled() || text == null || text.isEmpty()) {
            return;
        }
        try {
            if (narrator == null && !unavailable) {
                Class<?> type = Class.forName("com.mojang.text2speech.Narrator");
                narrator = type.getMethod("getNarrator").invoke(null);
                sayMethod = type.getMethod("say", String.class, boolean.class);
                if (narrator == null) {
                    unavailable = true;
                    return;
                }
            }
            if (unavailable || narrator == null) {
                return;
            }
            sayMethod.invoke(narrator, text, true);
        } catch (Throwable t) {
            // 没有讲述人实现（比如某些服务端/精简客户端）时静默停用
            unavailable = true;
        }
    }
}