package com.mtrmap.fabric;

//? if >=1.21.1 {
/*import com.mtrmap.MtrMapCommon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// 服务端 -> 客户端的地图端口同步。与 AvatarPayload 同理：1.21 起 Fabric 的网络 API
// 只认 CustomPacketPayload，不能再往通道里塞裸缓冲区。
// 注意：本文件整体处于 Stonecutter 的注释分支里，只能写 // 行注释。
public record MapPortPayload(int port) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<MapPortPayload> TYPE =
			new CustomPacketPayload.Type<>(MtrMapCommon.PORT_CHANNEL);

	public static final StreamCodec<RegistryFriendlyByteBuf, MapPortPayload> CODEC = StreamCodec.of(
			(buf, payload) -> buf.writeInt(payload.port()),
			buf -> new MapPortPayload(buf.readInt()));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
*///?}
