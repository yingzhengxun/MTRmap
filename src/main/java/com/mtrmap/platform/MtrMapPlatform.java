package com.mtrmap.platform;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 平台抽象层：把各加载器（Fabric / Forge）的差异收敛到这一个接口。
 *
 * <p>common 模块只能依赖本接口，不得直接引用任何 {@code net.fabricmc.*} /
 * {@code net.minecraftforge.*} 的类型。各平台模块在初始化时通过
 * {@link Platform#set(MtrMapPlatform)} 注入自己的实现。
 */
public interface MtrMapPlatform {

    /** 加载器名称，如 {@code "fabric"} / {@code "forge"} */
    String loaderName();

    /** 指定 modId 是否已加载 */
    boolean isModLoaded(String modId);

    /** 配置目录（= 游戏目录），用于 mods/mapconfig/mtrmap.json */
    Path getConfigDir();

    /**
     * 客户端把头像 PNG 推给服务端。
     * 服务端侧只是发起通道注册，由平台实现负责具体的网络协议。
     */
    void sendAvatarToServer(UUID uuid, byte[] png);

    /**
     * 服务端把地图 HTTP 端口告诉某个玩家（服务端 -> 客户端）。
     *
     * <p>专用服务端上玩家在别的机器上玩，客户端读不到服务器那份 mtrmap.json，
     * 只能由服务端把实际监听的端口发过来，客户端才能拼出可访问的地图服务地址。
     */
    void sendMapPort(net.minecraft.server.level.ServerPlayer player, int port);
}
