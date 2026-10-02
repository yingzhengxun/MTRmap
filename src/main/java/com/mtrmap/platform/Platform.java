package com.mtrmap.platform;

import java.nio.file.Path;

/**
 * 平台实现的静态持有者。由平台入口类在初始化时注入。
 */
public final class Platform {

    private static MtrMapPlatform impl;

    private Platform() {
    }

    public static void set(MtrMapPlatform p) {
        impl = p;
    }

    public static MtrMapPlatform get() {
        MtrMapPlatform p = impl;
        if (p == null) {
            throw new IllegalStateException("MtrMapPlatform 尚未初始化");
        }
        return p;
    }

    public static boolean isModLoaded(String id) {
        MtrMapPlatform p = impl;
        return p != null && p.isModLoaded(id);
    }

    public static Path getConfigDir() {
        return get().getConfigDir();
    }
}
