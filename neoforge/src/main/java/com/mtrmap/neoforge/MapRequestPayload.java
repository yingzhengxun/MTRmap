package com.mtrmap.neoforge;

import com.mtrmap.MtrMapCommon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端 -> 服务端的地图数据请求载荷：path（可带查询串）+ POST 体。
 *
 * <p>服务端处理完把结果分块回传（{@link MapDataPayload}）。
 * 注册方式与 {@link AvatarPayload} 相同，区别是方向为 {@code playToServer}。
 */
public record MapRequestPayload(int requestId, String path, String body) implements CustomPacketPayload {

    /** 请求里字符串的长度上限：导航任务 JSON 可能不小，给宽一点 */
    public static final int MAX_TEXT = 1 << 20;

    public static final CustomPacketPayload.Type<MapRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(MtrMapCommon.MAP_REQUEST_CHANNEL);

    public static final StreamCodec<RegistryFriendlyByteBuf, MapRequestPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.requestId);
                        buf.writeUtf(payload.path, MAX_TEXT);
                        buf.writeUtf(payload.body, MAX_TEXT);
                    },
                    buf -> new MapRequestPayload(buf.readVarInt(), buf.readUtf(MAX_TEXT), buf.readUtf(MAX_TEXT)));

    @Override
    public CustomPacketPayload.Type<MapRequestPayload> type() {
        return TYPE;
    }
}
