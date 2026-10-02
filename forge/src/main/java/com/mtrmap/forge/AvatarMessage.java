package com.mtrmap.forge;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * 客户端 -> 服务端的头像推送消息：UUID + PNG 字节。
 * 不用 record：1.16.5 的编译目标是 Java 8。
 */
public final class AvatarMessage {

    private final UUID uuid;
    private final byte[] png;

    public AvatarMessage(UUID uuid, byte[] png) {
        this.uuid = uuid;
        this.png = png;
    }

    public UUID uuid() {
        return uuid;
    }

    public byte[] png() {
        return png;
    }

    public static void encode(AvatarMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.uuid);
        buf.writeByteArray(msg.png);
    }

    public static AvatarMessage decode(FriendlyByteBuf buf) {
        return new AvatarMessage(buf.readUUID(), buf.readByteArray());
    }
}
