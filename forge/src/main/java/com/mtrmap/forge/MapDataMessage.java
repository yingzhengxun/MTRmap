package com.mtrmap.forge;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 服务端 -> 客户端的地图数据分块：整份 JSON 按 MAP_CHUNK_SIZE 切片后逐块发来，
 * 客户端按 {@link #requestId()} 归档、按 {@link #index()} 拼回。
 *
 * <p>不用 record：1.16.5 的编译目标是 Java 8。
 */
public final class MapDataMessage {

    private final int requestId;
    private final int index;
    private final int total;
    private final byte[] chunk;

    public MapDataMessage(int requestId, int index, int total, byte[] chunk) {
        this.requestId = requestId;
        this.index = index;
        this.total = total;
        this.chunk = chunk;
    }

    public int requestId() {
        return requestId;
    }

    public int index() {
        return index;
    }

    public int total() {
        return total;
    }

    public byte[] chunk() {
        return chunk;
    }

    public static void encode(MapDataMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.requestId);
        buf.writeVarInt(msg.index);
        buf.writeVarInt(msg.total);
        buf.writeByteArray(msg.chunk);
    }

    public static MapDataMessage decode(FriendlyByteBuf buf) {
        return new MapDataMessage(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray());
    }
}
