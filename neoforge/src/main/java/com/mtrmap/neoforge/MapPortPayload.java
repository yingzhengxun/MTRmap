package com.mtrmap.neoforge;

import com.mtrmap.MtrMapCommon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 -> 客户端的地图端口同步载荷：只带一个 HTTP 端口。
 *
 * <p>专用服务端上玩家在别的机器上玩，客户端读不到服务器那份 mtrmap.json，
 * 只能由服务端把实际监听的端口发过来。注册方式与 {@link AvatarPayload} 相同，
 * 区别只是方向为 {@code playToClient}。
 */
public record MapPortPayload(int port) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MapPortPayload> TYPE =
            new CustomPacketPayload.Type<>(MtrMapCommon.PORT_CHANNEL);

    public static final StreamCodec<RegistryFriendlyByteBuf, MapPortPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeInt(payload.port),
                    buf -> new MapPortPayload(buf.readInt()));

    @Override
    public CustomPacketPayload.Type<MapPortPayload> type() {
        return TYPE;
    }
}
