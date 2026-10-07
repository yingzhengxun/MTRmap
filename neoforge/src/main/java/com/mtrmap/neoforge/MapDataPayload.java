package com.mtrmap.neoforge;

import com.mtrmap.MtrMapCommon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 -> 客户端的地图数据分块载荷：整份 JSON 按 MAP_CHUNK_SIZE 切片后逐块发来，
 * 客户端按 {@code requestId} 归档、按 {@code index} 拼回。线网大时一份 JSON 几百 KB，只能分块。
 */
public record MapDataPayload(int requestId, int index, int total, byte[] chunk) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MapDataPayload> TYPE =
            new CustomPacketPayload.Type<>(MtrMapCommon.MAP_DATA_CHANNEL);

    public static final StreamCodec<RegistryFriendlyByteBuf, MapDataPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.requestId);
                        buf.writeVarInt(payload.index);
                        buf.writeVarInt(payload.total);
                        buf.writeByteArray(payload.chunk);
                    },
                    buf -> new MapDataPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray()));

    @Override
    public CustomPacketPayload.Type<MapDataPayload> type() {
        return TYPE;
    }
}
