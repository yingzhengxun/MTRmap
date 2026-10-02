package com.mtrmap.fabric;

//? if >=1.21.1 {
/*import com.mtrmap.MtrMapCommon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

// 客户端 -> 服务端的头像推送。1.21 起 Fabric 的网络 API 只认 CustomPacketPayload，
// 不能再像以前那样直接往通道里塞一个裸缓冲区，所以这里包成一个载荷。
// 注意：本文件整体处于 Stonecutter 的注释分支里，只能写 // 行注释。
public record AvatarPayload(UUID uuid, byte[] png) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<AvatarPayload> TYPE =
			new CustomPacketPayload.Type<>(MtrMapCommon.AVATAR_CHANNEL);

	public static final StreamCodec<RegistryFriendlyByteBuf, AvatarPayload> CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeUUID(payload.uuid());
				buf.writeByteArray(payload.png());
			},
			buf -> new AvatarPayload(buf.readUUID(), buf.readByteArray()));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
*///?}
