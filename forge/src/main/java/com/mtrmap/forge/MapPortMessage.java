package com.mtrmap.forge;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 服务端 -> 客户端的地图端口同步消息：只带一个 HTTP 端口。
 *
 * <p>专用服务端上玩家在别的机器上玩，客户端读不到服务器那份 mtrmap.json，
 * 只能由服务端把实际监听的端口发过来。
 *
 * <p>不用 record：1.16.5 的编译目标是 Java 8。
 */
public final class MapPortMessage {

    private final int port;

    public MapPortMessage(int port) {
        this.port = port;
    }

    public int port() {
        return port;
    }

    public static void encode(MapPortMessage msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.port);
    }

    public static MapPortMessage decode(FriendlyByteBuf buf) {
        return new MapPortMessage(buf.readInt());
    }
}
