package com.mtrmap.neoforge;

import com.mtrmap.MtrMapCommon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * 客户端 -> 服务端的头像推送载荷：UUID + PNG 字节。
 *
 * <p>NeoForge 1.21.1 的网络不再用 Forge 的 SimpleChannel，而是走原版的自定义载荷体系：
 * 载荷实现 {@link CustomPacketPayload}，序列化由 {@link StreamCodec} 描述，
 * 注册进 {@code PayloadRegistrar}。{@link #TYPE} 就是通道标识，直接复用
 * {@link MtrMapCommon#AVATAR_CHANNEL}。
 */
public record AvatarPayload(UUID uuid, byte[] png) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AvatarPayload> TYPE =
            new CustomPacketPayload.Type<>(MtrMapCommon.AVATAR_CHANNEL);

    public static final StreamCodec<RegistryFriendlyByteBuf, AvatarPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeUUID(payload.uuid);
                        buf.writeByteArray(payload.png);
                    },
                    buf -> new AvatarPayload(buf.readUUID(), buf.readByteArray()));

    @Override
    public CustomPacketPayload.Type<AvatarPayload> type() {
        return TYPE;
    }
}
