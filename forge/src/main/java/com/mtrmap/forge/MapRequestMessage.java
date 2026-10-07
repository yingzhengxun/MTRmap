package com.mtrmap.forge;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 -> 服务端的地图数据请求：path（可带查询串）+ POST 体。
 * 服务端处理完把结果分块回传（{@link MapDataMessage}）。
 *
 * <p>不用 record：1.16.5 的编译目标是 Java 8。
 */
public final class MapRequestMessage {

    /** 请求里字符串的长度上限，与接收端一致 */
    public static final int MAX_TEXT = 1 << 20;

    private final int requestId;
    private final String path;
    private final String body;

    public MapRequestMessage(int requestId, String path, String body) {
        this.requestId = requestId;
        this.path = path;
        this.body = body;
    }

    public int requestId() {
        return requestId;
    }

    public String path() {
        return path;
    }

    public String body() {
        return body;
    }

    public static void encode(MapRequestMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.requestId);
        buf.writeUtf(msg.path, MAX_TEXT);
        buf.writeUtf(msg.body, MAX_TEXT);
    }

    public static MapRequestMessage decode(FriendlyByteBuf buf) {
        return new MapRequestMessage(buf.readVarInt(), buf.readUtf(MAX_TEXT), buf.readUtf(MAX_TEXT));
    }
}
