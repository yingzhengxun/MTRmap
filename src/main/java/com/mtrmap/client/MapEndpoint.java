package com.mtrmap.client;

import com.mtrmap.MtrMapCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * 地图网页的地址解析（只在客户端使用）。
 *
 * <p>只有「用系统浏览器打开地图网页」这一处需要它：游戏内的 F6 地图窗口与导航
 * 已经改走模组网络包，不再访问这个地址。
 *
 * <p>服务端把地图服务监听在它自己那台机器上，所以地址由两部分拼成：
 * <ul>
 *   <li>主机：连的是哪台服务器就用哪台（单人游戏没有服务器，用 127.0.0.1）；</li>
 *   <li>端口：由服务端通过 {@link MtrMapCommon#getMapPort()} 同步过来，
 *       单人游戏时同步不到就退回本地配置。</li>
 * </ul>
 *
 * <p>以前这里写死 {@code 127.0.0.1}，在专用服务端上指向的就是玩家自己的电脑，
 * 网页自然打不开。
 */
public final class MapEndpoint {

    private MapEndpoint() {
    }

    /** 形如 {@code http://host:port}，不带结尾斜杠 */
    public static String base() {
        return "http://" + host() + ":" + MtrMapCommon.getMapPort();
    }

    /** 在系统浏览器里打开地图网页用的地址 */
    public static String pageUrl() {
        return base();
    }

    /** 当前连接的主机名；单人游戏或读不到时退回 127.0.0.1 */
    private static String host() {
        Minecraft client = Minecraft.getInstance();
        ServerData server = client == null ? null : client.getCurrentServer();
        String ip = server == null ? null : server.ip;
        if (ip == null) {
            return "127.0.0.1";
        }
        ip = ip.trim();
        if (ip.isEmpty()) {
            return "127.0.0.1";
        }
        // 玩家填的可能是 "example.com:25565" 或 "[::1]:25565"：这里只要主机名，
        // 端口用地图服务自己的（和 MC 的端口无关）。
        // IPv6 字面量形如 [::1]，最后一个冒号在 ] 之后才算端口分隔符。
        int colon = ip.lastIndexOf(':');
        if (colon > 0 && ip.indexOf(']') < colon) {
            ip = ip.substring(0, colon);
        }
        return ip.isEmpty() ? "127.0.0.1" : ip;
    }
}
